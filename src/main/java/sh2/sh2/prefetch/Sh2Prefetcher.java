package sh2.sh2.prefetch;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2MMREG;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Impl;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.sh2.drc.Ow2Sh2BlockRecompiler;

import java.nio.ByteBuffer;
import java.util.List;

import static omegadrive.util.Util.th;
import static sh2.Md32x.CYCLE_TABLE_LEN_MASK;
import static sh2.Md32x.SH2_ENABLE_DRC;
import static sh2.sh2.Sh2Instructions.generateInst;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2Prefetcher {

    static final Logger LOG = LogHelper.getLogger(Sh2Prefetcher.class.getSimpleName());

    static final int OPT_THRESHOLD = 0xFF; //needs to be (powerOf2 - 1)
    static final int OPT_THRESHOLD2 = 0x1FF; //needs to be (powerOf2 - 1)

    static final boolean verbose = false;

    void fetch(FetchResult ft, CpuDeviceAccess cpu);

    int fetchDelaySlot(int pc, FetchResult ft, CpuDeviceAccess cpu);

    default void dataWrite(CpuDeviceAccess cpu, int addr, int val, Size size) {
    }

    List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int address);

    void invalidateAllPrefetch(CpuDeviceAccess cpuDeviceAccess);

    void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx);

    public static class Sh2BlockUnit extends Sh2InstructionWrapper {
        public Sh2BlockUnit next;
        public int pc;

        public Sh2BlockUnit(Sh2InstructionWrapper i) {
            super(i.opcode, i.inst, i.runnable);
        }
    }

    public static class Sh2Block {

        public static final Sh2Block INVALID_BLOCK = new Sh2Block();
        public static final int MAX_INST_LEN = (Sh2Prefetch.SH2_DRC_MAX_BLOCK_LEN >> 1);

        public Sh2BlockUnit[] inst;
        public Sh2BlockUnit curr;
        public int[] prefetchWords;
        public int prefetchPc, hits, start, end, pcMasked, prefetchLenWords, fetchMemAccessDelay, cyclesConsumed;
        public ByteBuffer fetchBuffer;
        public Sh2Block nextBlock = INVALID_BLOCK;
        public Sh2Prefetch.Sh2DrcContext drcContext;
        public boolean isCacheFetch;
        public byte poller = -1;
        public Runnable stage2Drc;

        public boolean runOne() {
            curr.runnable.run();
            curr = curr.next;
            return curr != null;
        }

        public static Ow2DrcOptimizer.PollerCtx[] currentPollers = {NO_POLLER, NO_POLLER};

        public final void runBlock(Sh2Impl sh2, Sh2MMREG sm) {
            if (stage2Drc != null) {
                Ow2DrcOptimizer.PollerCtx currentPoller = currentPollers[drcContext.cpu.ordinal()];
                if (currentPoller.isPolling) {
                    //add max delay
                    this.drcContext.sh2Ctx.cycles = Sh2Context.burstCycles - CYCLE_TABLE_LEN_MASK + 5;
                    Md32xRuntimeData.resetCpuDelayExt();
                    return;
                }
                if (poller > 0 && currentPoller == NO_POLLER) {
                    Ow2DrcOptimizer.PollerCtx pctx = Ow2DrcOptimizer.map.getOrDefault(prefetchPc, NO_POLLER);
                    if (pctx != NO_POLLER) {
                        LOG.info("{} entering poller at PC: {}", this.drcContext.cpu, th(this.prefetchPc));
                        pctx.isPolling = true;
                        currentPollers[drcContext.cpu.ordinal()] = pctx;
                    }
                }
                stage2Drc.run();
                return;
            }
            Sh2BlockUnit prev = curr;
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

        public void addHit() {
            hits++;
            if (inst == null) {
                if (((hits + 1) & OPT_THRESHOLD) == 0) {
                    stage1(generateInst(prefetchWords));
                    if (verbose) LOG.info("{} HRC1 count: {}\n{}", "", th(hits), Sh2Instructions.toListOfInst(this));
                }
            } else if (stage2Drc == null && ((hits + 1) & OPT_THRESHOLD2) == 0) {
                if (verbose) LOG.info("{} HRC2 count: {}\n{}", "", th(hits), Sh2Instructions.toListOfInst(this));
                stage2();
            }
            if (((hits + 1) & 0xFFF) == 0) {
//                LOG.info("{} hits: {}, cycles: {}, tot: {}\n{}", drcContext.cpu, th(hits), cyclesConsumed,
//                        th(hits*cyclesConsumed),
//                        Sh2Instructions.toListOfInst(this));
                Ow2DrcOptimizer.pollDetector(this);
            }
        }

        public void stage1(Sh2Prefetcher.Sh2BlockUnit[] ic) {
            inst = ic;
            inst[0].next = inst.length > 1 ? inst[1] : null;
            inst[0].pc = prefetchPc;
            int lastIdx = ic.length - 1;
            for (int i = 1; i < inst.length - 1; i++) {
                inst[i].next = inst[i + 1];
                inst[i].pc = prefetchPc + (i << 1);
                assert !inst[i].inst.isBranch || inst[i].inst.isBranchDelaySlot;
            }
            Sh2BlockUnit sbu = inst[lastIdx];
            sbu.pc = prefetchPc + (lastIdx << 1);
            curr = inst[0];
            assert sbu.pc != 0;
            assert inst.length == MAX_INST_LEN ||
                    (sbu.inst.isBranch || (inst[lastIdx - 1].inst.isBranchDelaySlot && !sbu.inst.isBranch)) :
                    th(sbu.pc) + "," + inst.length;
        }

        public void stage2() {
            if (SH2_ENABLE_DRC) {
                assert drcContext != null;
                stage2Drc = Ow2Sh2BlockRecompiler.getInstance().createDrcClass(this, drcContext);
            }
        }

        public void setCurrent(int pcDeltaWords) {
            curr = inst[pcDeltaWords];
        }

        public void invalidate() {
            nextBlock = INVALID_BLOCK;
            inst = null;
            prefetchWords = null;
            prefetchPc = prefetchLenWords = -1;
            stage2Drc = null;
            hits = 0;
        }
    }

    public static class Stats {
        static String format = "%s pfTot: %d, pfMissPerc: %f, pfDsMissPerc: %f";
        public long pfMiss, pfTotal, pfDsMiss;
        private final CpuDeviceAccess cpu;

        public Stats(CpuDeviceAccess cpu) {
            this.cpu = cpu;
        }

        @Override
        public String toString() {
            return String.format(format, cpu, pfTotal, 1.0 * pfMiss / pfTotal, 1.0 * pfDsMiss / pfTotal);
        }

        public void addMiss() {
            if ((++pfMiss & 0xFF) == 0) {
                LOG.info(toString());
            }
        }

        public void addDelaySlotMiss() {
            addMiss();
            ++pfDsMiss;
        }
    }
}