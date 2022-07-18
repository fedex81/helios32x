package sh2;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.Sh2;
import sh2.sh2.cache.Sh2Cache.CacheInvalidateContext;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static omegadrive.util.Util.th;
import static sh2.sh2.Sh2Debug.PC_AREA_SHIFT;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory extends Sh2Prefetcher {

    boolean SH2_MEM_ACCESS_STATS = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.memAccess.stats", "false"));

    void write(int register, int value, Size size);

    int read(int register, Size size);

    void resetSh2();

    default void fetch(Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
        ft.opcode = read16(ft.pc);
    }

    default int fetchDelaySlot(int pc, Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
        return read16(pc);
    }

    default void write8(int addr, byte val) {
        write(addr, val, Size.BYTE);
    }

    default void write16(int addr, int val) {
        write(addr, val, Size.WORD);
    }

    default void write32(int addr, int val) {
        write(addr, val, Size.LONG);
    }

    default int read8(int addr) {
        return read(addr, Size.BYTE);
    }

    default int read16(int addr) {
        return read(addr, Size.WORD);
    }

    default int read32(int addr) {
        return read(addr, Size.LONG);
    }

    default void invalidateCachePrefetch(CacheInvalidateContext ctx) {
        //do nothing
    }

    default void invalidateAllPrefetch(S32xUtil.CpuDeviceAccess cpuDeviceAccess) {
        //do nothing
    }

    default List<Sh2Block> getPrefetchBlocksAt(S32xUtil.CpuDeviceAccess cpu, int address) {
        return Collections.emptyList();
    }

    class MemAccessStats {

        private static final Logger LOG = LogManager.getLogger(MemAccessStats.class.getSimpleName());

        static final MemAccessStats NO_STATS = new MemAccessStats();
        //printCnt needs to be a multiple of 2
        static final long printCnt = 0x1FF_FFFF + 1, printCntMask = printCnt - 1;

        long[][] readHits = SH2_MEM_ACCESS_STATS ? new long[3][0x100] : null;
        long[][] writeHits = SH2_MEM_ACCESS_STATS ? new long[3][0x100] : null;

        long cnt = 0, readCnt;

        static final Function<Map, String> mapToStr = m -> (String) m.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).
                map(Objects::toString).collect(Collectors.joining("\n"));

        public void addMemHit(boolean read, int address, Size size) {
            long[][] ref = read ? readHits : writeHits;
            int idx = size.ordinal();
            ref[size.ordinal()][(address >>> PC_AREA_SHIFT) & 0xFF]++;
            readCnt += read ? 1 : 0;
            if ((++cnt & printCntMask) == 0) {
                Map<String, Double> rm = new HashMap<>(), wm = new HashMap<>();
                long writeCnt = cnt - readCnt;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < ref[i].length; j++) {
                        if (readHits[i][j] > 0) {
                            addStatString("read, ", readHits[i][j], i, j, rm);
                        }
                        if (writeHits[i][j] > 0) {
                            addStatString("write, ", writeHits[i][j], i, j, wm);
                        }
                    }
                }
                LOG.info(th(cnt) + "\n" + mapToStr.apply(rm));
                LOG.info(th(cnt) + "\n" + mapToStr.apply(wm));
            }
        }

        private void addStatString(String head, long cnt, int i, int j, Map<String, Double> map) {
            if (cnt > 0) {
                String s = head + Size.values()[i] + "," + th(j) + "," + cnt;
                double readPerc = 100d * cnt / readCnt;
                double totPerc = 100d * cnt / cnt;
                map.put(s, readPerc);
            }
        }
    }
}
