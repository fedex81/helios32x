package sh2.sh2.prefetch;

import com.google.common.primitives.Ints;
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
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));

    private static final boolean SH2_REUSE_FETCH_DATA = true;
    static final boolean checkPrefetch = false;

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

    private List<Integer> opcodes = new ArrayList<>(10);

    private void doPrefetch(final PrefetchContext pctx, int pc, S32xUtil.CpuDeviceAccess cpu) {
        pctx.prefetchPc = pc;
        setupPrefetch(pctx, cpu);
        int bytePos = pctx.start;
//        LOG.info("{} prefetch @ pc: {}", cpu, th(pc));
        opcodes.clear();
        do {
            int val = pctx.buf.getShort(bytePos) & 0xFFFF;
            if (val == 0) {
                LOG.error("Invalid fetch @ PC :{}, opcode: {}", th(pc), val);
//                val = pctx.buf.getShort(bytePos) & 0xFFFF;
                break;
            }
            opcodes.add(val);
            if (Sh2Instructions.opcodeMap[val].isBranch) {
                int nextVal = pctx.buf.getShort(bytePos + 2) & 0xFFFF; //delay branch
                opcodes.add(nextVal);
                break;
            }
            bytePos += 2;
        } while (true);
        pctx.prefetchWords = Ints.toArray(opcodes);
        pctx.prefetchLenWords = pctx.prefetchWords.length;
        pctx.end = pctx.start + ((pctx.prefetchLenWords - 1) << 1);
//        LOG.info("{} prefetch @ pc: {}\n{}", cpu, th(pc), pctx);
    }

    private void setupPrefetch(final PrefetchContext pctx, S32xUtil.CpuDeviceAccess cpu) {
        final int pc = pctx.prefetchPc;
        pctx.start = pc & 0xFF_FFFF;
        switch (pc >> 24) {
            case 6:
            case 0x26:
                pctx.start = Math.max(0, pctx.start) & SDRAM_MASK;
                pctx.pcMasked = pc & SDRAM_MASK;
                pctx.memAccessDelay = SDRAM;
                pctx.buf = sdram;
                break;
            case 2:
            case 0x22:
                pctx.start = Math.max(0, pctx.start) & romMask;
                pctx.pcMasked = pc & romMask;
                pctx.memAccessDelay = S32xMemAccessDelay.ROM;
                pctx.buf = rom;
                break;
            case 0:
            case 0x20:
                pctx.buf = bios[cpu.ordinal()];
                pctx.start = Math.max(0, pctx.start);
                pctx.pcMasked = pc;
                pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> 28) == 0xC) {
                    pctx.start = Math.max(0, pctx.start) & Sh2Cache.DATA_ARRAY_MASK;
                    pctx.memAccessDelay = S32xMemAccessDelay.SYS_REG;
                    pctx.buf = cache[cpu.ordinal()].getDataArray();
                    pctx.pcMasked = pc & Sh2Cache.DATA_ARRAY_MASK;
                } else {
                    LOG.error("{} Unhandled prefetch: {}", cpu, th(pc));
                    throw new RuntimeException("Unhandled prefetch: " + th(pc));
                }
                break;
        }
        pctx.start = pctx.pcMasked;
    }

    public void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        Map<Integer, PrefetchContext> pMap = cpu == MASTER ? mPrefetch : sPrefetch;
        PrefetchContext pctxStored = pMap.get(pc);
        if (pctxStored != null) {
            pctxStored.hits++;
            if (((pctxStored.hits + 1) & 0xFFFF) == 0) {
                if (pctxStored.inst == null) {
                    pctxStored.inst = Sh2Instructions.generateInst(pctxStored.prefetchWords);
                }
//                LOG.info("{}\n{}", th(pctxStored.hits), Sh2Instructions.toListOfInst(pctxStored));
//                System.out.println(cpu + "," + pctxStored);
            }
            if (checkPrefetch) {
                doPrefetch(prefetchContexts[cpu.ordinal()], pc, cpu);
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
        if (SH2_REUSE_FETCH_DATA) {
            PrefetchContext prev = pMap.put(pc, pctx);
            if (prev != null) {
                throw new RuntimeException("PC has been rewritten: " + th(pc));
            }
        }
        assert pctx != null;
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
        boolean fetchAgain = pcDeltaWords >= pctx.prefetchLenWords || pcDeltaWords < 0;
        if (fetchAgain) {
            prefetch(pc, cpu);
            pcDeltaWords = 0;
            pctx = prefetchContexts[cpu.ordinal()];
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
        }
//		pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
//        System.out.println("Fetch PC: " + th(pc) + ", deltaW: " + pcDeltaWords);
        fetchResult.opcode = pctx.prefetchWords[pcDeltaWords];
        fetchResult.inst = pctx.inst != null ? pctx.inst[pcDeltaWords] : null;
//        fetchResult.pfCtx = pctx.inst != null ? pctx : null;
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
        boolean withinFetchWindow = pcDeltaWords < pctx.prefetchLenWords && pcDeltaWords >= 0;
        if (withinFetchWindow) {
            S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
            res = pctx.prefetchWords[pcDeltaWords];
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
            PrefetchContext pctx = prefetchContexts[i];
            if (writeAddr >= pctx.start && writeAddr <= pctx.end) {
                S32xUtil.CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
//				LOG.warn("{} write, addr: {} val: {} {}, {} PF window: [{},{}]", cpuAccess,
//						th(writeAddr), th(val), size, CpuDeviceAccess.cdaValues[i], th(start), th(end));
//				prefetch(prefetchContexts[i].prefetchPc, CpuDeviceAccess.cdaValues[i]);
            }
        }
    }
}
