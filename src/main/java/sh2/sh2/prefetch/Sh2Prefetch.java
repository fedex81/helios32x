package sh2.sh2.prefetch;

import com.google.common.primitives.Ints;
import omegadrive.cpu.CpuFastDebug;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.commons.LocalVariablesSorter;
import sh2.IMemory;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.*;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Instructions.Sh2Instruction;
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.*;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.Sh2Memory.SDRAM_MASK;
import static sh2.dict.S32xMemAccessDelay.SDRAM;
import static sh2.sh2.Sh2Instructions.generateInst;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * vr, vf are fetching from the cache data array, avoid using stale data
 */
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));

    private static final boolean SH2_REUSE_FETCH_DATA = true;
    private static final int INITIAL_BLOCK_LIMIT = 50;

    private static final boolean verbose = false;
    private static final boolean collectStats = verbose || false;

    //TODO creates a lot of garbage, int -> Integer
    private Map<Integer, Sh2Block>[] prefetchMap = new Map[]{new HashMap<Integer, Sh2Block>(), new HashMap<Integer, Sh2Block>()};
    private final Object[] pcVisited = new Object[2];

    private Stats[] stats = {new Stats(MASTER), new Stats(SLAVE)};

    private final IMemory memory;
    private final Sh2Cache[] cache;
    private final Sh2DrcContext[] sh2Context;

    public final int romSize, romMask;
    public final ByteBuffer[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

    private final CpuFastDebug.CpuDebugContext dc;
    private final List<Integer> opcodes = new ArrayList<>(10);
    private int blockLimit = INITIAL_BLOCK_LIMIT;

    public static class Sh2DrcContext {
        public CpuDeviceAccess cpu;
        public Sh2Impl sh2;
        public Sh2Context sh2Ctx;
        public Sh2Memory memory;
    }

    public static class BytecodeContext {
        public Sh2DrcContext drcCtx;
        public String classDesc;
        public LocalVariablesSorter mv;
        public int opcode, pc;
        public Sh2Instructions.Sh2Inst sh2Inst;
    }

    public Sh2Prefetch(Sh2Memory memory, Sh2Cache[] cache, Sh2DrcContext[] sh2Ctx) {
        this.cache = cache;
        this.memory = memory;
        this.sh2Context = sh2Ctx;
        romMask = memory.romMask;
        romSize = memory.romSize;
        sdram = memory.sdram;
        rom = memory.rom;
        bios = memory.bios;

        dc = Sh2Debug.createContext();
        int[][] pcVisitedM = new int[dc.pcAreasNumber][0];
        int[][] pcVisitedS = new int[dc.pcAreasNumber][0];
        Arrays.stream(dc.pcAreas).forEach(idx -> {
            pcVisitedM[idx] = new int[dc.pcAreaSize];
            pcVisitedS[idx] = new int[dc.pcAreaSize];
        });
        pcVisited[0] = pcVisitedM;
        pcVisited[1] = pcVisitedS;
    }

    private Sh2Block doPrefetch(int pc, CpuDeviceAccess cpu) {
        final Sh2Block block = new Sh2Block();
        block.prefetchPc = pc;
        block.drcContext = this.sh2Context[cpu.ordinal()];
        setupPrefetch(block, cpu);
        final boolean isCache = (pc >>> 28) == 2;
        assert !isCache; //TODO verify, Doom Resurrection?
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final int[][] pcVisit = (int[][]) pcVisited[cpu.ordinal()];
        int bytePos = block.start;
        int currentPc = pc;
        if (verbose) LOG.info("{} prefetch @ pc: {}", cpu, th(pc));
        opcodes.clear();
        final Sh2Instruction[] op = Sh2Instructions.instOpcodeMap;
        do {
            int val = isCache ? sh2Cache.cacheMemoryRead(currentPc, Size.WORD) :
                    block.fetchBuffer.getShort(bytePos) & 0xFFFF;
            pcVisit[currentPc >>> dc.pcAreaShift][currentPc & dc.pcMask] = 1;
            if (op[val].isIllegal) {
                LOG.error("{} Invalid fetch, start PC: {}, current: {} opcode: {}", cpu, th(pc), th(bytePos), th(val));
                if (block.prefetchWords != null) {
                    block.stage1(generateInst(block.prefetchWords));
                    LOG.info(instToString(pc, block.inst, cpu));
                }
                throw new RuntimeException("Fatal!");
            }
            opcodes.add(Util.getFromIntegerCache(val));
            if (op[val].isBranch) {
                if (op[val].isBranchDelaySlot) {
                    int nextVal = isCache ? sh2Cache.cacheMemoryRead(currentPc + 2, Size.WORD) :
                            block.fetchBuffer.getShort(bytePos + 2) & 0xFFFF;
                    opcodes.add(Util.getFromIntegerCache(nextVal));
                    pcVisit[(currentPc + 2) >>> dc.pcAreaShift][currentPc & dc.pcMask] = 1;
                }
                break;
            }
            bytePos += 2;
            currentPc += 2;
        } while (true);
        block.prefetchWords = Ints.toArray(opcodes);
        block.prefetchLenWords = block.prefetchWords.length;
        block.end = block.start + ((block.prefetchLenWords - 1) << 1);
        if (verbose) LOG.info("{} prefetch @ pc: {}\n{}", cpu, th(pc),
                instToString(pc, generateInst(block.prefetchWords), cpu));
        return block;
    }

    private static String instToString(int pc, Sh2Instruction[] inst, CpuDeviceAccess cpu) {
        StringBuilder sb = new StringBuilder("\n");
        final String type = cpu.name().substring(0, 1);
        for (int i = 0; i < inst.length; i++) {
            sb.append(Sh2Helper.getInstString(type, pc + (i << 1), inst[i].opcode)).append("\n");
        }
        return sb.toString();
    }

    private void setupPrefetch(final Sh2Block block, CpuDeviceAccess cpu) {
        final int pc = block.prefetchPc;
        block.start = pc & 0xFF_FFFF;
        switch (pc >> 24) {
            case 6:
                block.start = Math.max(0, block.start) & SDRAM_MASK;
                block.pcMasked = pc & SDRAM_MASK;
                block.memAccessDelay = SDRAM;
                block.fetchBuffer = sdram;
                break;
            case 2:
                block.start = Math.max(0, block.start) & romMask;
                block.pcMasked = pc & romMask;
                block.memAccessDelay = S32xMemAccessDelay.ROM;
                block.fetchBuffer = rom;
                break;
            case 0:
                block.fetchBuffer = bios[cpu.ordinal()];
                block.start = Math.max(0, block.start);
                block.pcMasked = pc;
                block.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> 28) == 0xC) {
                    int twoWay = cache[cpu.ordinal()].getCacheContext().twoWay;
                    final int mask = Sh2Cache.DATA_ARRAY_MASK >> twoWay;
                    block.start = Math.max(0, block.start) & mask;
                    block.memAccessDelay = S32xMemAccessDelay.SYS_REG;
                    block.fetchBuffer = cache[cpu.ordinal()].getDataArray();
                    block.pcMasked = pc & mask;
                } else {
                    LOG.error("{} Unhandled prefetch: {}", cpu, th(pc));
                    throw new RuntimeException("Unhandled prefetch: " + th(pc));
                }
                break;
        }
        block.start = block.pcMasked;
    }


    private void prefetch(FetchResult fetchResult, CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) return;
        final int pc = fetchResult.pc;
        final Map<Integer, Sh2Block> pMap = prefetchMap[cpu.ordinal()];
        Sh2Block block = pMap.get(pc);
        if (block != null && block != Sh2Block.INVALID_BLOCK) {
            assert fetchResult.pc == block.prefetchPc;
            fetchResult.block = block;
            return;
        }
        block = doPrefetch(pc, cpu);
        if (SH2_REUSE_FETCH_DATA) {
            Sh2Block prev = pMap.get(pc);
            handleMapLoad(pMap, cpu);
            pMap.put(pc, block);
            if (prev != null && !block.equals(prev)) {
                LOG.warn("New block generated at PC: " + th(pc));
            }
        }
        assert block != null;
        fetchResult.block = block;
    }

    private void handleMapLoad(Map<Integer, Sh2Block> pMap, CpuDeviceAccess cpu) {
        if (pMap.size() > blockLimit) {
            int size = pMap.size();
            pMap.entrySet().removeIf(e -> e.getValue().inst == null);
            int newSize = pMap.size();
            int prevLimit = blockLimit;
            blockLimit = blockLimit << ((newSize > blockLimit * 0.75) ? 1 : 0);
            blockLimit = blockLimit >> ((newSize < blockLimit >> 2) ? 1 : 0);
            blockLimit = Math.max(24, blockLimit);
            if (blockLimit != prevLimit) {
                if (verbose) LOG.info("{} clear {}->{}, limit {}", cpu, size, newSize, blockLimit);
            }
//            LOG.info("{} clear {}->{}, limit {}", cpu, size, newSize, blockLimit);
        }
    }

    public void fetch(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        if (!SH2_ENABLE_PREFETCH) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        Sh2Block block = fetchResult.block;
        int pcDeltaWords = block != null ? (pc - block.prefetchPc) >> 1 : -1;
        boolean fetchAgain = pcDeltaWords < 0 || pcDeltaWords >= block.prefetchLenWords;
        if (fetchAgain) {
            prefetch(fetchResult, cpu);
            pcDeltaWords = 0;
            block = fetchResult.block;
            if (collectStats) stats[cpu.ordinal()].addMiss();
        } else {
            block.addHit();
        }
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.memAccessDelay);
        assert block != Sh2Block.INVALID_BLOCK && block.prefetchWords != null && block.prefetchWords.length > 0;
        fetchResult.opcode = block.prefetchWords[pcDeltaWords];
        return;
    }

    public int fetchDelaySlot(int pc, Sh2.FetchResult ft, CpuDeviceAccess cpu) {
        if (!SH2_ENABLE_PREFETCH) {
            return memory.read(pc, Size.WORD);
        }
        Sh2Block block = ft.block;
        int pcDeltaWords = (pc - block.prefetchPc) >> 1;
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        int res;
        boolean withinFetchWindow = pcDeltaWords < block.prefetchLenWords && pcDeltaWords >= 0;
        if (withinFetchWindow) {
            S32xMemAccessDelay.addReadCpuDelay(block.memAccessDelay);
            res = block.prefetchWords[pcDeltaWords];
        } else {
            res = memory.read(pc, Size.WORD);
            if (collectStats) stats[cpu.ordinal()].addDelaySlotMiss();
        }
        return res;
    }

    @Override
    public void dataWrite(CpuDeviceAccess cpu, int addr, int val, Size size) {
        final int[][] pcVisit = (int[][]) pcVisited[cpu.ordinal()];
        final int[] pcVisArea = pcVisit[addr >>> dc.pcAreaShift];
        if (pcVisArea.length == 0 || pcVisArea[addr & dc.pcMask] == 0) {
            return;
        }

        for (int i = 0; i < SLAVE.ordinal(); i++) {
            for (var entry : prefetchMap[i].entrySet()) {
                Sh2Block b = entry.getValue();
                if (b != null) {
                    int start = b.prefetchPc;
                    int end = b.prefetchPc + (b.prefetchLenWords << 1);
                    if (addr >= start && addr <= end) {
//                            if(verbose)
                        LOG.info("{} rewrite block at addr: {}, val: {} {}", cpu, th(addr), th(val), size);
                        entry.getValue().invalidate();
                        entry.setValue(Sh2Block.INVALID_BLOCK);
                    }
                }
            }
        }
    }
}