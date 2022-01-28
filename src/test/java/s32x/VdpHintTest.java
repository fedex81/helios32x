package s32x;

import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import omegadrive.vdp.model.VdpCounterMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;
import sh2.sh2.device.IntControl;
import sh2.sh2.device.IntControl.Sh2Interrupt;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.RegSpecS32x.SH2_HCOUNT_REG;
import static sh2.sh2.device.IntControl.Sh2Interrupt.HINT_10;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class VdpHintTest {

    public static final int HCOUNT_OFFSET = 0x4000 + SH2_HCOUNT_REG.addr;

    private S32XMMREG s32XMMREG;
    private IntControl masterIntControl;

    @BeforeEach
    public void before() {
        s32XMMREG = MarsRegTestUtil.createInstance();
        masterIntControl = s32XMMREG.interruptControls[0];
    }

    //hcount = 0, hen = 0
    @Test
    public void test01() {
        setReloadHCount(0);
        s32XMMREG.write(MarsRegTestUtil.INT_MASK, 0x4, Size.WORD); //enable HINT
        int hint = testInternal(VideoMode.NTSCU_H40_V28, false);
        Assertions.assertTrue((hint & 0xFF00) == 0x1A00); //0x1A22
    }

    //hcount = 0, hen = 1
    @Test
    public void test02() {
        setReloadHCount(0);
        s32XMMREG.write(MarsRegTestUtil.INT_MASK, 0x84, Size.WORD); //enable HINT, HEN = 1
        int hint = testInternal(VideoMode.NTSCU_H40_V28, true);
        Assertions.assertTrue((hint & 0xFF00) == 0x1E00); //0x1e96
    }

    //hcount = 5, hen = 1
    @Test
    public void test03() {
        setReloadHCount(5);
        s32XMMREG.write(MarsRegTestUtil.INT_MASK, 0x84, Size.WORD); //enable HINT, HEN = 1
        int hint = testInternal(VideoMode.NTSCU_H40_V28, true);
        Assertions.assertTrue((hint & 0xFF00) == 0x500); //0x519
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
                masterIntControl.clearCurrentInterrupt();
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
