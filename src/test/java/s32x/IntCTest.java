package s32x;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.sh2.device.IntControl;
import sh2.sh2.device.IntControl.Sh2Interrupt;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class IntCTest {

    private IntControl mInt, sInt;
    private Sh2Interrupt[] vals = {PWM_6, CMD_8, HINT_10, VINT_12};

    @BeforeEach
    public void before() {
        mInt = MarsRegTestUtil.createIntC(MASTER);
        sInt = MarsRegTestUtil.createIntC(SLAVE);
    }

    @Test
    public void testInterrupt() {
        for (Sh2Interrupt sint : vals) {
            //regMask = (sh2Int - 6)/2
            int regMask = 1 << ((sint.ordinal() - 6) >> 1);
            mInt.setIntsMasked(regMask); //mask all but current
            mInt.setIntPending(sint, true);
            int actual = mInt.getInterruptLevel();
            Assertions.assertEquals(sint.ordinal(), actual);

            mInt.clearInterrupt(sint);
            actual = mInt.getInterruptLevel();
            Assertions.assertEquals(0, actual);
        }
    }

    @Test
    public void testMaskingWhenPending() {
        IntControl intc = mInt;
        for (Sh2Interrupt sint : vals) {
            //regMask = (sh2Int - 6)/2
            int regMask = 1 << ((sint.ordinal() - 6) >> 1);
            intc.setIntsMasked(regMask); //mask all but current
            intc.setIntPending(sint, true);
            int actual = intc.getInterruptLevel();
            Assertions.assertEquals(sint.ordinal(), actual);

            //mask it
            intc.setIntsMasked(0);
            actual = intc.getInterruptLevel();
            if (sint == CMD_8) {
                Assertions.assertEquals(0, actual);
            } else {
                Assertions.assertEquals(sint.ordinal(), actual);
            }

            //clears it
            intc.clearInterrupt(sint);
            actual = intc.getInterruptLevel();
            Assertions.assertEquals(0, actual);
        }
    }

    @Test
    public void testCMD_INT() {
        IntControl intc = mInt;
        Sh2Interrupt sint = CMD_8;
        //regMask = (sh2Int - 6)/2
        int regMask = 1 << ((sint.ordinal() - 6) >> 1);
        intc.setIntsMasked(regMask); //mask all but current
        intc.setIntPending(sint, true);
        int actual = intc.getInterruptLevel();
        Assertions.assertEquals(sint.ordinal(), actual);

        //mask it
        intc.setIntsMasked(0);
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(0, actual);

        //unmask it
        intc.setIntsMasked(regMask);
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(sint.ordinal(), actual);

        //clears it
        intc.clearInterrupt(sint);
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(0, actual);
    }

    @Test
    public void testPendingWhenMasking() {
        IntControl intc = mInt;
        Sh2Interrupt vint = VINT_12;

        //discarded
        intc.setIntsMasked(0);
        intc.setIntPending(vint, true);

        int actual = intc.getInterruptLevel();
        Assertions.assertEquals(0, actual);

        //unmask vint
        int regMask = (1 << ((vint.ordinal() - 6) >> 1));
        intc.setIntsMasked(regMask);

        //no vint
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(0, actual);
    }

    @Test
    public void testTwoInterrupts() {
        IntControl intc = mInt;
        Sh2Interrupt vint = VINT_12;
        Sh2Interrupt hint = HINT_10;
        intc.setIntPending(hint, true);
        intc.setIntPending(vint, true);
        //regMask = (sh2Int - 6)/2
        int regMask = (1 << ((hint.ordinal() - 6) >> 1)) | (1 << ((vint.ordinal() - 6) >> 1));
        intc.setIntsMasked(regMask); //unmask VINT,HINT
        //no VINT, no hint
        int actual = intc.getInterruptLevel();
        Assertions.assertEquals(0, actual);

        //set pending
        intc.setIntPending(hint, true);
        intc.setIntPending(vint, true);

        //VINT
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(vint.ordinal(), actual);

        //clears VINT
        intc.clearInterrupt(vint);

        //TODO check this
        //HINT is left
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(hint.ordinal(), actual);
    }

}
