package s32x;

import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import omegadrive.vdp.model.VdpCounterMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.S32xUtil;
import sh2.sh2.device.IntControl;
import sh2.sh2.device.IntControl.Sh2Interrupt;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.RegSpecS32x.SH2_HCOUNT_REG;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class VdpInterruptTest {

    public static final int HCOUNT_OFFSET = 0x4000 + SH2_HCOUNT_REG.addr;

    private MarsLauncherHelper.Sh2LaunchContext lc;
    private S32XMMREG s32XMMREG;
    private IntControl masterIntControl;

    @BeforeEach
    public void before() {
        lc = MarsRegTestUtil.createTestInstance();
        s32XMMREG = lc.s32XMMREG;
        masterIntControl = lc.mDevCtx.intC;
        Md32xRuntimeData.setAccessTypeExt(S32xUtil.CpuDeviceAccess.MASTER);
    }

    //hcount = 0, hen = 0
    @Test
    public void test01() {
        setReloadHCount(0);
        s32XMMREG.write(MarsRegTestUtil.SH2_INT_MASK, 0x4, Size.WORD); //enable HINT
        int hint = testInternal(VideoMode.NTSCU_H40_V28, false);
        Assertions.assertTrue((hint & 0xFF00) == 0x1A00, th(hint)); //0x1A22
    }

    //hcount = 0, hen = 1
    @Test
    public void test02() {
        setReloadHCount(0);
        s32XMMREG.write(MarsRegTestUtil.SH2_INT_MASK, 0x84, Size.WORD); //enable HINT, HEN = 1
        int hint = testInternal(VideoMode.NTSCU_H40_V28, true);
        Assertions.assertTrue((hint & 0xFF00) == 0x1E00); //0x1e96
    }

    //hcount = 5, hen = 1
    @Test
    public void test03() {
        setReloadHCount(5);
        s32XMMREG.write(MarsRegTestUtil.SH2_INT_MASK, 0x84, Size.WORD); //enable HINT, HEN = 1
        int hint = testInternal(VideoMode.NTSCU_H40_V28, true);
        Assertions.assertTrue((hint & 0xFF00) == 0x500); //0x519
    }

    /**
     * Partially follows Ares impl
     */
    @Test
    public void testHBlankOffAffectIntPendingState() {
        setReloadHCount(0);
        s32XMMREG.setHBlank(false);
        s32XMMREG.write(MarsRegTestUtil.SH2_INT_MASK, 0x4, Size.WORD); //enable HINT

        s32XMMREG.setHBlank(true);
        int lev = masterIntControl.getInterruptLevel();
        Sh2Interrupt actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(HINT_10, actualInt);

        //unset int_pending but the interrupt remains triggered until cleared
        s32XMMREG.setHBlank(false);
        lev = masterIntControl.getInterruptLevel();
        actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(HINT_10, actualInt);

        masterIntControl.clearCurrentInterrupt();
        lev = masterIntControl.getInterruptLevel();
        actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(NONE_0, actualInt);
    }

    /**
     * Partially follows Ares impl
     */
    @Test
    public void testVBlankOffAffectIntPendingState() {
        s32XMMREG.setVBlank(false);
        s32XMMREG.write(MarsRegTestUtil.SH2_INT_MASK, 0x8, Size.WORD); //enable VINT

        s32XMMREG.setVBlank(true);
        int lev = masterIntControl.getInterruptLevel();
        Sh2Interrupt actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(VINT_12, actualInt);

        //unset int_pending but the interrupt remains triggered until cleared
        s32XMMREG.setVBlank(false);
        lev = masterIntControl.getInterruptLevel();
        actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(VINT_12, actualInt);

        masterIntControl.clearCurrentInterrupt();
        lev = masterIntControl.getInterruptLevel();
        actualInt = IntControl.intVals[lev];
        Assertions.assertEquals(NONE_0, actualInt);
    }

    private void setReloadHCount(int hCount) {
        s32XMMREG.write(HCOUNT_OFFSET, hCount, Size.WORD);
        s32XMMREG.setVBlank(false);
        s32XMMREG.setHBlank(true);
        s32XMMREG.setHBlank(false);
    }

    private int testInternal(VideoMode vm, boolean hen) {
        VdpCounterMode vcm = VdpCounterMode.getCounterMode(vm);
        int lineVBlankSet = vcm.vBlankSet - 1;
        int lines = vcm.vTotalCount - 1;
        int lev;

        int frame = 0;
        int line = 0;
        int hint = 0;
        s32XMMREG.setVBlank(false);
        Sh2Interrupt actualInt;
        do {
            //visible line, then
            s32XMMREG.setHBlank(true);

            lev = masterIntControl.getInterruptLevel();
            actualInt = IntControl.intVals[lev];
            if (actualInt == HINT_10) {
                masterIntControl.clearInterrupt(HINT_10);
                hint++;
//                System.out.println("frame: " + frame + ", line: " + line + ", num: "+ hint);
            }
            line++;
            if (line == lineVBlankSet) {
                s32XMMREG.setVBlank(true);
            }
            if (line == lines) {
                frame++;
                line = 0;
                s32XMMREG.setVBlank(false);
            }
            s32XMMREG.setHBlank(false);
        } while (frame < 30);

        System.out.println(th(hint));
        return hint;
    }
}
