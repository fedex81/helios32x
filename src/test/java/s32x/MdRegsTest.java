package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;

import static s32x.MarsRegTestUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.dict.S32xDict.M68K_START_32X_SYSREG;
import static sh2.dict.S32xDict.M68K_START_ROM_MIRROR_BANK;
import static sh2.dict.S32xDict.RegSpecS32x.MD_BANK_SET;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class MdRegsTest {

    private static final int mdBankSetAddr = M68K_START_32X_SYSREG | MD_BANK_SET.addr;
    private MarsLauncherHelper.Sh2LaunchContext lc;

    @BeforeEach
    public void before() {
        lc = createTestInstance();
    }

    @Test
    public void testBankSetAdenOff() {
        setAdenMdSide(false);
        testBankSetInternal();
    }

    @Test
    public void testBankSetAdenOn() {
        setAdenMdSide(true);
        testBankSetInternal();
    }

    //Golf game
    @Test
    public void testSramAccessViaBankedMirrors() {
        setAdenMdSide(true);
        int bankSet = readBus(lc, M68K, mdBankSetAddr, Size.WORD);
        Assertions.assertEquals(0, bankSet & 3);

        //write to ROM address 0, ignored
        int romWord = readBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, Size.WORD);
        writeBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, 0xAABB, Size.WORD);
        int res2 = readBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, Size.WORD);
        Assertions.assertEquals(romWord, res2);

        //set bank 20 0000h – 2F FFFFh at 90 0000h
        int sramVal = 0xCC;
        writeBus(lc, M68K, mdBankSetAddr, 2, Size.WORD);
        writeBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, sramVal, Size.WORD);
        int res = readBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, Size.WORD);
        Assertions.assertEquals(sramVal, res);
        Assertions.assertNotEquals(sramVal, romWord);

        //reset bank to 0
        writeBus(lc, M68K, mdBankSetAddr, 0, Size.WORD);
        res = readBus(lc, M68K, M68K_START_ROM_MIRROR_BANK, Size.WORD);
        Assertions.assertEquals(romWord, res);
    }

    private void testBankSetInternal() {
        for (int i = 0; i < 0x1_0000; i += 257) {
            writeBus(lc, M68K, mdBankSetAddr, i, Size.WORD);
            int res = readBus(lc, M68K, mdBankSetAddr, Size.WORD);
            Assertions.assertEquals(i & 3, res & 3);
            Assertions.assertEquals(i & 3, lc.bus.getBankSetValue() & 3);

            //NOTE Zaxxon uses byte access
            //ignore byte write to 0x4
            writeBus(lc, M68K, mdBankSetAddr, 0, Size.WORD);
            writeBus(lc, M68K, mdBankSetAddr, i, Size.BYTE);
            res = readBus(lc, M68K, mdBankSetAddr, Size.WORD);
            Assertions.assertEquals(0, res & 3);
            Assertions.assertEquals(0, lc.bus.getBankSetValue() & 3);

            //handle byte write to 0x5
            writeBus(lc, M68K, mdBankSetAddr, 0, Size.WORD);
            writeBus(lc, M68K, mdBankSetAddr + 1, i, Size.BYTE);
            res = readBus(lc, M68K, mdBankSetAddr, Size.WORD);
            Assertions.assertEquals(i & 3, res & 3);
            Assertions.assertEquals(i & 3, lc.bus.getBankSetValue() & 3);
        }
    }

    private void setAdenMdSide(boolean enable) {
        int val = enable ? 1 : 0;
        int md0 = readBus(lc, M68K, MD_ADAPTER_CTRL, Size.WORD);
        writeBus(lc, M68K, MD_ADAPTER_CTRL, md0 | val, Size.WORD);
        checkAden(lc, val);
    }
}
