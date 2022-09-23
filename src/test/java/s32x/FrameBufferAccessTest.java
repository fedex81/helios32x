package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil;
import sh2.dict.S32xDict;

import static s32x.MarsRegTestUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class FrameBufferAccessTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;

    private static final int MD_ACCESS = 0;
    private static final int SH2_ACCESS = 1;

    private static final int sh2HCount = S32xDict.START_32X_SYSREG + S32xDict.RegSpecS32x.SH2_HCOUNT_REG.fullAddress;
    private static final int mdTv = M68K_START_32X_SYSREG + S32xDict.RegSpecS32x.MD_SEGA_TV.fullAddress;
    private static final int sh2sscr = S32xDict.START_32X_VDPREG + S32xDict.RegSpecS32x.SSCR.fullAddress;
    private static final int mdSscr = M68K_START_32X_VDPREG + S32xDict.RegSpecS32x.SSCR.fullAddress;

    @BeforeEach
    public void before() {
        lc = MarsRegTestUtil.createTestInstance();
        lc.s32XMMREG.aden = 1;
    }

    @Test
    public void testMdAccess() {
        setFmAccess(lc, MD_ACCESS);

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
        modifyAddress(M68K, M68K_START_32X_COLPAL, true);
        modifyAddress(M68K, M68K_START_FRAME_BUFFER, true);
        modifyAddress(M68K, M68K_START_OVERWRITE_IMAGE, true);
    }

    @Test
    public void testSh2Access() {
        setFmAccess(lc, SH2_ACCESS);

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
        modifyAddress(M68K, M68K_START_32X_COLPAL, false);
        modifyAddress(M68K, M68K_START_FRAME_BUFFER, false);
        modifyAddress(M68K, M68K_START_OVERWRITE_IMAGE, false);
    }

    @Test
    public void testSwitch() {
        testMdAccess();
        testSh2Access();
        testMdAccess();
        testSh2Access();
    }

    private void modifyAddress(S32xUtil.CpuDeviceAccess access, int address, boolean matchNewVal) {
        int res = readBus(lc, access, address, Size.WORD);
        int newVal = res + 1;
        int exp = matchNewVal ? newVal : res;
        writeBus(lc, access, address, newVal, Size.WORD);
        int act = readBus(lc, access, address, Size.WORD);
        Assertions.assertEquals(exp, act);
    }
}
