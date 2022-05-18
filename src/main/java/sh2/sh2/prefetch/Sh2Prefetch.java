package sh2.sh2.prefetch;

import com.google.common.primitives.Ints;
import omegadrive.cpu.CpuFastDebug.PcInfoWrapper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.objectweb.asm.commons.LocalVariablesSorter;
import sh2.IMemory;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.*;
import sh2.sh2.Sh2.FetchResult;
import sh2.sh2.Sh2Instructions.Sh2InstructionWrapper;
import sh2.sh2.cache.Sh2Cache;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static omegadrive.cpu.CpuFastDebug.NOT_VISITED;
import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.Sh2Memory.CACHE_THROUGH_OFFSET;
import static sh2.Sh2Memory.SDRAM_MASK;
import static sh2.dict.S32xMemAccessDelay.SDRAM;
import static sh2.sh2.Sh2Debug.PC_AREA_SHIFT;
import static sh2.sh2.Sh2Debug.pcAreaMaskMap;
import static sh2.sh2.Sh2Instructions.generateInst;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Sh2Prefetch implements Sh2Prefetcher {

    private static final Logger LOG = LogManager.getLogger(Sh2Prefetch.class.getSimpleName());

    private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));

    private static final boolean SH2_REUSE_FETCH_DATA = true; //TODO vr,vf require false
    private static final boolean SH2_LIMIT_BLOCK_MAP_LOAD = true;
    private static final int INITIAL_BLOCK_LIMIT = 50;

    private static final boolean verbose = false;
    private static final boolean collectStats = verbose || false;

    private Map<PcInfoWrapper, Sh2Block>[] prefetchMap = new Map[]{new HashMap<PcInfoWrapper, Sh2Block>(), new HashMap<PcInfoWrapper, Sh2Block>()};
    private final Object[] pcInfoWrapperMS = new Object[2];

    private Stats[] stats = {new Stats(MASTER), new Stats(SLAVE)};

    private final IMemory memory;
    private final Sh2Cache[] cache;
    private final Sh2DrcContext[] sh2Context;

    public final int romSize, romMask;
    public final ByteBuffer[] bios;
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
        public int opcode, pc;
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
    }

    private Sh2Block doPrefetch(int pc, CpuDeviceAccess cpu) {
        final Sh2Block block = new Sh2Block();
        final Sh2Cache sh2Cache = cache[cpu.ordinal()];
        final boolean isCache = (pc >>> 28) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        block.isCacheFetch = isCache;
        block.prefetchPc = pc;
        block.drcContext = this.sh2Context[cpu.ordinal()];
        setupPrefetch(block, cpu);
        final PcInfoWrapper piw = getOrCreate(pc, cpu);
        int bytePos = block.start;
        int currentPc = pc;
        if (verbose) LOG.info("{} prefetch @ pc: {}", cpu, th(pc));
        opcodes.clear();
        final Sh2InstructionWrapper[] op = Sh2Instructions.instOpcodeMap;
        do {
            int val = isCache ? sh2Cache.cacheMemoryRead(currentPc, Size.WORD) :
                    block.fetchBuffer.getShort(bytePos) & 0xFFFF;
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
                    int nextVal = isCache ? sh2Cache.cacheMemoryRead((currentPc + 2), Size.WORD) :
                            block.fetchBuffer.getShort(bytePos + 2) & 0xFFFF;
                    opcodes.add(Util.getFromIntegerCache(nextVal));
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
        switch (pc >> 24) {
            case 6:
            case 0x26:
                block.start = Math.max(0, block.start) & SDRAM_MASK;
                block.pcMasked = pc & SDRAM_MASK;
                block.memAccessDelay = SDRAM;
                block.fetchBuffer = sdram;
                break;
            case 2:
            case 0x22:
                block.start = Math.max(0, block.start) & romMask;
                block.pcMasked = pc & romMask;
                block.memAccessDelay = S32xMemAccessDelay.ROM;
                block.fetchBuffer = rom;
                break;
            case 0:
            case 0x20:
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
        PcInfoWrapper piw = get(pc, cpu);
        final Map<PcInfoWrapper, Sh2Block> pMap = prefetchMap[cpu.ordinal()];
        assert piw != null;
        if (piw != NOT_VISITED) {
            Sh2Block block = pMap.getOrDefault(piw, Sh2Block.INVALID_BLOCK);
            if (block != Sh2Block.INVALID_BLOCK) {
                assert fetchResult.pc == block.prefetchPc : th(fetchResult.pc);
                fetchResult.block = block;
                return;
            }
        }
        Sh2Block block = doPrefetch(pc, cpu);
        if (SH2_REUSE_FETCH_DATA) {
            piw = get(pc, cpu);
            Sh2Block prev = pMap.get(piw);
            if (SH2_LIMIT_BLOCK_MAP_LOAD) {
                handleMapLoad(pMap, cpu);
            }
            pMap.put(piw, block);
            if (prev != null && !block.equals(prev)) {
                LOG.warn("New block generated at PC: " + th(pc));
            }
        }
        assert block != null;
        fetchResult.block = block;
    }

    private PcInfoWrapper get(int pc, CpuDeviceAccess cpu) {
        PcInfoWrapper[] area = ((PcInfoWrapper[][]) pcInfoWrapperMS[cpu.ordinal()])[pc >>> PC_AREA_SHIFT];
//        assert (pc & pcAreaMaskMap[pc >>> PC_AREA_SHIFT]) == (pc & 0xFFFFFF) : th(pc) + "," + th(pcAreaMaskMap[pc >>> PC_AREA_SHIFT]);
        return area[pc & pcAreaMaskMap[pc >>> PC_AREA_SHIFT]];
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

    private void handleMapLoad(Map<PcInfoWrapper, Sh2Block> pMap, CpuDeviceAccess cpu) {
        if (pMap.size() > blockLimit) {
            int size = pMap.size();
            pMap.entrySet().removeIf(e -> e.getValue() == Sh2Block.INVALID_BLOCK || e.getValue().inst == null);
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
    public void dataWrite(CpuDeviceAccess cpuWrite, int addr, int val, Size size) {
        if (pcAreaMaskMap[addr >>> PC_AREA_SHIFT] == 0) return;

        boolean isCacheArray = addr >>> PC_AREA_SHIFT == 0xC0;
        boolean isWriteThrough = addr >>> 28 == 2;

        for (int i = 0; i <= SLAVE.ordinal(); i++) {
            //sh2 cacheArrays are not shared!
            if (isCacheArray && i != cpuWrite.ordinal()) {
                continue;
            }
            checkAddress(CpuDeviceAccess.cdaValues[i], addr, val, size);
            boolean isCacheEnabled = cache[i].getCacheContext().cacheEn > 0;
            if (!isCacheEnabled) {
                int otherAddr = isWriteThrough ? addr & 0xFFF_FFFF : addr | CACHE_THROUGH_OFFSET;
                checkAddress(CpuDeviceAccess.cdaValues[i], otherAddr, val, size);
            }
        }
    }

    private void checkAddress(CpuDeviceAccess cpu, int addr, int val, Size size) {
        PcInfoWrapper piw = get(addr, cpu);
        if (piw == null || piw == NOT_VISITED) {
            return;
        }
        for (var entry : prefetchMap[cpu.ordinal()].entrySet()) {
            Sh2Block b = entry.getValue();
            if (b != null) {
                int start = b.prefetchPc;
                int end = b.prefetchPc + (b.prefetchLenWords << 1);
                if (addr >= start && addr < end) {
                    if (size == Size.WORD) { //cosmic carnage
                        int prev = b.prefetchWords[((addr - start) >> 1)];
                        if (prev == val) {
                            return;
                        }
                    }
//                        if(verbose)
                    ParameterizedMessage pm = new ParameterizedMessage("{} write at addr: {} val: {} {}, invalidate {} block with start: {} blockLen: {}",
                            cpu, th(addr), th(val), size, cpu, th(b.prefetchPc), b.prefetchLenWords);
                    LOG.info(pm.getFormattedMessage());
                    System.out.println(pm.getFormattedMessage());
                    entry.getValue().invalidate();
                    entry.setValue(Sh2Block.INVALID_BLOCK);
                }
            }
            }
        }
}