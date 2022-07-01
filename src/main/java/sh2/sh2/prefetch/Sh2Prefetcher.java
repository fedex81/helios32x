package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2MMREG;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Impl;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.drc.Ow2Sh2BlockRecompiler;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.sh2.Sh2Instructions.generateInst;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2Prefetcher {

    static final Logger LOG = LogManager.getLogger(Sh2Prefetcher.class.getSimpleName());

    static final int OPT_THRESHOLD = 0xFF; //needs to be (powerOf2 - 1)
    static final int OPT_THRESHOLD2 = 0x1FF; //needs to be (powerOf2 - 1)

    static final boolean verbose = false;

    void fetch(FetchResult ft, CpuDeviceAccess cpu);

    int fetchDelaySlot(int pc, FetchResult ft, CpuDeviceAccess cpu);

    default void dataWrite(CpuDeviceAccess cpu, int addr, int val, Size size) {
    }

    public static class Sh2BlockUnit extends Sh2InstructionWrapper {
        public Sh2BlockUnit next;
        public int pc;

        public Sh2BlockUnit(Sh2InstructionWrapper i) {
            super(i.opcode, i.inst, i.runnable);
        }
    }

    public static class Sh2Block {

        public static final Sh2Block INVALID_BLOCK = new Sh2Block();
        private static final int MAX_INST_LEN = (Sh2Prefetch.SH2_DRC_MAX_BLOCK_LEN >> 1) + 1;

        public Sh2BlockUnit[] inst;
        public Sh2BlockUnit curr;
        public int[] prefetchWords;
        public int prefetchPc, hits, start, end, pcMasked, prefetchLenWords, memAccessDelay;
        public ByteBuffer fetchBuffer;
        public Sh2Block nextBlock = INVALID_BLOCK;
        public Sh2Prefetch.Sh2DrcContext drcContext;
        public boolean isCacheFetch;
        public Runnable stage2Drc;

        public boolean runOne() {
            curr.runnable.run();
            curr = curr.next;
            return curr != null;
        }

        public final void runBlock(Sh2Impl sh2, Sh2MMREG sm) {
            if (stage2Drc != null) {
                stage2Drc.run();
                return;
            }
            Sh2BlockUnit prev = curr;
            addHit();
            do {
                sh2.printDebugMaybe(curr.opcode);
                curr.runnable.run();
                sm.deviceStep();
                if (curr.inst.isBranchDelaySlot || curr.next == null) {
                    break;
                }
                curr = curr.next;
            } while (true);
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
                    (sbu.inst.isBranch || (inst[lastIdx - 1].inst.isBranchDelaySlot && !sbu.inst.isBranch)) : inst.length;
        }

        public void stage2() {
            if (Sh2Impl.SH2_ENABLE_DRC) {
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