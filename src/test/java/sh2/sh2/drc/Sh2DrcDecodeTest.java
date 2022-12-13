package sh2.sh2.drc;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Range;
import com.google.common.collect.Table;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh2.IMemory;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Helper;
import sh2.sh2.Sh2MultiTestBase;
import sh2.sh2.prefetch.Sh2CacheTest;
import sh2.sh2.prefetch.Sh2PrefetchTest;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.dict.S32xDict.SH2_START_ROM;
import static sh2.sh2.drc.Sh2Block.INVALID_BLOCK;
import static sh2.sh2.prefetch.Sh2CacheTest.NOP;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
@Disabled("fails in github")
public class Sh2DrcDecodeTest extends Sh2MultiTestBase {
    private static int pc = 0x100;

    //2 blocks:  the 2nd block jumps back to the start of the 1st
    public static int[] trace1 = {
            NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFF9, //A: BRA 0
            NOP, //C
    };

    public static Range[] trace1Ranges = {
            Range.closed(pc, pc + 6),
            Range.closed(pc + 8, pc + 0xC)
    };

    //2 blocks:  the 2nd block jumps back to the middle of the 1st
    //this generates 3 blocks
    public static int[] trace2 = {
            NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFFA, //A: BRA 2
            NOP, //C
    };

    public static Range[] trace2Ranges = {
            Range.closed(pc, pc + 6),
            Range.closed(pc + 2, pc + 6),
            Range.closed(pc + 8, pc + 0xC)
    };

    //2 blocks:  the 2nd block jumps back to the middle of the 1st
    //this generates 3 blocks, 2nd block is long-word aligned
    public static int[] trace3 = {
            NOP, //0
            NOP, //2
            Sh2CacheTest.SETT, //4
            0xA000, //6: BRA A
            NOP, //8
            Sh2CacheTest.CLRMAC, //A
            0xAFFA, //C: BRA 4
            NOP, //E
    };

    public static Range[] trace3Ranges = {
            Range.closed(pc, pc + 0x8),
            Range.closed(pc + 4, pc + 0x8),
            Range.closed(pc + 0xA, pc + 0xE)
    };

    static {
        config = configCacheEn;
    }

    protected static Stream<Sh2.Sh2Config> fileProvider() {
        return Arrays.stream(configList).filter(c -> c.prefetchEn && c.drcEn);
    }

    @ParameterizedTest
    @MethodSource("fileProvider")
    public void testDrc(Sh2.Sh2Config c) {
        System.out.println("Testing: " + c);
        Runnable r = () -> {
            resetCacheConfig(c);
            testTrace(trace1);
            testTrace(trace2);
            testTrace(trace3);
        };
        r.run();
        r.run();
    }

    @ParameterizedTest
    @MethodSource("fileProvider")
    public void testBlock(Sh2.Sh2Config c) {
        System.out.println("Testing: " + c);
        resetCacheConfig(c);
        testBlockInternal(c, trace1, trace1Ranges);
        resetCacheConfig(c);
        testBlockInternal(c, trace2, trace2Ranges);
        resetCacheConfig(c);
        testBlockInternal(c, trace3, trace3Ranges);
    }

    @Override
    @BeforeEach
    public void before() {
        super.before();
        IMemory.MemoryDataCtx mdc = lc.memory.getMemoryDataCtx();
        int sp = mdc.rom.capacity() - 4;
        ByteBuffer bios = mdc.bios[CpuDeviceAccess.MASTER.ordinal()].buffer;
        bios.putInt(0, SH2_START_ROM | pc);
        bios.putInt(4, SH2_START_ROM | sp);
    }

    private void testTrace(int[] trace) {
        setTrace(trace, masterCtx);
        triggerDrcBlocks(sh2, masterCtx);
        sh2.run(masterCtx);
    }

    private void testBlockInternal(Sh2.Sh2Config c, int[] trace, Range<Integer>[] blockRanges) {
        resetCacheConfig(c);
        setTrace(trace, masterCtx);

        sh2.run(masterCtx);
        sh2.run(masterCtx);
        sh2.run(masterCtx);

        for (var range : blockRanges) {
            int pc = range.lowerEndpoint();
            Sh2Helper.Sh2PcInfoWrapper wrapper = Sh2Helper.getOrDefault(SH2_START_ROM | pc, MASTER);
            checkWrapper(wrapper, SH2_START_ROM | pc, memory.romMask);
        }
    }

    public static void checkWrapper(Sh2Helper.Sh2PcInfoWrapper wrapper, int pc, int pcMask) {
        Assertions.assertEquals(pc >> 24, wrapper.area);
        Assertions.assertEquals(pc & pcMask, wrapper.pcMasked);
        Assertions.assertNotEquals(INVALID_BLOCK, wrapper.block);
        Assertions.assertEquals(pc & pcMask, wrapper.block.start);
        Assertions.assertEquals(pc, wrapper.block.prefetchPc);
        Assertions.assertTrue(wrapper.block.isValid());
    }

    public static void triggerDrcBlocks(Sh2 sh2, Sh2Context context) {
        blockTable.clear();
        boolean stop = false;
        int maxSpin = 0x1000;
        int spin = 0;
        do {
            sh2.run(context);
            spin++;
            stop = allBlocksDrc(context) || spin > maxSpin;
        } while (!stop);
        Assertions.assertFalse(spin > maxSpin);
    }

    private void setTrace(int[] trace, Sh2Context context) {
        for (int i = 0; i < trace.length; i++) {
            rom.putShort(pc + (i << 1), (short) trace[i]);
        }
        sh2.reset(context);
    }

    private static Table<CpuDeviceAccess, Integer, Sh2Block> blockTable = HashBasedTable.create();

    private static boolean allBlocksDrc(Sh2Context sh2Context) {
        int pc = sh2Context.PC;
        CpuDeviceAccess cpu = sh2Context.cpuAccess;
        if (!blockTable.contains(cpu, pc)) {
            Collection<Sh2Block> l = Sh2PrefetchTest.getPrefetchBlocksAt(cpu, pc);
            l.forEach(b -> {
                if (b != Sh2Block.INVALID_BLOCK) {
                    blockTable.put(cpu, pc, b);
                    System.out.println(cpu + " Detected block: " + th(pc));
                }
            });
        }
        boolean atLeastOneNoDrc = blockTable.row(cpu).values().stream().anyMatch(b -> b.stage2Drc == null);
        return !atLeastOneNoDrc;
    }
}
