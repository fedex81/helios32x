package sh2.sh2.drc;

import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2MMREG;
import sh2.event.SysEventManager;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Helper;
import sh2.sh2.drc.Ow2DrcOptimizer.PollType;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.StringJoiner;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;
import static sh2.sh2.drc.Ow2DrcOptimizer.UNKNOWN_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Block {
    private static final Logger LOG = LogHelper.getLogger(Sh2Block.class.getSimpleName());

    //needs to be (powerOf2 - 1)
    private static final int OPT_THRESHOLD2 = Integer.parseInt(System.getProperty("helios.32x.sh2.drc.stage2.hits", "31"));

    public static final Sh2Block INVALID_BLOCK = new Sh2Block(-1, MASTER);
    public static final int MAX_INST_LEN = (Sh2Prefetch.SH2_DRC_MAX_BLOCK_LEN >> 1);

    //0 - Master, 1 - Slave
    public static final int CPU_FLAG = 1 << 0;
    public static final int CACHE_FETCH_FLAG = 1 << 1;
    public static final int NO_JUMP_FLAG = 1 << 2;

    public static final int VALID_FLAG = 1 << 3;

    public Sh2Prefetcher.Sh2BlockUnit[] inst;
    public Sh2Prefetcher.Sh2BlockUnit curr;
    public int[] prefetchWords;
    public int prefetchPc, hits, start, end, pcMasked, prefetchLenWords, fetchMemAccessDelay, cyclesConsumed;
    public ByteBuffer fetchBuffer;
    public Sh2Block nextBlock = INVALID_BLOCK;
    public Sh2Prefetch.Sh2DrcContext drcContext;
    public Ow2DrcOptimizer.PollerCtx poller = UNKNOWN_POLLER;
    protected final static Sh2.Sh2Config sh2Config;
    public int blockFlags;
    public PollType pollType = PollType.UNKNOWN;
    public Runnable stage2Drc;
    public int hashCodeWords;
    private static final boolean verbose = false;

    static {
        S32xUtil.assertPowerOf2Minus1("OPT_THRESHOLD2", OPT_THRESHOLD2 + 1);
        sh2Config = Sh2.Sh2Config.get();
        INVALID_BLOCK.setFlag(VALID_FLAG, false);
    }

    public Sh2Block(int pc, CpuDeviceAccess cpu) {
        prefetchPc = pc;
        blockFlags = (cpu.ordinal() | VALID_FLAG);
    }

    public final void runBlock(Sh2 sh2, Sh2MMREG sm) {
        assert prefetchPc != -1;
        assert (blockFlags & VALID_FLAG) > 0;
        if (stage2Drc != null) {
            stage2Drc.run();
            if (sh2Config.pollDetectEn) {
                handlePoll();
            }
            return;
        }
        runInterpreter(sh2, sm, drcContext.sh2Ctx);
    }

    protected final void runInterpreter(Sh2 sh2, Sh2MMREG sm, Sh2Context ctx) {
        Sh2Prefetcher.Sh2BlockUnit prev = curr;
        addHit();
        int startCycle = ctx.cycles;
        do {
            sh2.printDebugMaybe(curr.opcode);
            curr.runnable.run();
            sm.deviceStep();
            if (curr.inst.isBranchDelaySlot || curr.next == null) {
                break;
            }
            curr = curr.next;
        } while (true);
        cyclesConsumed = (startCycle - ctx.cycles) + Md32xRuntimeData.getCpuDelayExt();
        curr = prev;
    }

    private static final int POLLER_ACTIVATE_LIMIT = 2;

    protected final void handlePoll() {
        final CpuDeviceAccess cpu = getCpu();
        if (!isPollingBlock()) {
            final Ow2DrcOptimizer.PollerCtx current = SysEventManager.instance.getPoller(cpu);
            assert current != UNKNOWN_POLLER;
            if (current != NO_POLLER && pollType == BUSY_LOOP) { //TODO check
                SysEventManager.instance.resetPoller(current.cpu);
            }
            return;
        }
        final Ow2DrcOptimizer.PollerCtx currentPoller = SysEventManager.instance.getPoller(cpu);
        final Ow2DrcOptimizer.PollerCtx blockPoller = poller;
        assert poller == Sh2Helper.get(prefetchPc, cpu).block.poller;
        if (currentPoller == NO_POLLER) {
            assert blockPoller != UNKNOWN_POLLER;
            if (blockPoller != NO_POLLER) {
                SysEventManager.instance.setPoller(cpu, blockPoller);
            } else {
                //DMA and PWM are not supported -> poller = NO_POLLER
                assert pollType == DMA || pollType == PWM : this + "\n" + blockPoller;
                if (verbose)
                    LOG.info("{} ignoring {} poll at PC {}, on address: {}", cpu, pollType,
                            th(this.prefetchPc), th(blockPoller.blockPollData.memLoadTarget));
                pollType = PollType.NONE;
            }
        } else if (!currentPoller.isPollingActive()) {
            if (blockPoller != currentPoller) {
                SysEventManager.instance.resetPoller(cpu);
                return;
            }
            startPollingMaybe(blockPoller);
        } else if (currentPoller.isPollingActive()) {
            if (verbose) LOG.info("Polling active: {}", currentPoller);
            assert blockPoller == currentPoller;
        } else {
            throw new RuntimeException("Unexpected, poller: " + currentPoller);
        }
    }

    private void startPollingMaybe(Ow2DrcOptimizer.PollerCtx blockPoller) {
        if (blockPoller.spinCount < POLLER_ACTIVATE_LIMIT) {
            if (verbose)
                LOG.info("{} avoid re-entering {} poll at PC {}, on address: {}", blockPoller.cpu, pollType,
                        th(this.prefetchPc), th(blockPoller.blockPollData.memLoadTarget));
            return;
        }
        blockPoller.pollState = Ow2DrcOptimizer.PollState.ACTIVE_POLL;
        Ow2DrcOptimizer.parseMemLoad(blockPoller.blockPollData);
        if (verbose)
            LOG.info("{} entering {} poll at PC {}, on address: {}", blockPoller.cpu, pollType,
                    th(this.prefetchPc), th(blockPoller.blockPollData.memLoadTarget));
        SysEventManager.instance.fireSysEvent(blockPoller.cpu, SysEvent.START_POLLING);
    }

    public void addHit() {
        hits++;
        if (stage2Drc == null && ((hits + 1) & OPT_THRESHOLD2) == 0) {
            assert inst != null;
            if (verbose) LOG.info("{} HRC2 count: {}\n{}", "", th(hits), Sh2Helper.toListOfInst(this));
            stage2();
            if (sh2Config.pollDetectEn) {
                Ow2DrcOptimizer.pollDetector(this);
            }
        }
    }
    public void stage1(Sh2Prefetcher.Sh2BlockUnit[] ic) {
        assert inst == null;
        inst = ic;
        inst[0].next = inst.length > 1 ? inst[1] : null;
        inst[0].pc = prefetchPc;
        int lastIdx = ic.length - 1;
        for (int i = 1; i < inst.length - 1; i++) {
            inst[i].next = inst[i + 1];
            inst[i].pc = prefetchPc + (i << 1);
            assert !inst[i].inst.isBranch || inst[i].inst.isBranchDelaySlot;
        }
        Sh2Prefetcher.Sh2BlockUnit sbu = inst[lastIdx];
        sbu.pc = prefetchPc + (lastIdx << 1);
        curr = inst[0];
        assert sbu.pc != 0;
        assert inst.length == MAX_INST_LEN ||
                (sbu.inst.isBranch || (inst[lastIdx - 1].inst.isBranchDelaySlot && !sbu.inst.isBranch)) :
                th(sbu.pc) + "," + inst.length;
    }

    public void stage2() {
        if (sh2Config.drcEn) {
            assert drcContext != null;
            stage2Drc = Ow2Sh2BlockRecompiler.getInstance().createDrcClass(this, drcContext);
        }
    }

    public boolean isPollingBlock() {
        return pollType.ordinal() > NONE.ordinal();
    }

    public void setCacheFetch(boolean val) {
        setFlag(CACHE_FETCH_FLAG, val);
    }

    public void setNoJump(boolean val) {
        setFlag(NO_JUMP_FLAG, val);
    }

    public boolean isValid() {
        return (blockFlags & VALID_FLAG) > 0;
    }

    private void setFlag(int flag, boolean val) {
        blockFlags &= ~flag;
        blockFlags |= val ? flag : 0;
    }

    public boolean isNoJump() {
        return (blockFlags & NO_JUMP_FLAG) > 0;
    }

    public boolean isCacheFetch() {
        return (blockFlags & CACHE_FETCH_FLAG) > 0;
    }

    public void invalidate() {
        blockFlags &= ~VALID_FLAG;
        prefetchPc |= 1;
    }

    public void setValid() {
        blockFlags |= VALID_FLAG;
        prefetchPc &= ~1;
    }

    public CpuDeviceAccess getCpu() {
        return CpuDeviceAccess.cdaValues[blockFlags & CPU_FLAG];
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Sh2Block.class.getSimpleName() + "[", "]")
                .add("inst=" + Arrays.toString(inst))
                .add("curr=" + curr)
                .add("prefetchWords=" + Arrays.toString(prefetchWords))
                .add("prefetchPc=" + th(prefetchPc))
                .add("hits=" + hits)
                .add("start=" + th(start))
                .add("end=" + th(end))
                .add("pcMasked=" + th(pcMasked))
                .add("prefetchLenWords=" + prefetchLenWords)
                .add("fetchMemAccessDelay=" + fetchMemAccessDelay)
                .add("cyclesConsumed=" + cyclesConsumed)
                .add("fetchBuffer=" + fetchBuffer)
                .add("drcContext=" + drcContext)
                .add("blockFlags=" + blockFlags)
                .add("pollType=" + pollType)
                .add("stage2Drc=" + stage2Drc)
                .toString();
    }
}