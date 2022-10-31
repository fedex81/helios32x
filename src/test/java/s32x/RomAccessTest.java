package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil;

import static s32x.MarsRegTestUtil.readBus;
import static s32x.MarsRegTestUtil.setRv;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class RomAccessTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;
    private byte[] rom;

    @BeforeEach
    public void before() {
        rom = new byte[0x1000];
        MarsRegTestUtil.fillAsMdRom(rom, true);
        lc = MarsRegTestUtil.createTestInstance(rom);
        lc.s32XMMREG.aden = 1;
    }

    @Test
    public void testMdAccess() {
        testMdAccessInternal(M68K);
        testMdAccessInternal(Z80);
    }

    private void testMdAccessInternal(S32xUtil.CpuDeviceAccess cpu) {
        int res;
        setRv(lc, 0);
        res = readBus(lc, cpu, M68K_START_ROM_MIRROR + 0x200, Size.BYTE);
        //random values are guaranteed not be 0 or 0xFF
        Assertions.assertTrue(res != 0 && res != 0xFF);

        res = readBus(lc, cpu, M68K_START_ROM_MIRROR_BANK + 0x202, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);

        res = readBus(lc, cpu, 0x208, Size.BYTE);
        Assertions.assertTrue(res == 0 || res == 0xFF);

        setRv(lc, 1);
        res = readBus(lc, cpu, M68K_START_ROM_MIRROR + 0x204, Size.BYTE);
        Assertions.assertTrue(res == 0 || res == 0xFF);

        res = readBus(lc, cpu, M68K_START_ROM_MIRROR_BANK + 0x206, Size.BYTE);
        Assertions.assertTrue(res == 0 || res == 0xFF);

        res = readBus(lc, cpu, 0x20a, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);
    }

    @Test
    public void testSh2Access() {
        int res;
        setRv(lc, 0);
        res = readBus(lc, MASTER, SH2_START_ROM + 0x200, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);
        res = readBus(lc, SLAVE, SH2_START_ROM + 0x200, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);

        setRv(lc, 1);
        //TODO should stall
        res = readBus(lc, MASTER, SH2_START_ROM + 0x200, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);
        res = readBus(lc, SLAVE, SH2_START_ROM + 0x200, Size.BYTE);
        Assertions.assertTrue(res != 0 && res != 0xFF);
    }

    @Test
    public void testSwitch() {
        testMdAccess();
        testSh2Access();
        testMdAccess();
        testSh2Access();
    }
}
