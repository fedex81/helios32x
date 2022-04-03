package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2Instruction;
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.Sh2Memory.SDRAM_MASK;
import static sh2.dict.S32xMemAccessDelay.SDRAM;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Prefetch {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
    private static final boolean SH2_NEW_PREFETCH = false;

    public static class PrefetchContext {
        public static final int DEFAULT_PREFETCH_LOOKAHEAD = 8;

        public final int prefetchLookahead;
        public final int[] prefetchWords;
        public Sh2Instruction[] inst;

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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PrefetchContext that = (PrefetchContext) o;

            if (prefetchLookahead != that.prefetchLookahead) return false;
            if (start != that.start) return false;
            if (end != that.end) return false;
            if (prefetchPc != that.prefetchPc) return false;
            if (pcMasked != that.pcMasked) return false;
            if (memAccessDelay != that.memAccessDelay) return false;
            if (!Arrays.equals(prefetchWords, that.prefetchWords)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = prefetchLookahead;
            result = 31 * result + Arrays.hashCode(prefetchWords);
            result = 31 * result + start;
            result = 31 * result + end;
            result = 31 * result + prefetchPc;
            result = 31 * result + pcMasked;
            result = 31 * result + memAccessDelay;
            return result;
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
        prefetchSimple(p, pc, cpu);
//        System.out.println(cpu + "," + p);
        return p;
    }

    public void prefetchSimple(final PrefetchContext pctx, int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        pctx.start = (pc & 0xFF_FFFF) + (-pctx.prefetchLookahead << 1);
        pctx.end = (pc & 0xFF_FFFF) + (pctx.prefetchLookahead << 1);
        switch (pc >> 24) {
            case 6:
            case 0x26:
                pctx.start = Math.max(0, pctx.start) & SDRAM_MASK;
                pctx.end = Math.min(romSize - 1, pctx.end) & SDRAM_MASK;
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
                pctx.end = Math.min(pctx.buf.capacity() - 1, pctx.end);
                pctx.pcMasked = pc;
                pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> 28) == 0xC) {
                    pctx.start = Math.max(0, pctx.start) & Sh2Cache.DATA_ARRAY_MASK;
                    pctx.end = Math.min(Sh2Cache.DATA_ARRAY_SIZE - 1, pctx.end) & Sh2Cache.DATA_ARRAY_MASK;
                    pctx.memAccessDelay = S32xMemAccessDelay.SYS_REG;
                    pctx.buf = cache[cpu.ordinal()].getDataArray();
                    pctx.pcMasked = pc & Sh2Cache.DATA_ARRAY_MASK;
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
        if (SH2_NEW_PREFETCH) prefetchNew(pc, cpu);
        if (!SH2_NEW_PREFETCH) prefetchSimple(prefetchContexts[cpu.ordinal()], pc, cpu);
        assert prefetchContexts[cpu.ordinal()] != null;
    }

    static final boolean checkPrefetch = false;

    public void prefetchNew(int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        Map<Integer, PrefetchContext> pMap = cpu == MASTER ? mPrefetch : sPrefetch;
        PrefetchContext pctxStored = pMap.get(pc);
        if (pctxStored != null) {
            pctxStored.hits++;
            if (((pctxStored.hits + 1) & 0xFFF) == 0 && pctxStored.inst == null) {
                pctxStored.inst = Sh2Instructions.generateInst(pctxStored.prefetchWords);
//                LOG.info("{}\n{}", th(pctxStored.hits), Sh2Instructions.toListOfInst(pctxStored));
//                System.out.println(cpu + "," + pctxStored);
            }
            if (checkPrefetch) {
                prefetchSimple(prefetchContexts[cpu.ordinal()], pc, cpu);
                if (!prefetchContexts[cpu.ordinal()].equals(pctxStored)) {
                    System.out.println("OLD: " + cpu + "," + pctxStored.toStringVerbose());
                    System.out.println("NEW: " + cpu + "," + prefetchContexts[cpu.ordinal()].toStringVerbose());
                    System.out.println();
                }
            } else {
                prefetchContexts[cpu.ordinal()] = pctxStored;
            }
            return;
        }
        PrefetchContext pctx = prefetchCreate(pc, cpu);
        prefetchContexts[cpu.ordinal()] = pctx;
        PrefetchContext prev = pMap.put(pc, pctx);
        if (prev != null) {
            System.out.println("here");
        }
        return;
    }

    public void fetch(FetchResult fetchResult, S32xUtil.CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        fetchResult.inst = null;
        if (!SH2_ENABLE_PREFETCH) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
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
        fetchResult.opcode = pctx.prefetchWords[pctx.prefetchLookahead + pcDeltaWords];
        fetchResult.inst = pctx.inst != null ? pctx.inst[pctx.prefetchLookahead + pcDeltaWords] : null;
        return;
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
