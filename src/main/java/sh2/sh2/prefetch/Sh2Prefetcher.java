package sh2.sh2.prefetch;

import sh2.S32xUtil;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Instructions;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static omegadrive.util.Util.th;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2Prefetcher {

    void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu);

    void fetch(Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu);

    int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu);

    public static class PrefetchContext {
        public int[] prefetchWords;
        public Sh2Instructions.Sh2Instruction[] inst;

        //{start,end} is the {first,last} address that has been fetched
        public int start, end, prefetchPc, pcMasked, hits, prefetchLenWords;
        public int memAccessDelay;
        public ByteBuffer buf;


        @Override
        public String toString() {
            return "PrefetchContext{" +
                    "start=" + th(start) +
                    ", end=" + th(end) +
                    ", prefetchPc=" + th(prefetchPc) +
                    ", pcMasked=" + th(pcMasked) +
                    ", prefetchLenWords=" + prefetchLenWords +
                    '}';
        }

        public String toStringVerbose() {
            return this + "\n" + Arrays.toString(prefetchWords);
        }
    }
}
