package s32x.sh2.prefetch;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.slf4j.Logger;
import s32x.bus.Sh2Bus;
import s32x.dict.S32xDict;
import s32x.dict.S32xMemAccessDelay;
import s32x.event.SysEventManager;
import s32x.sh2.*;
import s32x.sh2.cache.Sh2Cache;
import s32x.sh2.drc.Ow2DrcOptimizer;
import s32x.sh2.drc.Sh2Block;
import s32x.util.BiosHolder;
import s32x.util.Md32xRuntimeData;
import s32x.util.S32xUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static omegadrive.util.Util.readBufferWord;
import static omegadrive.util.Util.th;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogHelper.getLogger(Sh2Prefetch.class.getSimpleName());
    private final static boolean ENABLE_BLOCK_RECYCLING = true;
    private static final boolean SH2_LOG_PC_HITS = false;
    public static final int PC_CACHE_AREA_SHIFT = 28;

    private static final boolean verbose = false;
    private static final boolean collectStats = verbose || false;
    private final Stats[] stats = {new Stats(S32xUtil.CpuDeviceAccess.MASTER), new Stats(S32xUtil.CpuDeviceAccess.SLAVE)};

    private final Sh2Bus memory;
    private final Sh2Cache[] cache;
    private final Sh2DrcContext[] sh2Context;
    private final Sh2.Sh2Config sh2Config;
    private final int[] opcodeWords;

    public final int romSize, romMask;
    public final BiosHolder.BiosData[] bios;
    public final ByteBuffer sdram;
    public final ByteBuffer rom;

    public static class Sh2DrcContext {
        public S32xUtil.CpuDeviceAccess cpu;
        public Sh2 sh2;
        public Sh2Context sh2Ctx;
        public Sh2Bus memory;
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

    public Sh2Prefetch(Sh2Bus memory, Sh2Cache[] cache, Sh2DrcContext[] sh2Ctx) {
        this.cache = cache;
        this.memory = memory;
        this.sh2Context = sh2Ctx;
        Sh2Bus.MemoryDataCtx mdc = memory.getMemoryDataCtx();
        romMask = mdc.romMask;
        romSize = mdc.romSize;
        sdram = mdc.sdram;
        rom = mdc.rom;
        bios = mdc.bios;
        opcodeWords = new int[Sh2Block.MAX_INST_LEN];
        sh2Config = Sh2.Sh2Config.get();
    }

    private Sh2Block doPrefetch(Sh2Helper.Sh2PcInfoWrapper piw, int pc, S32xUtil.CpuDeviceAccess cpu) {
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
        block.stage1(Sh2Instructions.generateInst(block.prefetchWords));
        if (verbose) LOG.info("{} prefetch block at pc: {}, len: {}\n{}", cpu,
                th(pc), block.prefetchLenWords, Sh2Helper.toListOfInst(block));
        return block;
    }

    private Sh2Block doPrefetchInternal(int pc, S32xUtil.CpuDeviceAccess cpu) {
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
        assert wordsCount > 0 && wordsCount <= opcodeWords.length;
        block.prefetchLenWords = wordsCount;
        block.prefetchWords = Arrays.copyOf(opcodeWords, wordsCount);
        block.hashCodeWords = Arrays.hashCode(block.prefetchWords);
        block.end = block.start + ((block.prefetchLenWords - 1) << 1);
        return block;
    }

    private int fillOpcodes(S32xUtil.CpuDeviceAccess cpu, int pc, Sh2Block block) {
        return fillOpcodes(cpu, pc, block.start, block.fetchBuffer, block, opcodeWords);
    }

    private int fillOpcodes(S32xUtil.CpuDeviceAccess cpu, int pc, int blockStart, ByteBuffer fetchBuffer,
                            Sh2Block block, int[] opcodeWords) {
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final int pcLimit = pc + Sh2Block.SH2_DRC_MAX_BLOCK_LEN_BYTES - 2;
        final boolean isCache = (pc >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        final Sh2Instructions.Sh2InstructionWrapper[] op = Sh2Instructions.instOpcodeMap;
        boolean breakOnJump = false;
        int wordsCount = 0;
        int bytePos = blockStart;
        int currentPc = pc;
        do {
            int val = isCache ? sh2Cache.readDirect(currentPc, Size.WORD) : readBufferWord(fetchBuffer, bytePos) & 0xFFFF;
            final Sh2Instructions.Sh2BaseInstruction inst = op[val].inst;
            opcodeWords[wordsCount++] = val;
            if (inst.isIllegal) {
                LOG.error("{} Invalid fetch, start PC: {}, current: {} opcode: {}", cpu, th(pc), th(bytePos), th(val));
                break;
            }
            if (inst.isBranch) {
                if (inst.isBranchDelaySlot) {
                    int nextVal = isCache ? sh2Cache.readDirect(currentPc + 2, Size.WORD) :
                            readBufferWord(fetchBuffer, bytePos + 2) & 0xFFFF;
                    opcodeWords[wordsCount++] = nextVal;
                    assert Arrays.binarySearch(Sh2Instructions.illegalSlotOpcodes,
                            Sh2Instructions.instOpcodeMap[nextVal].inst) < 0;
                }
                breakOnJump = true;
                break;
            }
            bytePos += 2;
            currentPc += 2;
        } while (currentPc < pcLimit);
        assert currentPc == pcLimit ? !breakOnJump : true; //TODO test
        block.setNoJump(currentPc == pcLimit && !breakOnJump);
        return wordsCount;
    }

    private void setupPrefetch(final Sh2Block block, S32xUtil.CpuDeviceAccess cpu) {
        final int pc = block.prefetchPc;
        block.start = pc & 0xFF_FFFF;
        switch (pc >> S32xDict.SH2_PC_AREA_SHIFT) {
            case 6:
            case 0x26:
                block.start = Math.max(0, block.start) & S32xDict.SH2_SDRAM_MASK;
                block.pcMasked = pc & S32xDict.SH2_SDRAM_MASK;
                block.fetchMemAccessDelay = S32xMemAccessDelay.SDRAM;
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


    private void checkBlock(Sh2.FetchResult fetchResult, S32xUtil.CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        Sh2Helper.Sh2PcInfoWrapper piw = Sh2Helper.get(pc, cpu);
        assert piw != null;
        if (piw == Sh2Helper.SH2_NOT_VISITED) {
            piw = Sh2Helper.getOrCreate(pc, cpu);
        }
        if (piw.block != Sh2Block.INVALID_BLOCK && piw.block.isValid()) {
            assert fetchResult.pc == piw.block.prefetchPc : th(fetchResult.pc);
            piw.block.addHit();
            fetchResult.block = piw.block;
//            assert piw.block.isCacheFetch() ? cache[cpu.ordinal()].getCacheContext().cacheEn > 0 : true;
            return;
        }
        Sh2Block block = doPrefetch(piw, pc, cpu);
        Sh2Block prev = piw.block;
        assert prev != null;
        assert block != null && block.isValid();
        assert Sh2Helper.SH2_NOT_VISITED.block == Sh2Block.INVALID_BLOCK : cpu + "," + th(pc) + "," + Sh2Helper.SH2_NOT_VISITED.block;
        if (prev != Sh2Block.INVALID_BLOCK && !block.equals(prev)) {
            LOG.warn("{} New block generated at PC: {}\nPrev: {}\nNew : {}", cpu, th(pc), prev, block);
        }
        piw.setBlock(block);
        fetchResult.block = block;
    }

    @Override
    public void fetch(Sh2.FetchResult fetchResult, S32xUtil.CpuDeviceAccess cpu) {
        final int pc = fetchResult.pc;
        if (!sh2Config.prefetchEn) {
            fetchResult.opcode = memory.read(pc, Size.WORD);
            return;
        }
        final Sh2Block prev = fetchResult.block;
        boolean isPrevInvalid = !prev.isValid();
        assert fetchResult.pc != fetchResult.block.prefetchPc;
        checkBlock(fetchResult, cpu);
        final Sh2Block block = fetchResult.block;
        //block recycled, was invalid before
        assert (block == prev ? isPrevInvalid : true) : "\n" + block;
        block.poller.spinCount = 0;
        cacheOnFetch(pc, block.prefetchWords[0], cpu);
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
        assert block != Sh2Block.INVALID_BLOCK && block.prefetchWords != null && block.prefetchWords.length > 0;
        fetchResult.opcode = block.prefetchWords[0];
        assert fetchResult.block != null;
        return;
    }

    @Override
    public int fetchDelaySlot(int pc, Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
        if (!sh2Config.prefetchEn) {
            return memory.read(pc, Size.WORD);
        }
        final Sh2Block block = ft.block;
        //TODO test, if a block clears the cache, the block itself will be invalidated, leading to its PC becoming odd
        //TODO and breaking the fetching of the delaySlot, see Sangokushi blockPc = 0x200_6ACD
        int blockPc = block.prefetchPc;
        if (!block.isValid()) {
            blockPc &= ~1;
        }
        final int pcDeltaWords = (pc - blockPc) >> 1;
        assert pcDeltaWords < block.prefetchLenWords && pcDeltaWords >= 0 : th(pc) + "," + th(pcDeltaWords);
        if (collectStats) stats[cpu.ordinal()].pfTotal++;
        S32xMemAccessDelay.addReadCpuDelay(block.fetchMemAccessDelay);
        int res = block.prefetchWords[pcDeltaWords];
        cacheOnFetch(pc, res, cpu);
        return res;
    }

    @Deprecated
    public void dataWriteOld(S32xUtil.CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        if (Sh2Debug.pcAreaMaskMap[addr >>> S32xDict.SH2_PC_AREA_SHIFT] == 0) return;
        if (addr >= 0 && addr < 0x100) { //Doom res 2.2
            return;
        }
        boolean isCacheArray = addr >>> S32xDict.SH2_PC_AREA_SHIFT == 0xC0;
        boolean isWriteThrough = addr >>> PC_CACHE_AREA_SHIFT == 2;
        invalidateMemoryRegion(addr, cpuWrite, addr + size.getByteSize() - 1, val);
        //sh2 cacheArrays are not shared!
        if (!isCacheArray && isWriteThrough) {
            S32xUtil.CpuDeviceAccess otherCpu = S32xUtil.CpuDeviceAccess.cdaValues[(cpuWrite.ordinal() + 1) & 1];
            invalidateMemoryRegion(addr, otherCpu, addr + size.getByteSize() - 1, val);
        }
    }

    @Override
    public void dataWrite(S32xUtil.CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        if (Sh2Debug.pcAreaMaskMap[addr >>> S32xDict.SH2_PC_AREA_SHIFT] == 0) return;
        if (addr >= 0 && addr < 0x100) { //Doom res 2.2
            return;
        }
        invalidateMemoryRegion(addr, cpuWrite, addr + size.getByteSize() - 1, val);
    }

    public static void checkPoller(S32xUtil.CpuDeviceAccess cpuWrite, S32xDict.S32xRegType type, int addr, int val, Size size) {
        checkPoller(cpuWrite, SysEventManager.SysEvent.valueOf(type.name()), addr, val, size);
    }

    public static void checkPollersVdp(S32xDict.S32xRegType type, int addr, int val, Size size) {
        //cpuWrite doesn't apply here...
        checkPoller(null, SysEventManager.SysEvent.valueOf(type.name()), addr, val, size);
    }

    public static void checkPoller(S32xUtil.CpuDeviceAccess cpuWrite, SysEventManager.SysEvent type, int addr, int val, Size size) {
        int res = SysEventManager.instance.anyPollerActive();
        if (res == 0) {
            return;
        }
        if ((res & 1) > 0) {
            Ow2DrcOptimizer.PollerCtx c = SysEventManager.instance.getPoller(S32xUtil.CpuDeviceAccess.MASTER);
            if (c.isPollingActive() && type == c.event) {
                checkPollerInternal(c, cpuWrite, type, addr, val, size);
            }
        }
        if ((res & 2) > 0) {
            Ow2DrcOptimizer.PollerCtx c = SysEventManager.instance.getPoller(S32xUtil.CpuDeviceAccess.SLAVE);
            if (c.isPollingActive() && type == c.event) {
                checkPollerInternal(c, cpuWrite, type, addr, val, size);
            }
        }
    }

    private static void checkPollerInternal(Ow2DrcOptimizer.PollerCtx c, S32xUtil.CpuDeviceAccess cpuWrite, SysEventManager.SysEvent type,
                                            int addr, int val, Size size) {
        final Ow2DrcOptimizer.BlockPollData bpd = c.blockPollData;
        //TODO check, cache vs cache-through
        addr = addr & S32xDict.SH2_CACHE_THROUGH_MASK;
        if (rangeIntersect(bpd.memLoadTarget & S32xDict.SH2_CACHE_THROUGH_MASK,
                bpd.memLoadTargetEnd & S32xDict.SH2_CACHE_THROUGH_MASK, addr, addr + size.getByteSize() - 1)) {
            if (verbose)
                LOG.info("{} Poll write addr: {} {}, target: {} {} {}, val: {}", cpuWrite,
                        th(addr), size, c.cpu, th(c.blockPollData.memLoadTarget),
                        c.blockPollData.memLoadTargetSize, th(val));
//            if(c.pollValue != val) {
            SysEventManager.instance.fireSysEvent(c.cpu, type);
//            }
        }
    }

    private void invalidateMemoryRegion(final int addr, final S32xUtil.CpuDeviceAccess blockOwner, final int wend, final int val) {
        boolean ignore = addr >>> S32xDict.SH2_PC_AREA_SHIFT > 0xC0;
        if (ignore) {
            return;
        }
        final S32xUtil.CpuDeviceAccess otherCpu = S32xUtil.CpuDeviceAccess.cdaValues[(blockOwner.ordinal() + 1) & 1];
        final boolean isWriteThrough = addr >>> PC_CACHE_AREA_SHIFT == 2;
        final int addrEven = (addr & ~1);
        //find closest block, long requires starting at +2
        for (int i = addrEven + 2; i > addrEven - Sh2Block.SH2_DRC_MAX_BLOCK_LEN_BYTES; i -= 2) {
            Sh2Helper.Sh2PcInfoWrapper piw = Sh2Helper.getOrDefault(i, blockOwner);
            assert piw != null;
            if (piw == Sh2Helper.SH2_NOT_VISITED || !piw.block.isValid()) {
                continue;
            }
            final Sh2Block b = piw.block;
            final int bend = b.prefetchPc + ((b.prefetchLenWords - 1) << 1); //inclusive
            if (rangeIntersect(b.prefetchPc, bend, addr, wend)) {
                invalidateMemoryLocationForCpu(blockOwner, piw, addr, i, val);
                if (isWriteThrough) {
                    invalidateMemoryLocationForCpu(otherCpu, addr, i, val);
                }
            }
            //TODO slow, enabling this needs the nested block attribute implemented
//            break;
        }
    }

    @Deprecated
    private void invalidateMemoryRegionOld(final int addr, final S32xUtil.CpuDeviceAccess blockOwner, final int wend, final int val) {
        boolean ignore = addr >>> S32xDict.SH2_PC_AREA_SHIFT > 0xC0;
        if (ignore) {
            return;
        }
        final int addrEven = (addr & ~1);
        //find closest block, long requires starting at +2
        for (int i = addrEven + 2; i > addrEven - Sh2Block.SH2_DRC_MAX_BLOCK_LEN_BYTES; i -= 2) {
            Sh2Helper.Sh2PcInfoWrapper piw = Sh2Helper.getOrDefault(i, blockOwner);
            assert piw != null;
            if (piw == Sh2Helper.SH2_NOT_VISITED || !piw.block.isValid()) {
                continue;
            }
            final Sh2Block b = piw.block;
            final int bend = b.prefetchPc + ((b.prefetchLenWords - 1) << 1); //inclusive
            if (rangeIntersect(b.prefetchPc, bend, addr, wend)) {
                invalidateWrapper(addr, piw, false, val);
            }
        }
    }

    private void invalidateMemoryLocationForCpu(S32xUtil.CpuDeviceAccess cpu, Sh2Helper.Sh2PcInfoWrapper piw, int addr, int i, int val) {
        final boolean isCpuCacheOff = cache[cpu.ordinal()].getCacheContext().cacheEn == 0;
        if (piw != Sh2Helper.SH2_NOT_VISITED && piw.block.isValid()) {
            invalidateWrapper(addr, piw, false, val);
        }
        if (isCpuCacheOff) {
            piw = Sh2Helper.getOrDefault(i & S32xDict.SH2_CACHE_THROUGH_MASK, cpu);
            if (piw != Sh2Helper.SH2_NOT_VISITED && piw.block.isValid()) {
                invalidateWrapper(addr, piw, false, val);
            }
        }
    }

    private void invalidateMemoryLocationForCpu(S32xUtil.CpuDeviceAccess cpu, int addr, int i, int val) {
        invalidateMemoryLocationForCpu(cpu, Sh2Helper.getOrDefault(i, cpu), addr, i, val);
    }

    static final int RANGE_MASK = 0xFFF_FFFF;

    /**
     * Assumes closed ranges, does not work for the general case (32bit signed integer); use only for small ranges
     * [r1s, r1e] intersect [r2s, r2e]
     */
    public static boolean rangeIntersect(int r1start, int r1end, int r2start, int r2end) {
        return !(((r1start & RANGE_MASK) > (r2end & RANGE_MASK)) || ((r1end & RANGE_MASK) < (r2start & RANGE_MASK)));
    }

    private void invalidateWrapperFromCache(final int addr, final Sh2Helper.Sh2PcInfoWrapper pcInfoWrapper) {
        invalidateWrapper(addr, pcInfoWrapper, true, -1);
    }

    private void invalidateWrapper(final int addr, final Sh2Helper.Sh2PcInfoWrapper pcInfoWrapper, final boolean cacheOnly, final int val) {
        assert pcInfoWrapper != Sh2Helper.SH2_NOT_VISITED;
        if (cacheOnly && !pcInfoWrapper.block.isCacheFetch()) {
            return;
        }
        final Sh2Block block = pcInfoWrapper.block;
        assert block != Sh2Block.INVALID_BLOCK;
        //TODO check not needed anymore
//        if (!cacheOnly) {
//            //cosmic carnage
//            int prev = block.prefetchWords[((addr - block.prefetchPc) >> 1)];
//            if (prev == val) {
//                return;
//            }
//        }
        if (verbose) {
            String s = LogHelper.formatMessage(
                    "{} write at addr: {} val: {}, {} invalidate block with start: {} blockLen: {}",
                    Md32xRuntimeData.getAccessTypeExt(), th(addr), th(val),
                    pcInfoWrapper.block.drcContext.cpu, Util.th(pcInfoWrapper.block.prefetchPc),
                    pcInfoWrapper.block.prefetchLenWords);
            LOG.info(s);
        }
        //motocross byte, vf word
        invalidateBlock(pcInfoWrapper);
    }

    private void invalidateBlock(Sh2Helper.Sh2PcInfoWrapper piw) {
        Sh2Block b = piw.block;
        boolean isCacheArray = b.prefetchPc >>> S32xDict.SH2_PC_AREA_SHIFT == 0xC0;
        //TODO Blackthorne lots of SDRAM invalidation, does removing (&& isCacheArray) help?
        if (ENABLE_BLOCK_RECYCLING) {// && isCacheArray) {
            assert b.getCpu() != null;
            piw.addToKnownBlocks(b);
        }
        piw.invalidateBlock();
    }

    private void cacheOnFetch(int pc, int expOpcode, S32xUtil.CpuDeviceAccess cpu) {
        boolean isCache = pc >>> PC_CACHE_AREA_SHIFT == 0;
        if (isCache && cache[cpu.ordinal()].getCacheContext().cacheEn > 0) {
            //NOTE necessary to trigger the cache hit on fetch
            int cached = cache[cpu.ordinal()].cacheMemoryRead(pc, Size.WORD);
            assert (cached & 0xFFFF) == expOpcode : th(pc) + "," + th(expOpcode) + "," + th(cached);
        }
    }

    //TODO should we check cache contents vs SDRAM to detect inconsistencies?
    //TODO test
    public void invalidateCachePrefetch(Sh2Cache.CacheInvalidateContext ctx) {
        int addr = ctx.prevCacheAddr;
        if (addr >= 0 && addr < 0x100) { //Sangokushi
            return;
        }
        int end = ctx.prevCacheAddr + Sh2Cache.CACHE_BYTES_PER_LINE;
        boolean ignore = addr >>> S32xDict.SH2_PC_AREA_SHIFT > 0xC0;
        if (ignore) {
            return;
        }
        final int addrEven = end;
        for (int i = addrEven; i > addr - Sh2Block.SH2_DRC_MAX_BLOCK_LEN_BYTES; i -= 2) {
            Sh2Helper.Sh2PcInfoWrapper piw = Sh2Helper.getOrDefault(i, ctx.cpu);
            assert piw != null;
            if (piw == Sh2Helper.SH2_NOT_VISITED || !piw.block.isValid()) {
                continue;
            }
            invalidateWrapper(i, piw, true, -1);
        }
    }

    long cnt = 0;

    @Override
    public void newFrame() {
        if (SH2_LOG_PC_HITS && (++cnt & 0x2FF) == 0) {
            PrefetchUtil.logPcHits(S32xUtil.CpuDeviceAccess.MASTER);
            PrefetchUtil.logPcHits(S32xUtil.CpuDeviceAccess.SLAVE);
        }
    }
}