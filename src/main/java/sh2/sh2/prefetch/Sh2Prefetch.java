package sh2.sh2.prefetch;

import com.google.common.primitives.Ints;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Helper;
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
    private static final int OPT_THRESHOLD = 0xFF; //needs to be (powerOf2 - 1)
    static final boolean checkPrefetch = false;

    Map<Integer, PrefetchContext> mPrefetch = new HashMap<>();
    Map<Integer, PrefetchContext> sPrefetch = new HashMap<>();

    private final IMemory memory;
    private final Sh2Cache[] cache;

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

    private final List<Integer> opcodes = new ArrayList<>(10);

    private void doPrefetch(final PrefetchContext pctx, int pc, CpuDeviceAccess cpu) {
        pctx.prefetchPc = pc;
        setupPrefetch(pctx, cpu);
        int bytePos = pctx.start;
//        LOG.info("{} prefetch @ pc: {}", cpu, th(pc));
        opcodes.clear();
        do {
            int val = pctx.buf.getShort(bytePos) & 0xFFFF;
            if (val == 0) {
                LOG.error("Invalid fetch, start PC: {}, current: {} opcode: {}", th(pc), th(bytePos), th(val));
                pctx.inst = Sh2Instructions.generateInst(Ints.toArray(opcodes));
                LOG.info(instToString(pctx, cpu));
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

    private static String instToString(PrefetchContext pctx, CpuDeviceAccess cpu) {
        StringBuilder sb = new StringBuilder("\n");
        final int pc = pctx.prefetchPc;
        final String type = cpu.name().substring(0, 1);
        for (int i = 0; i < pctx.inst.length; i++) {
            sb.append(Sh2Helper.getInstString(type, pc + (i << 1), pctx.inst[i].opcode)).append("\n");
        }
        return sb.toString();
    }

    private void setupPrefetch(final PrefetchContext pctx, CpuDeviceAccess cpu) {
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
        pctx.start = pctx.pcMasked;
    }

    private int limit = 50;

    public void prefetch(int pc, CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        Map<Integer, PrefetchContext> pMap = cpu == MASTER ? mPrefetch : sPrefetch;
        PrefetchContext pctxStored = pMap.get(pc);
        if (pctxStored != null) {
            pctxStored.hits++;
            if (((pctxStored.hits + 1) & OPT_THRESHOLD) == 0) {
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
            handleMapLoad(pMap, cpu);
            PrefetchContext prev = pMap.put(pc, pctx);
            if (prev != null) {
                throw new RuntimeException("PC has been rewritten: " + th(pc));
            }
        }
        assert pctx != null;
    }

    private void handleMapLoad(Map<Integer, PrefetchContext> pMap, CpuDeviceAccess cpu) {
        if (pMap.size() > limit) {
            int size = pMap.size();
            pMap.entrySet().removeIf(e -> e.getValue().inst == null);
            int newSize = pMap.size();
            int prevLimit = limit;
            limit = limit << ((newSize > limit * 0.75) ? 1 : 0);
            limit = limit >> ((newSize < limit >> 2) ? 1 : 0);
            limit = Math.max(24, limit);
            if (limit != prevLimit) {
//                LOG.info("{} clear {}->{}, limit {}", cpu, size, newSize, limit);
            }
        }
    }

    public void fetch(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        fetchResult.inst = null;
        if (!SH2_ENABLE_PREFETCH) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        //TODO vr, vf are fetching from the cache data array, avoid using stale data
        if ((fetchResult.pc >>> 28) == 0xC) { //vr, vf
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
        if (false) { //double check
            int memVal = memory.read(pc, Size.WORD);
            if (memVal != fetchResult.opcode) { //vr, vf
                invalidatePrefetch(cpu, pctx, pc, fetchResult);
                fetchResult.opcode = memVal;
            }
        }
        return;
    }

    public int fetchDelaySlot(int pc, CpuDeviceAccess cpu) {
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

    private void invalidatePrefetch(CpuDeviceAccess cpu, PrefetchContext pctx, int pc, FetchResult fetchResult) {
        Map<Integer, PrefetchContext> pMap = cpu == MASTER ? mPrefetch : sPrefetch;
        pMap.remove(pctx.prefetchPc);
        LOG.info("Mismatch on {}, remove fetched data for PC: {}", th(pc), th(pctx.prefetchPc));
        pctx.prefetchPc = -1000;
        fetchResult.inst = null;
    }

    private void checkPrefetch(int writeAddr, int val, Size size) {
        writeAddr &= 0xFFF_FFFF; //drop cached vs uncached
        for (int i = 0; i < 2; i++) {
            if (cache[i].getCacheContext().cacheEn == 0) {
                continue;
            }
            PrefetchContext pctx = prefetchContexts[i];
            if (writeAddr >= pctx.start && writeAddr <= pctx.end) {
                CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
//				LOG.warn("{} write, addr: {} val: {} {}, {} PF window: [{},{}]", cpuAccess,
//						th(writeAddr), th(val), size, CpuDeviceAccess.cdaValues[i], th(start), th(end));
//				prefetch(prefetchContexts[i].prefetchPc, CpuDeviceAccess.cdaValues[i]);
            }
        }
    }
}
