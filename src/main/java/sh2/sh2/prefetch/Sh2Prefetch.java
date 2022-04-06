package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;
import static sh2.Sh2Memory.SDRAM_MASK;
import static sh2.Sh2Memory.SDRAM_SIZE;
import static sh2.dict.S32xMemAccessDelay.SDRAM;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Prefetch {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
    public static final int DEFAULT_PREFETCH_LOOKAHEAD = 8;

    public static class PrefetchContext {
        public final int prefetchLookahead;
        public final int[] prefetchWords;

        public int start, end, prefetchPc, pcMasked, hits;
        public int memAccessDelay;
        public ByteBuffer buf;

        public PrefetchContext() {
            this(DEFAULT_PREFETCH_LOOKAHEAD);
        }

        public PrefetchContext(int lookahead) {
            prefetchLookahead = lookahead;
            prefetchWords = new int[lookahead << 1];
        }

        @Override
        public String toString() {
            return "PrefetchContext{" +
                    "prefetchLookahead=" + th(prefetchLookahead) +
                    ", start=" + th(start) +
                    ", end=" + th(end) +
                    ", prefetchPc=" + th(prefetchPc) +
                    ", pcMasked=" + th(pcMasked) +
                    '}';
        }

        public String toStringVerbose() {
            return this + "\n" + Arrays.toString(prefetchWords);
        }
    }

    Map<Integer, PrefetchContext> mPrefetch = new HashMap<>();
    Map<Integer, PrefetchContext> sPrefetch = new HashMap<>();

    private IMemory memory;
    private Sh2Cache[] cache;

    public final int romSize, romMask;
    public final ByteBuffer[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

    public final PrefetchContext[] prefetchContexts = {new PrefetchContext(), new PrefetchContext()};

    public Sh2Prefetch(Sh2Memory memory, Sh2Cache[] cache) {
        this.cache = cache;
        this.memory = memory;
        romMask = memory.romMask;
        romSize = memory.romSize;
        sdram = memory.sdram;
        rom = memory.rom;
        bios = memory.bios;
    }

    public PrefetchContext prefetchCreate(int pc, S32xUtil.CpuDeviceAccess cpu) {
        PrefetchContext p = new PrefetchContext();
        doPrefetch(p, pc, cpu);
        return p;
    }

    public void doPrefetch(final PrefetchContext pctx, int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        pctx.start = (pc & 0xFF_FFFF) + (-pctx.prefetchLookahead << 1);
        pctx.end = (pc & 0xFF_FFFF) + (pctx.prefetchLookahead << 1);
        switch (pc >> 24) {
            case 6:
            case 0x26:
                pctx.start = Math.max(0, pctx.start) & SDRAM_MASK;
                pctx.end = Math.min(SDRAM_SIZE - 1, pctx.end) & SDRAM_MASK;
                pctx.pcMasked = pc & SDRAM_MASK;
                pctx.memAccessDelay = SDRAM;
                pctx.buf = sdram;
                break;
            case 2:
            case 0x22:
                pctx.start = Math.max(0, pctx.start) & romMask;
                pctx.end = Math.min(romSize - 1, pctx.end) & romMask;
                pctx.pcMasked = pc & romMask;
                pctx.memAccessDelay = S32xMemAccessDelay.ROM;
                pctx.buf = rom;
                break;
            case 0:
            case 0x20:
                pctx.buf = bios[cpu.ordinal()];
                pctx.start = Math.max(0, pctx.start);
                int biosMask = pctx.buf.capacity() - 1;
                pctx.end = pctx.end & biosMask;
                pctx.pcMasked = pc;
                pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> 28) == 0xC) {
                    int twoWay = cache[cpu.ordinal()].getCacheContext().twoWay;
                    final int mask = Sh2Cache.DATA_ARRAY_MASK >> twoWay;
                    pctx.start = Math.max(0, pctx.start) & mask;
                    pctx.memAccessDelay = S32xMemAccessDelay.SYS_REG;
                    pctx.buf = cache[cpu.ordinal()].getDataArray();
                    pctx.pcMasked = pc & mask;
                } else {
                    LOG.error("{} Unhandled prefetch: {}", cpu, th(pc));
                    throw new RuntimeException("Unhandled prefetch: " + th(pc));
                }
                break;
        }
        pctx.prefetchPc = pc;
        for (int bytePos = pctx.start; bytePos < pctx.end; bytePos += 2) {
            int w = ((bytePos - pctx.pcMasked) >> 1) + pctx.prefetchLookahead;
            pctx.prefetchWords[w] = pctx.buf.getShort(bytePos) & 0xFFFF;
        }
    }

    public void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
        doPrefetch(prefetchContexts[cpu.ordinal()], pc, cpu);
        assert prefetchContexts[cpu.ordinal()] != null;
    }

    public int fetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) {
            return memory.read(pc, Size.WORD);
        }
        PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
        if (Math.abs(pcDeltaWords) >= pctx.prefetchLookahead) {
            prefetch(pc, cpu);
            pcDeltaWords = 0;
            pctx = prefetchContexts[cpu.ordinal()];
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        }
//		pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
        return pctx.prefetchWords[pctx.prefetchLookahead + pcDeltaWords];
    }


    public int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) {
            return memory.read(pc, Size.WORD);
        }
        final PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
//		pfTotal++;
        int res;
        if (Math.abs(pcDeltaWords) < pctx.prefetchLookahead) {
            S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
            res = pctx.prefetchWords[pctx.prefetchLookahead + pcDeltaWords];
        } else {
            res = memory.read(pc, Size.WORD);
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        }
        return res;
    }

    private void checkPrefetch(int writeAddr, int val, Size size) {
        writeAddr &= 0xFFF_FFFF; //drop cached vs uncached
        for (int i = 0; i < 2; i++) {
            if (cache[i].getCacheContext().cacheEn == 0) {
                continue;
            }
            int start = Math.max(0, prefetchContexts[i].prefetchPc - (prefetchContexts[i].prefetchLookahead << 1));
            int end = prefetchContexts[i].prefetchPc + (prefetchContexts[i].prefetchLookahead << 1);
            if (writeAddr >= start && writeAddr <= end) {
                S32xUtil.CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
//				LOG.warn("{} write, addr: {} val: {} {}, {} PF window: [{},{}]", cpuAccess,
//						th(writeAddr), th(val), size, CpuDeviceAccess.cdaValues[i], th(start), th(end));
//				prefetch(prefetchContexts[i].prefetchPc, CpuDeviceAccess.cdaValues[i]);
            }
        }
    }
}
