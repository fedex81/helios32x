package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.dict.S32xDict.RegSpecS32x;

import static s32x.MarsRegTestUtil.MD_ADAPTER_CTRL;
import static s32x.MarsRegTestUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.S32xUtil.CpuDeviceAccess.Z80;
import static sh2.dict.S32xDict.M68K_START_32X_SYSREG;
import static sh2.dict.S32xDict.M68K_START_ROM_MIRROR_BANK;
import static sh2.dict.S32xDict.RegSpecS32x.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class MdRegsTest {

    private static final int mdBankSetAddr = M68K_START_32X_SYSREG | MD_BANK_SET.addr;
    private static final int mdIntCtrlByte0 = M68K_START_32X_SYSREG | MD_INT_CTRL.addr;
    private static final int mdIntCtrlByte1 = M68K_START_32X_SYSREG | (MD_INT_CTRL.addr + 1);
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

    @Test
    public void testZ80SysRegs() {
        RegSpecS32x[] regSpecs = {RegSpecS32x.MD_ADAPTER_CTRL, MD_INT_CTRL, MD_BANK_SET, RegSpecS32x.MD_DMAC_CTRL};
        testZ80RegsInternal(regSpecs);
    }

    @Test
    public void testZ80PwmRegs() {
        RegSpecS32x[] regSpecs = {PWM_CTRL, PWM_CYCLE, PWM_RCH_PW, PWM_LCH_PW, PWM_MONO};
        testZ80RegsInternal(regSpecs);
    }

    @Test
    public void testZ80CommRegs() {
        RegSpecS32x[] regSpecs = {COMM0, COMM1, COMM2, COMM3, COMM4, COMM5, COMM6, COMM7};
        testZ80RegsInternal(regSpecs);
    }

    private void testZ80RegsInternal(RegSpecS32x[] regSpecs) {
        setAdenMdSide(true);
        Md32xRuntimeData.setAccessTypeExt(Z80);

        for (RegSpecS32x regSpec : regSpecs) {
            int byte0Addr = M68K_START_32X_SYSREG | regSpec.addr;
            int byte1Addr = M68K_START_32X_SYSREG | (regSpec.addr + 1);
            int wordAddr = byte0Addr;

            checkBytesVsWord(regSpec);

            writeBus(lc, Z80, byte0Addr, 2, Size.BYTE);
            checkBytesVsWord(regSpec);

            writeBus(lc, Z80, byte1Addr, 0xF0, Size.BYTE);
            checkBytesVsWord(regSpec);

            writeBus(lc, M68K, wordAddr, 1, Size.WORD);
            checkBytesVsWord(regSpec);

            writeBus(lc, Z80, byte1Addr, 0, Size.BYTE);
            checkBytesVsWord(regSpec);

            switch (regSpec) {
                case MD_ADAPTER_CTRL:
                    break;
                case PWM_RCH_PW, PWM_LCH_PW, PWM_MONO:
                    emptyPwmFifoAndCheck(regSpec);
                    break;
                default:
                    int w = readBus(lc, M68K, wordAddr, Size.WORD);
                    Assertions.assertEquals(0, w, regSpec.toString());
                    break;
            }
        }
    }

    private void emptyPwmFifoAndCheck(RegSpecS32x regSpec) {
        lc.pwm.readFifoMono(regSpec);
        lc.pwm.readFifoMono(regSpec);
        lc.pwm.readFifoMono(regSpec);
        int emptyFifo = 1 << 14;
        int w = readBus(lc, M68K, M68K_START_32X_SYSREG | regSpec.addr, Size.WORD);
        Assertions.assertEquals(0 | emptyFifo, w, regSpec.toString());
    }

    private void checkBytesVsWord(RegSpecS32x regSpec) {
        int w = readBus(lc, M68K, M68K_START_32X_SYSREG | regSpec.addr, Size.WORD);
        int b0 = readBus(lc, Z80, M68K_START_32X_SYSREG | regSpec.addr, Size.BYTE);
        int b1 = readBus(lc, Z80, M68K_START_32X_SYSREG | (regSpec.addr + 1), Size.BYTE);
        Assertions.assertEquals(w & 0xFF, b1, regSpec.toString());
        Assertions.assertEquals(w >> 8, b0, regSpec.toString());
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
