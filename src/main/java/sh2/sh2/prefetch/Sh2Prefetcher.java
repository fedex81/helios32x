package sh2.sh2.prefetch;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.drc.Sh2Block;

import java.util.List;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2Prefetcher {

    Logger LOG = LogHelper.getLogger(Sh2Prefetcher.class.getSimpleName());

    boolean verbose = false;

    void fetch(FetchResult ft, CpuDeviceAccess cpu);

    int fetchDelaySlot(int pc, FetchResult ft, CpuDeviceAccess cpu);

    default void dataWrite(CpuDeviceAccess cpu, int addr, int val, Size size) {
    }

    List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int address);

    void invalidateAllPrefetch(CpuDeviceAccess cpuDeviceAccess);

    void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx);

    default void newFrame() {
    }

    public static class Sh2BlockUnit extends Sh2InstructionWrapper {
        public Sh2BlockUnit next;
        public int pc;

        public Sh2BlockUnit(Sh2InstructionWrapper i) {
            super(i.opcode, i.inst, i.runnable);
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