package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.*;
import sh2.dict.S32xDict;

import static s32x.MarsRegTestUtil.AD_CTRL;
import static s32x.MarsRegTestUtil.INT_MASK;
import static sh2.S32xUtil.CpuDeviceAccess.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class FrameBufferAccessTest {

    private Sh2Memory memory;
    private S32xBus mdBus;
    private S32XMMREG s32XMMREG;

    private static final int MD_ACCESS = 0;
    private static final int SH2_ACCESS = 1;

    private static final int sh2IntMask = S32xDict.START_32X_SYSREG + S32xDict.RegSpecS32x.SH2_INT_MASK.fullAddress;
    private static final int sh2HCount = S32xDict.START_32X_SYSREG + S32xDict.RegSpecS32x.SH2_HCOUNT_REG.fullAddress;
    private static final int mdTv = S32xBus.START_32X_SYSREG + S32xDict.RegSpecS32x.M68K_SEGA_TV.fullAddress;
    private static final int sh2sscr = S32xDict.START_32X_VDPREG + S32xDict.RegSpecS32x.SSCR.fullAddress;
    private static final int mdSscr = S32xBus.START_32X_VDPREG + S32xDict.RegSpecS32x.SSCR.fullAddress;

    @BeforeEach
    public void before() {
        MarsLauncherHelper.Sh2LaunchContext lc = MarsRegTestUtil.createTestInstance();
        s32XMMREG = lc.s32XMMREG;
        memory = lc.memory;
        mdBus = lc.bus;
        s32XMMREG.aden = 1;
    }

    @Test
    public void testMdAccess() {
        setFmAccess(MD_ACCESS);

        //sh2 can modify sysRegs
        modifyAddress(MASTER, sh2HCount, true);
        modifyAddress(SLAVE, sh2HCount, true);
        //sh2 cannot modify vdpRegs, frame buffer, overwrite image, palette
        modifyAddress(MASTER, sh2sscr, false);
        modifyAddress(SLAVE, sh2sscr, false);
        modifyAddress(MASTER, S32xDict.START_32X_COLPAL, false);
        modifyAddress(SLAVE, S32xDict.START_32X_COLPAL, false);
        modifyAddress(MASTER, S32xDict.START_DRAM, false);
        modifyAddress(SLAVE, S32xDict.START_DRAM, false);
        modifyAddress(MASTER, S32xDict.START_OVER_IMAGE, false);
        modifyAddress(SLAVE, S32xDict.START_OVER_IMAGE, false);

        //md can modify anything
        modifyAddress(M68K, mdTv, true);
        modifyAddress(M68K, mdSscr, true);
        modifyAddress(M68K, S32xBus.START_32X_COLPAL, true);
        modifyAddress(M68K, S32xBus.START_FRAME_BUFFER, true);
        modifyAddress(M68K, S32xBus.START_OVERWRITE_IMAGE, true);
    }

    @Test
    public void testSh2Access() {
        setFmAccess(SH2_ACCESS);

        //sh2 can modify anything
        modifyAddress(MASTER, sh2HCount, true);
        modifyAddress(SLAVE, sh2HCount, true);
        modifyAddress(MASTER, sh2sscr, true);
        modifyAddress(SLAVE, sh2sscr, true);
        modifyAddress(MASTER, S32xDict.START_32X_COLPAL, true);
        modifyAddress(SLAVE, S32xDict.START_32X_COLPAL, true);
        modifyAddress(MASTER, S32xDict.START_DRAM, true);
        modifyAddress(SLAVE, S32xDict.START_DRAM, true);
        modifyAddress(MASTER, S32xDict.START_OVER_IMAGE, true);
        modifyAddress(SLAVE, S32xDict.START_OVER_IMAGE, true);

        //md can only modify sysRegs
        modifyAddress(M68K, mdTv, true);
        modifyAddress(M68K, mdSscr, false);
        modifyAddress(M68K, S32xBus.START_32X_COLPAL, false);
        modifyAddress(M68K, S32xBus.START_FRAME_BUFFER, false);
        modifyAddress(M68K, S32xBus.START_OVERWRITE_IMAGE, false);
    }

    @Test
    public void testSwitch() {
        testMdAccess();
        testSh2Access();
        testMdAccess();
        testSh2Access();
    }

    private void setFmAccess(int fm) {
        int val = readBus(MASTER, sh2IntMask, Size.WORD) & 0x7FFF;
        writeBus(MASTER, sh2IntMask, (fm << 15) | val, Size.WORD);
        checkFm(fm);
    }

    private void modifyAddress(S32xUtil.CpuDeviceAccess access, int address, boolean matchNewVal) {
        int res = readBus(access, address, Size.WORD);
        int newVal = res + 1;
        int exp = matchNewVal ? newVal : res;
        writeBus(access, address, newVal, Size.WORD);
        int act = readBus(access, address, Size.WORD);
        Assertions.assertEquals(exp, act);
    }

    private void checkFm(int exp) {
        Assertions.assertEquals(exp, (read(M68K, AD_CTRL, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(M68K, AD_CTRL, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (read(MASTER, INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(MASTER, INT_MASK, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (read(SLAVE, INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(SLAVE, INT_MASK, Size.BYTE) >> 7) & 1);
    }

    private int readBus(S32xUtil.CpuDeviceAccess sh2Access, int reg, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        if (sh2Access == M68K) {
            return (int) mdBus.read(reg, size);
        } else {
            return memory.read(reg, size);
        }
    }

    private void writeBus(S32xUtil.CpuDeviceAccess sh2Access, int reg, int data, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        if (sh2Access == M68K) {
            mdBus.write(reg, data, size);
        } else {
            memory.write(reg, data, size);
        }
    }

    private int read(S32xUtil.CpuDeviceAccess sh2Access, int reg, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        return s32XMMREG.read(reg, size);
    }
}
