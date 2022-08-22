package sh2.sh2.prefetch;

import com.google.common.primitives.Ints;
import omegadrive.cpu.CpuFastDebug.PcInfoWrapper;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import sh2.BiosHolder;
import sh2.IMemory;
import sh2.Md32x;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.*;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.sh2.drc.Sh2Block;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static omegadrive.cpu.CpuFastDebug.NOT_VISITED;
import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.SH2_CACHE_THROUGH_OFFSET;
import static sh2.dict.S32xDict.SH2_SDRAM_MASK;
import static sh2.dict.S32xMemAccessDelay.SDRAM;
import static sh2.sh2.Sh2Debug.pcAreaMaskMap;
import static sh2.sh2.Sh2Instructions.generateInst;
import static sh2.sh2.drc.Ow2DrcOptimizer.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogHelper.getLogger(Sh2Prefetch.class.getSimpleName());

    //TODO fix, see VF, ECCO
    public static final int SH2_DRC_MAX_BLOCK_LEN = Integer.parseInt(System.getProperty("helios.32x.sh2.drc.maxBlockLen", "40000"));

    private static final boolean SH2_REUSE_FETCH_DATA = true; //TODO vr requires false
    //NOTE vf is rewriting code so much that setting this to false slows it down
    private static final boolean SH2_LIMIT_BLOCK_MAP_LOAD = true;

    private static final boolean SH2_LOG_PC_HITS = false;
    private static final int INITIAL_BLOCK_LIMIT = 50;
    public static final int PC_AREA_SHIFT = 24;
    public static final int PC_CACHE_AREA_SHIFT = 28;

    private static final boolean verbose = false;
    private static final boolean collectStats = verbose || false;

    private final Map<PcInfoWrapper, Sh2Block>[] prefetchMap = new Map[]{new HashMap<PcInfoWrapper, Sh2Block>(), new HashMap<PcInfoWrapper, Sh2Block>()};
    private final Object[] pcInfoWrapperMS = new Object[2];

    private final Stats[] stats = {new Stats(MASTER), new Stats(SLAVE)};

    private final IMemory memory;
    private final Sh2Cache[] cache;
    private final Sh2DrcContext[] sh2Context;
    private final Sh2.Sh2Config sh2Config;

    public final int romSize, romMask;
    public final BiosHolder.BiosData[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

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
        public int opcode, pc, branchPc;
        public Sh2Instructions.Sh2BaseInstruction sh2Inst;
        public BytecodeContext delaySlotCtx;
        public boolean delaySlot;
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

        pcInfoWrapperMS[0] = Sh2Debug.getPcInfoWrapper(MASTER);
        pcInfoWrapperMS[1] = Sh2Debug.getPcInfoWrapper(SLAVE);
        sh2Config = Sh2.Sh2Config.instance.get();
        Ow2DrcOptimizer.clear();
    }

    private Sh2Block doPrefetch(int pc, CpuDeviceAccess cpu) {
        if (collectStats) stats[cpu.ordinal()].addMiss();
        final Sh2Block block = new Sh2Block();
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        block.isCacheFetch = (pc >>> PC_CACHE_AREA_SHIFT) == 0;
        block.prefetchPc = pc;
        block.drcContext = this.sh2Context[cpu.ordinal()];
        setupPrefetch(block, cpu);
        final PcInfoWrapper piw = getOrCreate(pc, cpu);
        int bytePos = block.start;
        int currentPc = pc;
        if (verbose) LOG.info("{} prefetch at pc: {}", cpu, th(pc));
        opcodes.clear();
        final Sh2InstructionWrapper[] op = Sh2Instructions.instOpcodeMap;
        //force a cache effect by fetching the current PC
        if (isCache) {
            sh2Cache.cacheMemoryRead(pc, Size.WORD);
        }
        final int pcLimit = pc + SH2_DRC_MAX_BLOCK_LEN;
        do {
            int val = isCache ? sh2Cache.readDirect(currentPc, Size.WORD) : block.fetchBuffer.getShort(bytePos) & 0xFFFF;
            final Sh2Instructions.Sh2BaseInstruction inst = op[val].inst;
            if (inst.isIllegal) {
                LOG.error("{} Invalid fetch, start PC: {}, current: {} opcode: {}", cpu, th(pc), th(bytePos), th(val));
                if (block.prefetchWords != null) {
                    block.stage1(generateInst(block.prefetchWords));
                    LOG.info(instToString(pc, block.inst, cpu));
                }
                throw new RuntimeException("Fatal! " + inst + "," + th(val));
            }
            opcodes.add(Util.getFromIntegerCache(val));
            if (inst.isBranch) {
                if (inst.isBranchDelaySlot) {
                    //TODO readDirect breaks Chaotix, Vf and possibly more
//                    int nextVal = isCache ? sh2Cache.readDirect(currentPc + 2, Size.WORD) :
//                            block.fetchBuffer.getShort(bytePos) & 0xFFFF;
                    int nextVal = isCache ? sh2Cache.cacheMemoryRead((currentPc + 2), Size.WORD) :
                            block.fetchBuffer.getShort(bytePos + 2) & 0xFFFF;
                    opcodes.add(Util.getFromIntegerCache(nextVal));
                }
                break;
            }
            bytePos += 2;
            currentPc += 2;
        } while (currentPc < pcLimit);
        block.prefetchWords = Ints.toArray(opcodes);
        block.prefetchLenWords = block.prefetchWords.length;
        block.end = block.start + ((block.prefetchLenWords - 1) << 1);
        block.stage1(generateInst(block.prefetchWords));
        if (verbose) LOG.info("{} prefetch at pc: {}, len: {}\n{}", cpu, th(pc), block.prefetchLenWords,
                instToString(pc, generateInst(block.prefetchWords), cpu));
        return block;
    }

    private static String instToString(int pc, Sh2InstructionWrapper[] inst, CpuDeviceAccess cpu) {
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
        switch (pc >> PC_AREA_SHIFT) {
            case 6:
            case 0x26:
                block.start = Math.max(0, block.start) & SH2_SDRAM_MASK;
                block.pcMasked = pc & SH2_SDRAM_MASK;
                block.fetchMemAccessDelay = SDRAM;
                block.fetchBuffer = sdram;
                break;
            case 2:
            case 0x22:
                block.start = Math.max(0, block.start) & romMask;
                block.pcMasked = pc & romMask;
                block.fetchMemAccessDelay = S32xMemAccessDelay.ROM;
                block.fetchBuffer = rom;
                break;
            case 0:
            case 0x20:
                block.fetchBuffer = bios[cpu.ordinal()].buffer;
                block.start = Math.max(0, block.start);
                block.pcMasked = pc;
                block.fetchMemAccessDelay = S32xMemAccessDelay.BOOT_ROM;
                break;
            default:
                if ((pc >>> 28) == 0xC) {
                    int twoWay = cache[cpu.ordinal()].getCacheContext().twoWay;
                    final int mask = Sh2Cache.DATA_ARRAY_MASK >> twoWay;
                    block.start = Math.max(0, block.start) & mask;
                    block.fetchMemAccessDelay = S32xMemAccessDelay.SYS_REG;
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


    private void checkBlock(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        PcInfoWrapper piw = get(pc, cpu);
        final Map<PcInfoWrapper, Sh2Block> pMap = prefetchMap[cpu.ordinal()];
        assert piw != null;
        if (piw != NOT_VISITED) {
            Sh2Block block = pMap.getOrDefault(piw, Sh2Block.INVALID_BLOCK);
            if (block != Sh2Block.INVALID_BLOCK) {
                assert fetchResult.pc == block.prefetchPc : th(fetchResult.pc);
                block.addHit();
                fetchResult.block = block;
                return;
            }
        }
        Sh2Block block = doPrefetch(pc, cpu);
        if (SH2_REUSE_FETCH_DATA) {
            piw = get(pc, cpu);
            Sh2Block prev = pMap.get(piw);
            pMap.put(piw, block);
            if (verbose && prev != null && !block.equals(prev)) {
                LOG.warn("{} New block generated at PC: {}", cpu, th(pc));
            }
        }
        assert block != null;
        fetchResult.block = block;
    }

    private PcInfoWrapper get(int pc, CpuDeviceAccess cpu) {
        PcInfoWrapper[] area = ((PcInfoWrapper[][]) pcInfoWrapperMS[cpu.ordinal()])[pc >>> PC_AREA_SHIFT];
//        assert (pc & pcAreaMaskMap[pc >>> PC_AREA_SHIFT]) == (pc & 0xFFFFFF) : th(pc) + "," + th(pcAreaMaskMap[pc >>> PC_AREA_SHIFT]);
        PcInfoWrapper piw = area[pc & pcAreaMaskMap[pc >>> PC_AREA_SHIFT]];
        piw.hits++;
        return piw;
    }

    private PcInfoWrapper getOrCreate(int pc, CpuDeviceAccess cpu) {
        PcInfoWrapper piw = get(pc, cpu);
        assert piw != null;
        if (piw == NOT_VISITED) {
            piw = new PcInfoWrapper();
            piw.area = pc >>> PC_AREA_SHIFT;
            piw.pcMasked = pc & pcAreaMaskMap[piw.area];
            ((PcInfoWrapper[][]) pcInfoWrapperMS[cpu.ordinal()])
                    [piw.area][piw.pcMasked] = piw;
        }
        return piw;
    }

    static Predicate<Map.Entry<PcInfoWrapper, Sh2Block>> removeEntryPred =
            e -> !e.getValue().shouldKeep();

    private void handleMapLoad(Map<PcInfoWrapper, Sh2Block> pMap, CpuDeviceAccess cpu) {
        if (pMap.size() > blockLimit) {
            int size = pMap.size();
            String s = null;
            if (verbose) {
                s = pMap.entrySet().stream().filter(removeEntryPred).map(e -> e.getValue().toString()).
                        collect(Collectors.joining("\n"));
            }
            pMap.entrySet().removeIf(removeEntryPred);
            int newSize = pMap.size();
            int prevLimit = blockLimit;
            blockLimit = blockLimit << ((newSize > blockLimit * 0.75) ? 1 : 0);
            blockLimit = blockLimit >> ((newSize < blockLimit >> 2) ? 1 : 0);
            blockLimit = Math.max(24, blockLimit);
            if (verbose) LOG.info("{} clear {}->{}, limit {}\n{}", cpu, size, newSize, blockLimit, s);
            if (blockLimit != prevLimit) {
                if (verbose) LOG.info("{} clear {}->{}, limit {}\n{}", cpu, size, newSize, blockLimit, s);
            }
        }
    }

    public void fetch(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        if (!sh2Config.prefetchEn) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        Sh2Block block = fetchResult.block;
        int pcPosWords = block != null ? (pc - block.prefetchPc) >> 1 : -1; //0 based
        boolean withinFetchWindow = pcPosWords < block.prefetchLenWords && pcPosWords >= 0;
        if (!withinFetchWindow) {
            checkBlock(fetchResult, cpu);
            pcPosWords = 0;
            block = fetchResult.block;
        } else {
            block.addHit(); //keeps looping on the same block
        }
        cacheOnFetch(pc, block.prefetchWords[pcPosWords], cpu);
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
        assert block != Sh2Block.INVALID_BLOCK && block.prefetchWords != null && block.prefetchWords.length > 0;
        fetchResult.opcode = block.prefetchWords[pcPosWords];
        assert fetchResult.block != null;
        return;
    }

    public int fetchDelaySlot(int pc, Sh2.FetchResult ft, CpuDeviceAccess cpu) {
        if (!sh2Config.prefetchEn) {
            return memory.read(pc, Size.WORD);
        }
        Sh2Block block = ft.block;
        int pcDeltaWords = (pc - block.prefetchPc) >> 1;
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        int res;
        boolean withinFetchWindow = pcDeltaWords < block.prefetchLenWords && pcDeltaWords >= 0;
        if (withinFetchWindow) {
            S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
            res = block.prefetchWords[pcDeltaWords];
            cacheOnFetch(pc, res, cpu);
        } else {
            res = memory.read(pc, Size.WORD);
            if (collectStats) stats[cpu.ordinal()].addDelaySlotMiss();
        }
        return res;
    }

    @Override
    public void dataWrite(CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        checkPoller(cpuWrite, PollCancelType.SDRAM, addr, val, size);
        if (pcAreaMaskMap[addr >>> PC_AREA_SHIFT] == 0) return;
        switch (size) {
            case WORD:
            case BYTE:
                dataWriteWord(cpuWrite, addr, val, size);
                break;
            case LONG:
                dataWriteWord(cpuWrite, addr, val >> 16, Size.WORD);
                dataWriteWord(cpuWrite, addr + 2, val & 0xFFFF, Size.WORD);
                break;
        }
    }

    public static void checkPoller(CpuDeviceAccess cpuWrite, S32xDict.S32xRegType type, int addr, int val, Size size) {
        checkPoller(cpuWrite, PollCancelType.valueOf(type.name()), addr, val, size);
    }

    public static void checkPoller(CpuDeviceAccess cpuWrite, PollCancelType type, int addr, int val, Size size) {
        if (Sh2Block.currentPollers[0].isPollingActive()) {
            checkPollerInternal(Sh2Block.currentPollers[0], cpuWrite, type, addr, val, size);
        }
        if (Sh2Block.currentPollers[1].isPollingActive()) {
            checkPollerInternal(Sh2Block.currentPollers[1], cpuWrite, type, addr, val, size);
        }
    }

    public static void checkPollerInternal(PollerCtx c, CpuDeviceAccess cpuWrite, PollCancelType type,
                                           int addr, int val, Size size) {
        if (c.block.pollType == PollType.BUSY_LOOP) {
            return;
        }
        addr = addr & 0xFFF_FFFF;
        int tgtStart = (c.memoryTarget & 0xFFF_FFFF);
        int tgtEnd = tgtStart + Math.max(1, c.memTargetSize.ordinal() << 1);
        int addrEnd = addr + Math.max(1, size.ordinal() << 1);
        if ((addrEnd > tgtStart) && (addr <= tgtEnd)) {
            if (verbose)
                LOG.info("{} Poll write addr: {} {}, target: {} {} {}, val: {}", cpuWrite,
                        th(addr), size, c.cpu, th(c.memoryTarget), c.memTargetSize, th(val));
            stopPoller(type, c);
        }
    }

    public static void stopPoller(PollCancelType type, PollerCtx c) {
        Md32x.md32x.resumeNow(type, c.cpu);
    }

    public void dataWriteWord(CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        boolean isCacheArray = addr >>> PC_AREA_SHIFT == 0xC0;
        boolean isWriteThrough = addr >>> 28 == 2;

        for (int i = 0; i <= SLAVE.ordinal(); i++) {
            //sh2 cacheArrays are not shared!
            if (isCacheArray && i != cpuWrite.ordinal()) {
                continue;
            }
            checkAddress(cpuWrite, CpuDeviceAccess.cdaValues[i], addr, val, size);
            boolean isCacheEnabled = cache[i].getCacheContext().cacheEn > 0;
            if (!isCacheEnabled && !isCacheArray) {
                int otherAddr = isWriteThrough ? addr & 0xFFF_FFFF : addr | SH2_CACHE_THROUGH_OFFSET;
                checkAddress(cpuWrite, CpuDeviceAccess.cdaValues[i], otherAddr, val, size);
            }
        }
    }

    private void checkAddress(CpuDeviceAccess writer, CpuDeviceAccess blockOwner, int addr, int val, Size size) {
        PcInfoWrapper piw = get(addr, blockOwner);
        if (piw == null || piw == NOT_VISITED) {
            return;
        }
        for (var entry : prefetchMap[blockOwner.ordinal()].entrySet()) {
            Sh2Block b = entry.getValue();
            if (b != null) {
                int start = b.prefetchPc;
                int end = b.prefetchPc + (b.prefetchLenWords << 1);
                if (addr >= start && addr < end) {
                    if (size == Size.WORD) {
                        //cosmic carnage
                        int prev = b.prefetchWords[((addr - start) >> 1)];
                        if (prev == val) {
                            return;
                        }
                    }
                    if (verbose) {
                        String s = formatMessage("{} write at addr: {} val: {} {}, invalidate {} block with start: {} blockLen: {}",
                                writer, th(addr), th(val), Size.WORD, blockOwner, th(b.prefetchPc), b.prefetchLenWords);
                        LOG.info(s);
                    }
                    entry.getValue().invalidate();
                    entry.setValue(Sh2Block.INVALID_BLOCK);
                }
            }
        }
    }

    public static String formatMessage(String s, Object... o) {
        return MessageFormatter.arrayFormat(s, o).getMessage();
    }

    private void cacheOnFetch(int pc, int expOpcode, CpuDeviceAccess cpu) {
        if (!sh2Config.cacheEn) {
            return;
        }
        boolean isCache = pc >>> PC_CACHE_AREA_SHIFT == 0;
        if (isCache && cache[cpu.ordinal()].getCacheContext().cacheEn > 0) {
            //NOTE necessary to trigger the cache hit on fetch
            int cached = cache[cpu.ordinal()].cacheMemoryRead(pc, Size.WORD);
            assert cached == expOpcode;
        }
    }

    //TODO this should check cache contents vs SDRAM to detect inconsistencies
    public void invalidateAllPrefetch(CpuDeviceAccess cpu) {
        if (sh2Config.cacheEn) {
            prefetchMap[cpu.ordinal()].clear();
            if (verbose) LOG.info("{} invalidate all prefetch data", cpu);
        }
    }

    public void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx) {
        int lineStart = ctx.prevCacheAddr;
        int lineEnd = lineStart + 16;
        for (var entry : prefetchMap[ctx.cpu.ordinal()].entrySet()) {
            Sh2Block b = entry.getValue();
            if (b != null && b != Sh2Block.INVALID_BLOCK && b.isCacheFetch) {
                int start = b.prefetchPc;
                int end = b.prefetchPc + (b.prefetchLenWords << 1);
                if (lineEnd >= start && lineStart < end) {
                    if (verbose) {
                        String s = LogHelper.formatMessage("{} invalidateCachePrefetch {}, block with start: {} blockLen: {}",
                                ctx.cpu, "cacheReadAddr: " + th(ctx.cacheReadAddr) + ", " + th(lineStart) + " - " +
                                        th(lineEnd), th(b.prefetchPc), b.prefetchLenWords);
                        LOG.info(s);
                        //                    System.out.println(s);
                    }
                    entry.getValue().invalidate();
                    entry.setValue(Sh2Block.INVALID_BLOCK);
                }
            }
        }
    }

    public List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int addr) {
        List<Sh2Block> l = new ArrayList<>();
        for (var entry : prefetchMap[cpu.ordinal()].entrySet()) {
            Sh2Block b = entry.getValue();
            if (b != null) {
                int start = b.prefetchPc;
                int end = b.prefetchPc + (b.prefetchLenWords << 1);
                if (addr >= start && addr < end) {
                    l.add(b);
                }
            }
        }
        return l;
    }

    long cnt = 0;

    @Override
    public void newFrame() {
        if (SH2_LIMIT_BLOCK_MAP_LOAD) {
            handleMapLoad(prefetchMap[MASTER.ordinal()], MASTER);
            handleMapLoad(prefetchMap[SLAVE.ordinal()], SLAVE);
        }

        if (SH2_LOG_PC_HITS && (++cnt & 0x2FF) == 0) {
            logPcHits(MASTER);
            logPcHits(SLAVE);
        }
    }

    private void logPcHits(CpuDeviceAccess cpu) {
        PcInfoWrapper[][] pw = (PcInfoWrapper[][]) pcInfoWrapperMS[cpu.ordinal()];
        Map<PcInfoWrapper, Long> hitMap = new HashMap<>();
        long top10 = 10;
        for (int i = 0; i < pw.length; i++) {
            for (int j = 0; j < pw[i].length; j++) {
                PcInfoWrapper piw = pw[i][j];
                if (piw != NOT_VISITED) {
                    if (piw.hits < top10) {
                        continue;
                    }
                    int pc = (i << PC_AREA_SHIFT) | j;
                    hitMap.put(piw, Long.valueOf(piw.hits));
                    top10 = hitMap.values().stream().sorted().limit(10).findFirst().orElse(10L);
//                        LOG.info("{} PC: {} hits: {}, {}", cpu, th(pc), piw.hits, piw);
                }
            }
        }
        List<Map.Entry<PcInfoWrapper, Long>> l = hitMap.entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).
                limit(10).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        l.forEach(e -> {
            PcInfoWrapper piw = e.getKey();
            int pc = (piw.area << PC_AREA_SHIFT) | piw.pcMasked;
            Sh2Block res = prefetchMap[cpu.ordinal()].getOrDefault(piw, Sh2Block.INVALID_BLOCK);
            if (res == Sh2Block.INVALID_BLOCK) {
                System.out.println("oops");
            }
            sb.append(cpu + " " + th(pc) + "," + e.getValue() + ", block: " +
                    th(res.prefetchPc) + "," + res.pollType + "," + res.hits + "\n" + Sh2Instructions.toListOfInst(res)).append("\n");

        });
//            String s = hitMap.entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).
//                    limit(10).map(Objects::toString).collect(Collectors.joining("\n"));
        LOG.info(sb.toString());
    }
}