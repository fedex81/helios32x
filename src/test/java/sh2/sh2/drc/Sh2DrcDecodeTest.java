package sh2.sh2.drc;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh2.IMemory;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2MultiTestBase;
import sh2.sh2.prefetch.Sh2CacheTest;
import sh2.sh2.prefetch.Sh2PrefetchTest;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.SH2_START_ROM;
import static sh2.sh2.prefetch.Sh2CacheTest.NOP;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Sh2DrcDecodeTest extends Sh2MultiTestBase {
    private int pc = 0x100;

    //2 blocks:  the 2nd block jumps back to the start of the 1st
    private static int[] trace1 = {
            NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFF9, //A: BRA 0
            NOP, //C
    };

    //2 blocks:  the 2nd block jumps back to the middle of the 1st
    //this generates 3 blocks
    private static int[] trace2 = {
            NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFFA, //A: BRA 2
            NOP, //C
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
            testTrace1();
            testTrace2();
        };
        r.run();
        r.run();
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

    private void testTrace1() {
        setTrace(trace1, masterCtx);
        triggerDrcBlocks();
        sh2.run(masterCtx);
    }

    private void testTrace2() {
        setTrace(trace2, masterCtx);
        triggerDrcBlocks();
        sh2.run(masterCtx);
    }

    private void triggerDrcBlocks() {
        boolean stop = false;
        int maxSpin = 0x1000;
        int spin = 0;
        do {
            sh2.run(masterCtx);
            spin++;
            stop = allBlocksDrc(masterCtx) || spin > maxSpin;
        } while (!stop);
        Assertions.assertFalse(spin > maxSpin);
    }

    private void setTrace(int[] trace, Sh2Context context) {
        for (int i = 0; i < trace.length; i++) {
            rom.putShort(pc + (i << 1), (short) trace[i]);
        }
        sh2.reset(masterCtx);
    }

    private Table<CpuDeviceAccess, Integer, Sh2Block> blockTable = HashBasedTable.create();

    private boolean allBlocksDrc(Sh2Context sh2Context) {
        int pc = sh2Context.PC;
        CpuDeviceAccess cpu = sh2Context.cpuAccess;
        if (!blockTable.contains(cpu, pc)) {
            Collection<Sh2Block> l = Sh2PrefetchTest.getPrefetchBlocksAt(cpu, pc);
            Sh2Block b = l.stream().findFirst().orElse(Sh2Block.INVALID_BLOCK);
            if (b != Sh2Block.INVALID_BLOCK) {
                blockTable.put(cpu, pc, b);
                System.out.println(cpu + " Detected block: " + th(pc));
            }
        }
        boolean atLeastOneNoDrc = blockTable.row(cpu).values().stream().anyMatch(b -> b.stage2Drc == null);
        return !atLeastOneNoDrc;
    }
}
