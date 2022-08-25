package sh2.sh2.drc;

import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.Md32xRuntimeData;
import sh2.Sh2MMREG;
import sh2.event.SysEventManager;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Helper;
import sh2.sh2.Sh2Impl;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.StringJoiner;

import static omegadrive.util.Util.th;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;
import static sh2.sh2.drc.Ow2DrcOptimizer.map;

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


    public static final Sh2Block INVALID_BLOCK = new Sh2Block(-1);
    public static final int MAX_INST_LEN = (Sh2Prefetch.SH2_DRC_MAX_BLOCK_LEN >> 1);

    public Sh2Prefetcher.Sh2BlockUnit[] inst;
    public Sh2Prefetcher.Sh2BlockUnit curr;
    public int[] prefetchWords;
    public int prefetchPc, hits, start, end, pcMasked, prefetchLenWords, fetchMemAccessDelay, cyclesConsumed;
    public ByteBuffer fetchBuffer;
    public Sh2Block nextBlock = INVALID_BLOCK;
    public Sh2Prefetch.Sh2DrcContext drcContext;
    private final Sh2.Sh2Config sh2Config;
    public boolean isCacheFetch;
    public Ow2DrcOptimizer.PollType pollType = Ow2DrcOptimizer.PollType.UNKNOWN;
    public Runnable stage2Drc;

    private static boolean verbose = false;

    static {
        assert !INVALID_BLOCK.shouldKeep();
    }

    public Sh2Block(int pc) {
        sh2Config = Sh2.Sh2Config.instance.get();
        prefetchPc = pc;
    }

    public final void runBlock(Sh2Impl sh2, Sh2MMREG sm) {
        assert prefetchPc != -1;
        if (stage2Drc != null) {
            if (sh2Config.drcEn) {
                handlePoll();
            }
            stage2Drc.run();
            return;
        }
        Sh2Prefetcher.Sh2BlockUnit prev = curr;
        addHit();
        int startCycle = this.drcContext.sh2Ctx.cycles;
        do {
            sh2.printDebugMaybe(curr.opcode);
            curr.runnable.run();
            sm.deviceStep();
            if (curr.inst.isBranchDelaySlot || curr.next == null) {
                break;
            }
            curr = curr.next;
        } while (true);
        cyclesConsumed = (startCycle - this.drcContext.sh2Ctx.cycles) + Md32xRuntimeData.getCpuDelayExt();
        curr = prev;
    }


    private void handlePoll() {
        if (!isPollingBlock()) {
            final Ow2DrcOptimizer.PollerCtx current = SysEventManager.currentPollers[drcContext.cpu.ordinal()];
            if (current != NO_POLLER && pollType == BUSY_LOOP) {
                current.stopPolling();
                SysEventManager.currentPollers[drcContext.cpu.ordinal()] = NO_POLLER;
            }
            return;
        }
        final Ow2DrcOptimizer.PollerCtx pollerCtx = SysEventManager.currentPollers[drcContext.cpu.ordinal()];
        if (pollerCtx == NO_POLLER) {
            Ow2DrcOptimizer.PollerCtx pctx = Ow2DrcOptimizer.map[drcContext.cpu.ordinal()].getOrDefault(prefetchPc, NO_POLLER);
            if (pctx != NO_POLLER) {
                SysEventManager.currentPollers[drcContext.cpu.ordinal()] = pctx;
                if (verbose)
                    LOG.info("{} entering {} poll at PC {}, on address: {}", this.drcContext.cpu, pctx.block.pollType,
                            th(this.prefetchPc), th(pctx.memoryTarget));
                SysEventManager.instance.fireSysEvent(drcContext.cpu, SysEvent.START_POLLING);
            } else {
                if (verbose)
                    LOG.info("{} ignoring {} poll at PC {}, on address: {}", this.drcContext.cpu, pctx.block.pollType,
                            th(this.prefetchPc), th(pctx.memoryTarget));
                pollType = Ow2DrcOptimizer.PollType.NONE;
            }
            //TODO remove else-if ??
        } else if (pollerCtx.isPollingActive()) {
//            SysEventManager.instance.fireSysEvent(pollerCtx.cpu, START_POLLING);
        } else {
            throw new RuntimeException("unexpected");
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

    public void invalidate() {
        Ow2DrcOptimizer.PollerCtx pctx = map[drcContext.cpu.ordinal()].remove(prefetchPc);
        if (pctx != null) {
            LOG.warn("{} invalidating a polling block: {}", drcContext.cpu, pctx);
        }
        nextBlock = INVALID_BLOCK;
        inst = null;
        prefetchWords = null;
        prefetchPc = prefetchLenWords = -1;
        stage2Drc = null;
        hits = 0;
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
                .add("isCacheFetch=" + isCacheFetch)
                .add("pollType=" + pollType)
                .add("stage2Drc=" + stage2Drc)
                .toString();
    }
}