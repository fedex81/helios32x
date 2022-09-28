package sh2.sh2.drc;

import omegadrive.cpu.CpuFastDebug.PcInfoWrapper;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.*;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.event.SysEventManager;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Helper;
import sh2.sh2.drc.Ow2DrcOptimizer.PollType;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.StringJoiner;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Block {
    private static final Logger LOG = LogHelper.getLogger(Sh2Block.class.getSimpleName());
    //needs to be (powerOf2 - 1)
    private static final int OPT_THRESHOLD = Integer.parseInt(System.getProperty("helios.32x.sh2.drc.keep.hits", "63"));

    //needs to be (powerOf2 - 1)
    private static final int OPT_THRESHOLD2 = Integer.parseInt(System.getProperty("helios.32x.sh2.drc.stage2.hits", "511"));


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
    private final Sh2.Sh2Config sh2Config;

    public int blockFlags;
    public PollType pollType = PollType.UNKNOWN;
    public Runnable stage2Drc;
    public int hashCodeWords;
    private static boolean verbose = false;

    static {
        assert !INVALID_BLOCK.shouldKeep();
        S32xUtil.assertPowerOf2Minus1("OPT_THRESHOLD", OPT_THRESHOLD + 1);
        S32xUtil.assertPowerOf2Minus1("OPT_THRESHOLD2", OPT_THRESHOLD2 + 1);
    }

    public Sh2Block(int pc, CpuDeviceAccess cpu) {
        sh2Config = Sh2.Sh2Config.instance.get();
        prefetchPc = pc;
        blockFlags = (cpu.ordinal() | VALID_FLAG);
    }

    public final void runBlock(Sh2 sh2, Sh2MMREG sm) {
        assert prefetchPc != -1;
        assert (blockFlags & VALID_FLAG) > 0;
        if (stage2Drc != null) {
            if (sh2Config.pollDetectEn) {
                handlePoll();
            }
            if (!Md32x.SH2_DEBUG_DRC) {
                stage2Drc.run();
            } else {
                prepareInterpreterParallel();
                stage2Drc.run();
                runInterpreterParallel(sh2, sm);
            }
            return;
        }
        if (Md32x.SH2_DEBUG_DRC) ((Sh2MemoryParallel) drcContext.memory).setActive(false);
        runInterpreter(sh2, sm, drcContext.sh2Ctx);
    }


    private final Sh2Context[] cloneCtxs = {new Sh2Context(MASTER, false), new Sh2Context(SLAVE, false)};

    private void prepareInterpreterParallel() {
        Sh2Context ctx = this.drcContext.sh2Ctx;
        Sh2Context cloneCtx = cloneCtxs[ctx.cpuAccess.ordinal()];
        cloneCtx.PC = ctx.PC;
        cloneCtx.delayPC = ctx.delayPC;
        cloneCtx.GBR = ctx.GBR;
        cloneCtx.MACL = ctx.MACL;
        cloneCtx.SR = ctx.SR;
        cloneCtx.MACH = ctx.MACH;
        cloneCtx.VBR = ctx.VBR;
        cloneCtx.PR = ctx.PR;
        cloneCtx.opcode = ctx.opcode;
        cloneCtx.fetchResult = ctx.fetchResult;
        cloneCtx.cycles = ctx.cycles;
        cloneCtx.cycles_ran = ctx.cycles_ran;
        System.arraycopy(ctx.registers, 0, cloneCtx.registers, 0, ctx.registers.length);
        Sh2MemoryParallel sp = ((Sh2MemoryParallel) drcContext.memory);
        sp.setActive(true);
    }

    private void runInterpreterParallel(Sh2 sh2, Sh2MMREG sm) {
        int delay = Md32xRuntimeData.getCpuDelayExt();
        Sh2MemoryParallel sp = ((Sh2MemoryParallel) drcContext.memory);
        sp.setReplayMode(true);
        Sh2Context ctx = this.drcContext.sh2Ctx;
        Sh2Context intCtx = cloneCtxs[ctx.cpuAccess.ordinal()];
        sh2.setCtx(intCtx);
        boolean log = false;
        try {
            runInterpreter(sh2, sm, intCtx);
        } catch (Exception | Error e) {
            LOG.error("", e);
            log = true;
        }
        sh2.setCtx(ctx);

        if (ctx.equals(intCtx)) {
            log |= !Arrays.equals(ctx.registers, intCtx.registers);
        } else {
            log = true;
        }
        if (log) {
            LOG.info("\ndrc: {}\nint: {}\n{}", ctx, intCtx, Sh2Helper.toListOfInst(this));
            throw new RuntimeException();
        }
        sp.setReplayMode(false);
        sp.clear();
        sp.setActive(false);
        Md32xRuntimeData.resetCpuDelayExt(delay);
    }

    private void runInterpreter(Sh2 sh2, Sh2MMREG sm, Sh2Context ctx) {
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


    private void handlePoll() {
        if (!isPollingBlock()) {
            final Ow2DrcOptimizer.PollerCtx current = SysEventManager.instance.getPoller(drcContext.cpu);
            if (current != NO_POLLER && pollType == BUSY_LOOP) { //TODO check
                SysEventManager.instance.resetPoller(current.cpu);
            }
            return;
        }
        final Ow2DrcOptimizer.PollerCtx pollerCtx = SysEventManager.instance.getPoller(drcContext.cpu);
        if (pollerCtx == NO_POLLER) {
            PcInfoWrapper piw = Sh2Debug.get(prefetchPc, drcContext.cpu);
            Ow2DrcOptimizer.PollerCtx pctx = piw.poller;
            if (pctx != NO_POLLER) {
                pctx.spinCount++;
                if (pctx.spinCount < 3) {
                    if (verbose)
                        LOG.info("{} avoid re-entering {} poll at PC {}, on address: {}", this.drcContext.cpu, piw.block.pollType,
                                th(this.prefetchPc), th(pctx.blockPollData.memLoadTarget));
                    return;
                }
                SysEventManager.instance.setPoller(drcContext.cpu, pctx);
                if (verbose)
                    LOG.info("{} entering {} poll at PC {}, on address: {}", this.drcContext.cpu, piw.block.pollType,
                            th(this.prefetchPc), th(pctx.blockPollData.memLoadTarget));
                SysEventManager.instance.fireSysEvent(drcContext.cpu, SysEvent.START_POLLING);
            } else {
                if (verbose)
                    LOG.info("{} ignoring {} poll at PC {}, on address: {}", this.drcContext.cpu, piw.block.pollType,
                            th(this.prefetchPc), th(pctx.blockPollData.memLoadTarget));
                pollType = PollType.NONE;
            }
        } else if (!pollerCtx.isPollingActive()) {
            throw new RuntimeException("Unexpected, inactive poller: " + pollerCtx);
        }
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

    public boolean shouldKeep() {
        return hits > OPT_THRESHOLD || pollType != UNKNOWN;
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

    @Override
    public String toString() {
        return new StringJoiner(", ", Sh2Block.class.getSimpleName() + "[", "]")
                .add("inst=" + Arrays.toString(inst))
                .add("curr=" + curr)
                .add("prefetchWords=" + Arrays.toString(prefetchWords))
                .add("prefetchPc=" + prefetchPc)
                .add("hits=" + hits)
                .add("start=" + start)
                .add("end=" + end)
                .add("pcMasked=" + pcMasked)
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