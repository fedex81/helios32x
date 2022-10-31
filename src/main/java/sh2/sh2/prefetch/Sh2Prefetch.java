package sh2.sh2.prefetch;

import com.google.common.collect.Range;
import omegadrive.cpu.CpuFastDebug.PcInfoWrapper;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.slf4j.Logger;
import sh2.BiosHolder;
import sh2.IMemory;
import sh2.S32xUtil;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.event.SysEventManager;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Helper;
import sh2.sh2.Sh2Helper.Sh2PcInfoWrapper;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.drc.Sh2Block;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xMemAccessDelay.SDRAM;
import static sh2.sh2.Sh2Debug.pcAreaMaskMap;
import static sh2.sh2.Sh2Helper.SH2_NOT_VISITED;
import static sh2.sh2.Sh2Instructions.generateInst;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollerCtx;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogHelper.getLogger(Sh2Prefetch.class.getSimpleName());

    public static final int SH2_DRC_MAX_BLOCK_LEN = Integer.parseInt(System.getProperty("helios.32x.sh2.drc.maxBlockLen", "32"));

    private final static boolean ENABLE_BLOCK_RECYCLING = true;
    private static final boolean SH2_LOG_PC_HITS = false;
    public static final int PC_CACHE_AREA_SHIFT = 28;

    private static final boolean verbose = false;
    private static final boolean collectStats = verbose || false;
    private final Stats[] stats = {new Stats(MASTER), new Stats(SLAVE)};

    private final IMemory memory;
    private final Sh2Cache[] cache;
    private final Sh2DrcContext[] sh2Context;
    private final Sh2.Sh2Config sh2Config;
    private final int[] opcodeWords;

    public final int romSize, romMask;
    public final BiosHolder.BiosData[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

    public static class Sh2DrcContext {
        public CpuDeviceAccess cpu;
        public Sh2 sh2;
        public Sh2Context sh2Ctx;
        public IMemory memory;
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

    public Sh2Prefetch(IMemory memory, Sh2Cache[] cache, Sh2DrcContext[] sh2Ctx) {
        this.cache = cache;
        this.memory = memory;
        this.sh2Context = sh2Ctx;
        IMemory.MemoryDataCtx mdc = memory.getMemoryDataCtx();
        romMask = mdc.romMask;
        romSize = mdc.romSize;
        sdram = mdc.sdram;
        rom = mdc.rom;
        bios = mdc.bios;
        opcodeWords = new int[SH2_DRC_MAX_BLOCK_LEN];
        sh2Config = Sh2.Sh2Config.get();
    }

    private Sh2Block doPrefetch(Sh2PcInfoWrapper piw, int pc, CpuDeviceAccess cpu) {
        if (collectStats) stats[cpu.ordinal()].addMiss();
        Sh2Block block = doPrefetchInternal(pc, cpu);
        final boolean tryRecycleBlock = ENABLE_BLOCK_RECYCLING && !piw.knownBlocks.isEmpty();
        if (tryRecycleBlock) {
            final int hashCode = S32xUtil.hashCode(block.prefetchWords, block.prefetchLenWords);
            for (var entry : piw.knownBlocks.entrySet()) {
                if (entry.getKey().intValue() == hashCode) {
                    assert Arrays.equals(entry.getValue().prefetchWords, block.prefetchWords);
                    Sh2Block rec = entry.getValue();
                    rec.setValid();
                    rec.nextBlock = Sh2Block.INVALID_BLOCK;
                    if (verbose && rec.isPollingBlock()) {
                        LOG.info("{} recycle block at pc: {}, len: {}\n{}", cpu,
                                th(pc), rec.prefetchLenWords, Sh2Helper.toListOfInst(rec));
                        LOG.info("{}\n{}\n{}", th(pc), rec, rec.poller);
                    }
//                    assert !rec.isPollingBlock() : th(pc) + "\n" + rec + "\n" + rec.poller;
                    return rec;
                }
            }
            //new block, add it to the list
            Sh2Block prev = piw.knownBlocks.put(hashCode, block);
            assert prev == null;
        }
        block.stage1(generateInst(block.prefetchWords));
        if (verbose) LOG.info("{} prefetch block at pc: {}, len: {}\n{}", cpu,
                th(pc), block.prefetchLenWords, Sh2Helper.toListOfInst(block));
        return block;
    }

    private Sh2Block doPrefetchInternal(int pc, CpuDeviceAccess cpu) {
        final Sh2Block block = new Sh2Block(pc, cpu);
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        block.setCacheFetch((pc >>> PC_CACHE_AREA_SHIFT) == 0);
        block.drcContext = this.sh2Context[cpu.ordinal()];
        setupPrefetch(block, cpu);
        if (verbose) LOG.info("{} prefetch at pc: {}", cpu, th(pc));
        //force a cache effect by fetching the current PC
        if (isCache) {
            sh2Cache.cacheMemoryRead(pc, Size.WORD);
        }
        int wordsCount = fillOpcodes(cpu, pc, block);
        assert wordsCount > 0;
        block.prefetchLenWords = wordsCount;
        block.prefetchWords = Arrays.copyOf(opcodeWords, wordsCount);
        block.hashCodeWords = Arrays.hashCode(block.prefetchWords);
        block.end = block.start + ((block.prefetchLenWords - 1) << 1);
        return block;
    }

    private int fillOpcodes(CpuDeviceAccess cpu, int pc, Sh2Block block) {
        return fillOpcodes(cpu, pc, block.start, block.fetchBuffer, block, opcodeWords, false);
    }

    private int fillOpcodes(CpuDeviceAccess cpu, int pc, int blockStart, ByteBuffer fetchBuffer,
                            Sh2Block block, int[] opcodeWords, boolean dummy) {
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final int pcLimit = pc + SH2_DRC_MAX_BLOCK_LEN;
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        final Sh2InstructionWrapper[] op = Sh2Instructions.instOpcodeMap;
        boolean breakOnJump = false;
        int wordsCount = 0;
        int bytePos = blockStart;
        int currentPc = pc;
        do {
            int val = isCache ? sh2Cache.readDirect(currentPc, Size.WORD) : fetchBuffer.getShort(bytePos) & 0xFFFF;
            final Sh2Instructions.Sh2BaseInstruction inst = op[val].inst;
            if (inst.isIllegal) {
                if (!dummy) {
                    LOG.error("{} Invalid fetch, start PC: {}, current: {} opcode: {}", cpu, th(pc), th(bytePos), th(val));
                    throw new RuntimeException("Fatal! " + inst + "," + th(val) + "\n" + block);
                }
                return -1;
            }
            opcodeWords[wordsCount++] = val;
            if (inst.isBranch) {
                if (inst.isBranchDelaySlot) {
                    //TODO readDirect breaks Chaotix, Vf and possibly more
//                    int nextVal = isCache ? sh2Cache.readDirect(currentPc + 2, Size.WORD) :
//                            block.fetchBuffer.getShort(bytePos) & 0xFFFF;
                    int nextVal = isCache ? sh2Cache.cacheMemoryRead((currentPc + 2), Size.WORD) :
                            fetchBuffer.getShort(bytePos + 2) & 0xFFFF;
                    opcodeWords[wordsCount++] = nextVal;
                }
                breakOnJump = true;
                break;
            }
            bytePos += 2;
            currentPc += 2;
        } while (currentPc < pcLimit);
        assert currentPc == pcLimit ? !breakOnJump : true; //TODO test
        if (!dummy) {
            block.setNoJump(currentPc == pcLimit && !breakOnJump);
        }
        return wordsCount;
    }

    private void setupPrefetch(final Sh2Block block, CpuDeviceAccess cpu) {
        final int pc = block.prefetchPc;
        block.start = pc & 0xFF_FFFF;
        switch (pc >> SH2_PC_AREA_SHIFT) {
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
        Sh2PcInfoWrapper piw = Sh2Helper.get(pc, cpu);
        assert piw != null;
        if (piw == SH2_NOT_VISITED) {
            piw = Sh2Helper.getOrCreate(pc, cpu);
        }
        if (piw.block != Sh2Block.INVALID_BLOCK && piw.block.isValid()) {
            assert fetchResult.pc == piw.block.prefetchPc : th(fetchResult.pc);
            piw.block.addHit();
            fetchResult.block = piw.block;
            return;
        }
        Sh2Block block = doPrefetch(piw, pc, cpu);
        Sh2Block prev = piw.block;
        assert prev != null;
        assert block != null && block.isValid();
        assert SH2_NOT_VISITED.block == Sh2Block.INVALID_BLOCK : cpu + "," + th(pc);
        if (prev != Sh2Block.INVALID_BLOCK && !block.equals(prev)) {
            LOG.warn("{} New block generated at PC: {}\nPrev: {}\nNew : {}", cpu, th(pc), prev, block);
        }
        piw.setBlock(block);
        fetchResult.block = block;
    }
    public void fetch(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        if (!sh2Config.prefetchEn) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        final Sh2Block prev = fetchResult.block;
        assert fetchResult.pc != fetchResult.block.prefetchPc;
        checkBlock(fetchResult, cpu);
        final int pcPosWords = 0;
        final Sh2Block block = fetchResult.block;
        block.poller.spinCount = 0;
        assert prev != block : "\n" + prev + "\n" + block;
        assert block.poller.spinCount == 0 : "\n" + prev + "\n" + block;
        cacheOnFetch(pc, block.prefetchWords[pcPosWords], cpu);
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
        assert block != Sh2Block.INVALID_BLOCK && block.prefetchWords != null && block.prefetchWords.length > 0;
        fetchResult.opcode = block.prefetchWords[pcPosWords];
        assert fetchResult.block != null;
        return;
    }

    @Deprecated
    public void fetchOld(FetchResult fetchResult, CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        if (!sh2Config.prefetchEn) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        Sh2Block prev = fetchResult.block;
        Sh2Block block = fetchResult.block;
        int pcPosWords = block != null ? (pc - block.prefetchPc) >> 1 : -1; //0 based
        boolean withinFetchWindow = pcPosWords < block.prefetchLenWords && pcPosWords >= 0;
        if (!withinFetchWindow) {
            checkBlock(fetchResult, cpu);
            pcPosWords = 0;
            block = fetchResult.block;
            block.poller.spinCount = 0;
            assert prev != block : "\n" + prev + "\n" + block;
            assert block.poller.spinCount == 0 : "\n" + prev + "\n" + block;
        } else {
            //only gets here when jumping inside a block
            assert pc != block.prefetchPc;
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

    @Override
    public int fetchDelaySlot(int pc, Sh2.FetchResult ft, CpuDeviceAccess cpu) {
        if (!sh2Config.prefetchEn) {
            return memory.read(pc, Size.WORD);
        }
        final Sh2Block block = ft.block;
        final int pcDeltaWords = (pc - block.prefetchPc) >> 1;
        assert pcDeltaWords < block.prefetchLenWords && pcDeltaWords >= 0;
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
        int res = block.prefetchWords[pcDeltaWords];
        cacheOnFetch(pc, res, cpu);
        return res;
    }

    @Deprecated
    public int fetchDelaySlotOld(int pc, Sh2.FetchResult ft, CpuDeviceAccess cpu) {
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
        checkPoller(cpuWrite, SysEvent.SDRAM, addr, val, size);
        if (pcAreaMaskMap[addr >>> SH2_PC_AREA_SHIFT] == 0) return;
        switch (size) {
            case WORD, BYTE -> dataWriteWord(cpuWrite, addr, val, size);
            case LONG -> {
                dataWriteWord(cpuWrite, addr, val >> 16, Size.WORD);
                dataWriteWord(cpuWrite, addr + 2, val & 0xFFFF, Size.WORD);
            }
        }
    }

    public static void checkPoller(CpuDeviceAccess cpuWrite, S32xDict.S32xRegType type, int addr, int val, Size size) {
        checkPoller(cpuWrite, SysEvent.valueOf(type.name()), addr, val, size);
    }

    public static void checkPollers(S32xDict.S32xRegType type, int addr, int val, Size size) {
        checkPoller(MASTER, SysEvent.valueOf(type.name()), addr, val, size);
        checkPoller(SLAVE, SysEvent.valueOf(type.name()), addr, val, size);
    }

    public static void checkPoller(CpuDeviceAccess cpuWrite, SysEvent type, int addr, int val, Size size) {
        int res = SysEventManager.instance.anyPollerActive();
        if (res == 0) {
            return;
        }
        if ((res & 1) > 0) {
            checkPollerInternal(SysEventManager.instance.getPoller(MASTER), cpuWrite, type, addr, val, size);
        }
        if ((res & 2) > 0) {
            checkPollerInternal(SysEventManager.instance.getPoller(SLAVE), cpuWrite, type, addr, val, size);
        }
    }

    public static void checkPollerInternal(PollerCtx c, CpuDeviceAccess cpuWrite, SysEvent type,
                                           int addr, int val, Size size) {
        if (c.isPollingBusyLoop() || type != c.event) {
            return;
        }
        addr = addr & 0xFFF_FFFF;
        int tgtStart = (c.blockPollData.memLoadTarget & 0xFFF_FFFF);
        int tgtEnd = tgtStart + c.blockPollData.memLoadTargetSize.getByteSize();
        int addrEnd = addr + size.getByteSize();
        if ((addrEnd > tgtStart) && (addr < tgtEnd)) {
            if (verbose)
                LOG.info("{} Poll write addr: {} {}, target: {} {} {}, val: {}", cpuWrite,
                        th(addr), size, c.cpu, th(c.blockPollData.memLoadTarget),
                        c.blockPollData.memLoadTargetSize, th(val));
            SysEventManager.instance.fireSysEvent(c.cpu, type);
        }
    }

    public void dataWriteWord(CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        boolean isCacheArray = addr >>> SH2_PC_AREA_SHIFT == 0xC0;
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
        int end = addr + size.getByteSize();
        invalidateMemoryRegion(writer, blockOwner, addr, end, val, false, size);
    }

    private void invalidateMemoryRegion(CpuDeviceAccess writer, CpuDeviceAccess blockOwner,
                                        int addr, int end, int val, boolean cacheOnly, Size size) {
        boolean ignore = addr >>> SH2_PC_AREA_SHIFT > 0xC0;
        if (ignore) {
            return;
        }
        Sh2PcInfoWrapper pcInfoWrapper = SH2_NOT_VISITED;
        final int addrEven = addr & ~1;
        //find closest block
        for (int i = addrEven; i > addrEven - SH2_DRC_MAX_BLOCK_LEN; i -= 2) {
            Sh2PcInfoWrapper piw = Sh2Helper.get(addrEven, blockOwner);
            if (piw == null || piw == SH2_NOT_VISITED || piw.block == Sh2Block.INVALID_BLOCK) {
                continue;
            }
            final Sh2Block b = piw.block;
            var range = Range.closed(b.prefetchPc, b.prefetchPc + (b.prefetchLenWords << 1));
            if (range.contains(addr) || range.contains(end)) {
                pcInfoWrapper = piw;
            }
            break;
        }
        if (pcInfoWrapper != SH2_NOT_VISITED) {
            if (cacheOnly && !pcInfoWrapper.block.isCacheFetch()) {
                return;
            }
            final Sh2Block block = pcInfoWrapper.block;
            assert block != Sh2Block.INVALID_BLOCK;
            assert size != Size.LONG;
            //cosmic carnage
            int prev = block.prefetchWords[((addr - block.prefetchPc) >> 1)];
            if (prev == val) {
                return;
            }
            if (verbose) {
                String s = LogHelper.formatMessage(
                        "{} write at addr: {} val: {} {}, {} invalidate block with start: {} blockLen: {}",
                        writer, th(addr), th(val), size, blockOwner, th(pcInfoWrapper.block.prefetchPc),
                        pcInfoWrapper.block.prefetchLenWords);
                LOG.info(s);
            }
            //motocross byte, vf word
            invalidateBlock(pcInfoWrapper);
        }
    }

    private void invalidateBlock(Sh2PcInfoWrapper piw) {
        Sh2Block b = piw.block;
        boolean isCacheArray = b.prefetchPc >>> SH2_PC_AREA_SHIFT == 0xC0;
        if (ENABLE_BLOCK_RECYCLING && isCacheArray) {
            assert b.getCpu() != null;
            piw.addToKnownBlocks(b);
        }
        piw.invalidateBlock();
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
//            prefetchMap[cpu.ordinal()].clear();
//            if (verbose) LOG.info("{} invalidate all prefetch data", cpu);
        }
    }

    public void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx) {
        invalidateMemoryRegion(ctx.cpu, ctx.cpu, ctx.prevCacheAddr, ctx.prevCacheAddr + 16, 0, true, null);
    }

    //TODO this should be test only, move it somewhere else
    public List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int addr) {
        List<Sh2Block> l = new ArrayList<>();
//        for (var entry : prefetchMap[cpu.ordinal()].entrySet()) {
//            Sh2Block b = entry.getValue();
//            if (b != null) {
//                int start = b.prefetchPc;
//                int end = b.prefetchPc + (b.prefetchLenWords << 1);
//                if (addr >= start && addr < end) {
//                    l.add(b);
//                }
//            }
//        }
        return l;
    }

    long cnt = 0;

    @Override
    public void newFrame() {
        if (SH2_LOG_PC_HITS && (++cnt & 0x2FF) == 0) {
            logPcHits(MASTER);
            logPcHits(SLAVE);
        }
    }

    private void logPcHits(CpuDeviceAccess cpu) {
        Map<PcInfoWrapper, Long> hitMap = new HashMap<>();
        long top10 = 10;
        Sh2PcInfoWrapper[][] pcInfoWrapper = Sh2Helper.getPcInfoWrapper();
        for (int i = 0; i < pcInfoWrapper.length; i++) {
            for (int j = 0; j < pcInfoWrapper[i].length; j++) {
                Sh2PcInfoWrapper piw = pcInfoWrapper[i][j | cpu.ordinal()];
                if (piw != SH2_NOT_VISITED) {
                    if (piw.block.hits < top10) {
                        continue;
                    }
                    hitMap.put(piw, Long.valueOf(piw.block.hits));
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
            int pc = (piw.area << SH2_PC_AREA_SHIFT) | piw.pcMasked;
            //TODO fix
            Sh2Block res = Sh2Block.INVALID_BLOCK;
//            Sh2Block res = prefetchMap[cpu.ordinal()].getOrDefault(piw, Sh2Block.INVALID_BLOCK);
            //TODO fix
            assert res != Sh2Block.INVALID_BLOCK;
            sb.append(cpu + " " + th(pc) + "," + e.getValue() + ", block: " +
                    th(res.prefetchPc) + "," + res.pollType + "," + res.hits + "\n" + Sh2Helper.toListOfInst(res)).append("\n");

        });
//            String s = hitMap.entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).
//                    limit(10).map(Objects::toString).collect(Collectors.joining("\n"));
        LOG.info(sb.toString());
    }
}