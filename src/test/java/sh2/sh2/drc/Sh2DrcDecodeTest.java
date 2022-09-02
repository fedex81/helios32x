package sh2.sh2.drc;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.IMemory;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.prefetch.Sh2CacheTest;

import java.nio.ByteBuffer;
import java.util.List;

import static omegadrive.util.Util.th;
import static s32x.MarsRegTestUtil.createTestInstance;
import static sh2.dict.S32xDict.SH2_START_ROM;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2DrcDecodeTest {

    static {
//        System.setProperty("helios.32x.sh2.drc.stage1.hits", "1");
//        System.setProperty("helios.32x.sh2.drc.stage2.hits", "1");
    }

    private MarsLauncherHelper.Sh2LaunchContext lc;
    private Sh2 sh2;
    private Sh2Context masterCtx;
    private ByteBuffer rom;
    private int pc = 0x100;

    //2 blocks:  the 2nd block jumps back to the start of the 1st
    private static int[] trace1 = {
            Sh2CacheTest.NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            Sh2CacheTest.NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFF9, //A: BRA 0
            Sh2CacheTest.NOP, //C
    };

    //2 blocks:  the 2nd block jumps back to the middle of the 1st
    //this generates 3 blocks
    private static int[] trace2 = {
            Sh2CacheTest.NOP, //0
            Sh2CacheTest.SETT, //2
            0xA000, //4: BRA 8
            Sh2CacheTest.NOP, //6
            Sh2CacheTest.CLRMAC, //8
            0xAFFA, //A: BRA 2
            Sh2CacheTest.NOP, //C
    };

    @BeforeEach
    public void before() {
        lc = createTestInstance();
        IMemory.MemoryDataCtx mdc = lc.memory.getMemoryDataCtx();
        int sp = mdc.rom.capacity() - 4;
        ByteBuffer bios = mdc.bios[CpuDeviceAccess.MASTER.ordinal()].buffer;
        bios.putInt(0, SH2_START_ROM | pc);
        bios.putInt(4, SH2_START_ROM | sp);
        rom = mdc.rom;
        sh2 = lc.sh2;
        masterCtx = lc.masterCtx;
    }

    @Test
    public void testTrace1() {
        System.out.println(Sh2.Sh2Config.instance.get());
        setTrace(trace1, masterCtx);
        triggerDrcBlocks();
        sh2.run(masterCtx);
    }

    @Test
    public void testTrace2() {
        System.out.println(Sh2.Sh2Config.instance.get());
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
            List<Sh2Block> l = lc.memory.getPrefetchBlocksAt(cpu, pc);
            if (l.size() > 0 && l.get(0) != Sh2Block.INVALID_BLOCK) {
                blockTable.put(cpu, pc, l.get(0));
                System.out.println(cpu + " Detected block: " + th(pc));
            }
        }
        boolean atLeastOneNoDrc = blockTable.row(cpu).values().stream().anyMatch(b -> b.stage2Drc == null);
        return !atLeastOneNoDrc;
    }
}
