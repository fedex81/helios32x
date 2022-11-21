package sh2.sh2.prefetch;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.BiosHolder;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2BaseInstruction;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.drc.Sh2Block;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xMemAccessDelay.SDRAM;

/**
 * Prefetcher in interpreter mode (no drc)
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2PrefetchSimple implements Sh2Prefetcher {

    private static final Logger LOG = LogHelper.getLogger(Sh2PrefetchSimple.class.getSimpleName());

    private static final boolean SH2_PREFETCH_DEBUG = false;

    public static final int DEFAULT_PREFETCH_LOOKAHEAD = 0x16;
    public static final int PC_CACHE_AREA_SHIFT = 28;

    private static final boolean verbose = false;

    public static class PrefetchContext {
        public final int[] prefetchWords;

        public int start, end, prefetchPc, pcMasked, hits, pfMaxIndex, memAccessDelay;
        public boolean dirty;
        public ByteBuffer buf;

        public PrefetchContext() {
            this(DEFAULT_PREFETCH_LOOKAHEAD);
        }

        public PrefetchContext(int lookahead) {
            prefetchWords = new int[lookahead];
        }

        @Override
        public String toString() {
            return "PrefetchContext{" +
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

    private final IMemory memory;
    private final Sh2Cache[] cache;
    private final Sh2.Sh2Config sh2Config;
    public final int romSize, romMask;
    public final BiosHolder.BiosData[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

    public final PrefetchContext[] prefetchContexts = {new PrefetchContext(), new PrefetchContext()};

    public Sh2PrefetchSimple(Sh2Memory memory, Sh2Cache[] cache) {
        this.cache = cache;
        this.memory = memory;
        romMask = memory.romMask;
        romSize = memory.romSize;
        sdram = memory.sdram;
        rom = memory.rom;
        bios = memory.bios;
        sh2Config = Sh2.Sh2Config.get();
    }
    public void doPrefetch(final PrefetchContext pctx, int pc, CpuDeviceAccess cpu) {
        if (!sh2Config.prefetchEn) return;
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        pctx.start = (pc & 0xFF_FFFF);
        pctx.end = (pc & 0xFF_FFFF) + (DEFAULT_PREFETCH_LOOKAHEAD << 1);

        switch (pc >> SH2_PC_AREA_SHIFT) {
            case 6:
            case 0x26:
                pctx.start = Math.max(0, pctx.start) & SH2_SDRAM_MASK;
                pctx.end = Math.min(SH2_SDRAM_SIZE - 1, pctx.end) & SH2_SDRAM_MASK;
                pctx.pcMasked = pc & SH2_SDRAM_MASK;
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
                final BiosHolder.BiosData bd = bios[cpu.ordinal()];
                pctx.buf = bd.buffer;
                pctx.start = Math.max(0, pctx.start);
                pctx.end = pctx.end & bd.padMask;
                pctx.pcMasked = pc;
                pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> PC_CACHE_AREA_SHIFT) == 0xC) {
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
        boolean outNext = false;
        int cpc = (pc & 0xFFF_FFFF) - (pctx.pcMasked - pctx.start);
        for (int bytePos = pctx.start; bytePos < pctx.end; bytePos += 2, cpc += 2) {
            int w = ((bytePos - pctx.pcMasked) >> 1);
            int opc = isCache ? sh2Cache.readDirect(cpc, Size.WORD) : pctx.buf.getShort(bytePos) & 0xFFFF;
            pctx.prefetchWords[w] = opc;
            Sh2BaseInstruction instType = Sh2Instructions.sh2OpcodeMap[opc];
            if (instType.isBranchDelaySlot) {
                outNext = true;
            } else if (instType.isBranch || outNext) {
                pctx.end = bytePos + 2;
                break;
            }
        }
        //force a cache effect by fetching the current PC
        if (isCache) {
            sh2Cache.cacheMemoryRead(pc, Size.WORD);
        }
        pctx.pfMaxIndex = ((pctx.end - 2) - pctx.start) >> 1;
        pctx.dirty = false;
    }

    public void prefetch(int pc, CpuDeviceAccess cpu) {
        doPrefetch(prefetchContexts[cpu.ordinal()], pc, cpu);
        assert prefetchContexts[cpu.ordinal()] != null;
    }

    public void fetch(Sh2.FetchResult ft, CpuDeviceAccess cpu) {
        ft.opcode = fetch(ft.pc, cpu);
    }

    public int fetch(int pc, CpuDeviceAccess cpu) {
        assert cpu == Md32xRuntimeData.getAccessTypeExt();
        if (!sh2Config.prefetchEn) {
            return memory.read(pc, Size.WORD);
        }
        PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
        if (pctx.dirty || pcDeltaWords < 0 || pcDeltaWords > pctx.pfMaxIndex) {
            prefetch(pc, cpu);
            pcDeltaWords = 0;
            pctx = prefetchContexts[cpu.ordinal()];
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        } else {
            boolean isCache = pc >>> PC_CACHE_AREA_SHIFT == 0;
            if (isCache && cache[cpu.ordinal()].getCacheContext().cacheEn > 0) {
                //NOTE necessary to trigger the cache effect on fetch
                int cached = cache[cpu.ordinal()].cacheMemoryRead(pc, Size.WORD);
                assert cached == pctx.prefetchWords[pcDeltaWords];
            }
        }
        //TODO sh2 has 2 words prefecth, this should always be 0 ??
        //TODO vr broken
//		pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
        int pres = pctx.prefetchWords[pcDeltaWords];
        if (SH2_PREFETCH_DEBUG) {
            int res = memory.read16(pc);
            if (res != pres) {
                LOG.info("{} pc {}, pfOpcode: {}, memOpcode: {}", cpu, th(pc), th(pres), th(res));
            }
        }
        return pres;
    }


    public int fetchDelaySlot(int pc, Sh2.FetchResult ft, CpuDeviceAccess cpu) {
        if (!sh2Config.prefetchEn) {
            assert cpu == Md32xRuntimeData.getAccessTypeExt();
            return memory.read(pc, Size.WORD);
        }
        final PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
//		pfTotal++;
        int res;
        if (!pctx.dirty && pcDeltaWords >= 0 && pcDeltaWords <= pctx.pfMaxIndex) {
            S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
            res = pctx.prefetchWords[pcDeltaWords];
        } else {
            res = memory.read(pc, Size.WORD);
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        }
        if (SH2_PREFETCH_DEBUG) {
            int mres = memory.read16(pc);
            if (res != mres) {
                LOG.info("{} pc {}, pfOpcode: {}, memOpcode: {}", cpu, th(pc), th(res), th(mres));
            }
        }
        return res;
    }

    public void dataWrite(CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        if (!sh2Config.prefetchEn) {
            return;
        }
        boolean isCacheArray = addr >>> SH2_PC_AREA_SHIFT == 0xC0;
        boolean isWriteThrough = addr >>> SH2_PC_AREA_SHIFT == 0x20;

        for (int i = 0; i <= SLAVE.ordinal(); i++) {
            //sh2 cacheArrays are not shared!
            if (isCacheArray && i != cpuWrite.ordinal()) {
                continue;
            }
            checkPrefetch(cpuWrite, CpuDeviceAccess.cdaValues[i], addr, val, size);
            boolean isCacheEnabled = cache[i].getCacheContext().cacheEn > 0;
            if (!isCacheEnabled) {
                int otherAddr = isWriteThrough ? addr & 0xFFF_FFFF : addr | SH2_CACHE_THROUGH_OFFSET;
                checkPrefetch(cpuWrite, CpuDeviceAccess.cdaValues[i], otherAddr, val, size);
            }
        }
    }

    @Override
    public List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int address) {
        if (prefetchContexts[cpu.ordinal()].prefetchPc == address) {
            Sh2Block block = new Sh2Block(address, cpu);
            block.start = address;
            block.end = prefetchContexts[cpu.ordinal()].end;
            block.prefetchWords = prefetchContexts[cpu.ordinal()].prefetchWords;
            return List.of(block);
        }
        return Collections.emptyList();
    }

    @Override
    public void invalidateAllPrefetch(CpuDeviceAccess cpu) {
        prefetchContexts[cpu.ordinal()].dirty = true;
    }

    private void checkPrefetch(CpuDeviceAccess cpuWrite, CpuDeviceAccess cpu, int writeAddr, int val, Size size) {
        final PrefetchContext p = prefetchContexts[cpu.ordinal()];
        int start = p.prefetchPc;
        int end = start + (p.pfMaxIndex << 1);
        if (writeAddr >= start && writeAddr <= end) {
            if (verbose) {
                String s = LogHelper.formatMessage("{} write at addr: {} val: {} {}, " +
                                "{} reload PF at pc {}, window: [{},{}]",
                        cpuWrite, th(writeAddr), th(val), size, cpu, th(p.prefetchPc), th(start), th(end));
                LOG.info(s);
//                System.out.println(s);
            }
            p.dirty = true;
        }
    }

    public void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx) {
        final PrefetchContext p = prefetchContexts[ctx.cpu.ordinal()];
        if (p.dirty) {
            return;
        }
        boolean isCacheAddress = ctx.force || ctx.cacheReadAddr >>> PC_CACHE_AREA_SHIFT == 0;
        assert isCacheAddress : ctx.cpu + "," + th(ctx.cacheReadAddr);
        int start = p.prefetchPc;
        int end = start + (p.pfMaxIndex << 1);
        int lineStart = ctx.prevCacheAddr;
        int lineEnd = lineStart + Sh2Cache.CACHE_BYTES_PER_LINE;
        if (ctx.force || lineEnd >= start && lineStart <= end) {
            if (verbose) {
                String s = LogHelper.formatMessage("{} invalidateCachePrefetch forced={} from addr: {}, cacheLine: [{},{}]" +
                                ", invalidate PF at pc {}, window: [{},{}]",
                        ctx.cpu, ctx.force, th(ctx.cacheReadAddr), th(lineStart), th(lineEnd), th(p.prefetchPc),
                        th(start), th(end));
//                System.out.println(s);
                LOG.info(s);
            }
            p.dirty = true;
        }
    }
}

