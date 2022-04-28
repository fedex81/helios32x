package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2MMREG;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Impl;
import sh2.sh2.Sh2Instructions.Sh2Instruction;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2Prefetcher {

    static final Logger LOG = LogManager.getLogger(Sh2Prefetcher.class.getSimpleName());

    void fetch(FetchResult ft, CpuDeviceAccess cpu);

    int fetchDelaySlot(int pc, FetchResult ft, CpuDeviceAccess cpu);

    default void dataWrite(CpuDeviceAccess cpu, int addr, int val, Size size) {
    }

    public static class Sh2BlockUnit extends Sh2Instruction {
        public Sh2BlockUnit next;
        public int pc;

        public Sh2BlockUnit(Sh2Instruction i) {
            super(i.opcode, i.inst, i.runnable);
        }
    }

    public static class Sh2Block {

        public static final Sh2Block INVALID_BLOCK = new Sh2Block();

        public Sh2BlockUnit[] inst;
        public Sh2BlockUnit curr;
        public int[] prefetchWords;
        public int prefetchPc, hits, start, end, pcMasked, prefetchLenWords, memAccessDelay;
        public ByteBuffer fetchBuffer;
        public Sh2Block nextBlock = INVALID_BLOCK;

        public boolean runOne() {
            curr.runnable.run();
            curr = curr.next;
            return curr != null;
        }

        public final void runBlock(Sh2Impl sh2, Sh2MMREG sm) {
            Sh2BlockUnit prev = curr;
            do {
                sh2.printDebugMaybe(curr.opcode);
                curr.runnable.run();
                sm.deviceStep();
                if (curr.isBranchDelaySlot || curr.next == null) {
                    break;
                }
                curr = curr.next;
            } while (true);
            curr = prev;
        }

        public void init(Sh2Prefetcher.Sh2BlockUnit[] ic) {
            inst = ic;
            inst[0].next = inst.length > 1 ? inst[1] : null;
            inst[0].pc = prefetchPc;
            for (int i = 1; i < inst.length - 1; i++) {
                inst[i].next = inst[i + 1];
                inst[i].pc = prefetchPc + (i << 1);
                assert !inst[i].isBranch || inst[i].isBranchDelaySlot;
            }
            curr = inst[0];
            assert inst[ic.length - 1].isBranch || (inst[ic.length - 2].isBranchDelaySlot && !inst[ic.length - 1].isBranch);
        }

        public void setCurrent(int pcDeltaWords) {
            curr = inst[pcDeltaWords];
        }

        public void invalidate() {
            nextBlock = INVALID_BLOCK;
            inst = null;
            prefetchWords = null;
            prefetchPc = prefetchLenWords = 0;
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
            if ((++pfMiss & 0xFFFF) == 0) {
                LOG.info(toString());
            }
        }

        public void addDelaySlotMiss() {
            addMiss();
            ++pfDsMiss;
        }
    }
}