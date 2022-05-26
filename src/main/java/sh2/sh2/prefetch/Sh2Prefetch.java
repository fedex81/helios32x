package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2BaseInstruction;
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.Sh2Memory.*;
import static sh2.dict.S32xMemAccessDelay.SDRAM;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Prefetch {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
    private static final boolean SH2_PREFETCH_DEBUG = false;

    public static final int DEFAULT_PREFETCH_LOOKAHEAD = 0x16;

    public static final int PC_AREA_SHIFT = 24;
    public static final int PC_CACHE_AREA_SHIFT = 28;

    private static final boolean verbose = false;

    public static class PrefetchContext {
        public final int[] prefetchWords;

        public int start, end, prefetchPc, pcMasked, hits, pfMaxIndex;
        public int memAccessDelay;
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

    public PrefetchContext prefetchCreate(int pc, CpuDeviceAccess cpu) {
        PrefetchContext p = new PrefetchContext();
        doPrefetch(p, pc, cpu);
        return p;
    }

    public void doPrefetch(final PrefetchContext pctx, int pc, CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        pctx.start = (pc & 0xFF_FFFF);
        pctx.end = (pc & 0xFF_FFFF) + (DEFAULT_PREFETCH_LOOKAHEAD << 1);

        switch (pc >> PC_AREA_SHIFT) {
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
//                    sh2Cache.cacheMemoryRead(cpc, Size.WORD)
            pctx.prefetchWords[w] = opc;
            Sh2BaseInstruction instType = Sh2Instructions.sh2OpcodeMap[opc];
            if (instType.isBranchDelaySlot) {
                outNext = true;
            } else if (instType.isBranch || outNext) {
                pctx.end = bytePos + 2;
                break;
            }
        }
        pctx.pfMaxIndex = ((pctx.end - 2) - pctx.start) >> 1;
    }

    public void prefetch(int pc, CpuDeviceAccess cpu) {
        doPrefetch(prefetchContexts[cpu.ordinal()], pc, cpu);
        assert prefetchContexts[cpu.ordinal()] != null;
    }

    public int fetch(int pc, CpuDeviceAccess cpu) {
        assert cpu == Md32xRuntimeData.getAccessTypeExt();
        if (!SH2_ENABLE_PREFETCH) {
            return memory.read(pc, Size.WORD);
        }
        PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
        if (pcDeltaWords < 0 || pcDeltaWords > pctx.pfMaxIndex) {
            prefetch(pc, cpu);
            pcDeltaWords = 0;
            pctx = prefetchContexts[cpu.ordinal()];
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        } else {
            boolean isCache = pc >>> PC_CACHE_AREA_SHIFT == 0;
            if (isCache && cache[cpu.ordinal()].getCacheContext().cacheEn > 0) {
                //NOTE necessary to trigger the cache hit on fetch
                int cached = cache[cpu.ordinal()].cacheMemoryRead(pc, Size.WORD);
                assert cached == pctx.prefetchWords[pcDeltaWords];
            }
        }
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


    public int fetchDelaySlot(int pc, CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) {
            assert cpu == Md32xRuntimeData.getAccessTypeExt();
            return memory.read(pc, Size.WORD);
        }
        final PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
        int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
//		pfTotal++;
        int res;
        if (pcDeltaWords >= 0 && pcDeltaWords <= pctx.pfMaxIndex) {
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
        if (!SH2_ENABLE_PREFETCH) {
            return;
        }
        boolean isCacheArray = addr >>> PC_AREA_SHIFT == 0xC0;
        boolean isWriteThrough = addr >>> PC_AREA_SHIFT == 0x20;

        for (int i = 0; i <= SLAVE.ordinal(); i++) {
            //sh2 cacheArrays are not shared!
            if (isCacheArray && i != cpuWrite.ordinal()) {
                continue;
            }
            checkPrefetch(cpuWrite, CpuDeviceAccess.cdaValues[i], addr, val, size);
            boolean isCacheEnabled = cache[i].getCacheContext().cacheEn > 0;
            if (!isCacheEnabled) {
                int otherAddr = isWriteThrough ? addr & 0xFFF_FFFF : addr | CACHE_THROUGH_OFFSET;
                checkPrefetch(cpuWrite, CpuDeviceAccess.cdaValues[i], otherAddr, val, size);
            }
        }
    }

    private void checkPrefetch(CpuDeviceAccess cpuWrite, CpuDeviceAccess cpu, int writeAddr, int val, Size size) {
        final PrefetchContext p = prefetchContexts[cpu.ordinal()];
        int start = p.prefetchPc;
        int end = start + (p.pfMaxIndex << 1);
        if (writeAddr >= start && writeAddr <= end) {
            if (verbose) {
                ParameterizedMessage pm = new ParameterizedMessage("{} write at addr: {} val: {} {}, " +
                        "{} reload PF at pc {}, window: [{},{}]",
                        cpuWrite, th(writeAddr), th(val), size, cpu, th(p.prefetchPc), th(start), th(end));
                LOG.info(pm.getFormattedMessage());
//                System.out.println(pm.getFormattedMessage());
            }
            prefetch(p.prefetchPc, cpu);
        }
    }

    public void invalidateCachePrefetch(CpuDeviceAccess cpu) {
        final PrefetchContext p = prefetchContexts[cpu.ordinal()];
        boolean isCacheAddress = p.prefetchPc >>> PC_CACHE_AREA_SHIFT == 0;
        if (isCacheAddress) {
            Md32xRuntimeData.setAccessTypeExt(cpu);
            if (verbose) {
                ParameterizedMessage pm = new ParameterizedMessage(
                        "{} cache enable invalidate, reload PF at pc {}, window: [{},{}]",
                        cpu, th(p.prefetchPc), th(p.start), th(p.end));
                LOG.info(pm.getFormattedMessage());
                System.out.println(pm.getFormattedMessage());
            }
            prefetch(p.prefetchPc, cpu);
        }
    }


}
