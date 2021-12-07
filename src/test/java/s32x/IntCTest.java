package s32x;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.IntC;
import sh2.IntC.Sh2Interrupt;

import static sh2.IntC.Sh2Interrupt.*;
import static sh2.Sh2Util.CpuDeviceAccess.MASTER;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class IntCTest {

    static {
        IntC.DISABLE_INT = false;
    }

    private IntC intc;
    private Sh2Interrupt[] vals = {PWM_6, CMD_8, HINT_10, VINT_12};

    @BeforeEach
    public void before() {
        intc = new IntC();
    }

    @Test
    public void testInterrupt() {
        for (Sh2Interrupt sint : vals) {
            //regMask = (sh2Int - 6)/2
            int regMask = 1 << ((sint.ordinal() - 6) >> 1);
            intc.setIntsMasked(MASTER, regMask); //mask all but current
            intc.setIntPending(MASTER, sint, true);
            int actual = intc.getInterruptLevel(MASTER);
            Assertions.assertEquals(sint.ordinal(), actual);

            intc.clearInterrupt(MASTER, sint);
            actual = intc.getInterruptLevel(MASTER);
            Assertions.assertEquals(0, actual);
        }
    }

    @Test
    public void testMaskingWhenPending() {
        for (Sh2Interrupt sint : vals) {
            //regMask = (sh2Int - 6)/2
            int regMask = 1 << ((sint.ordinal() - 6) >> 1);
            intc.setIntsMasked(MASTER, regMask); //mask all but current
            intc.setIntPending(MASTER, sint, true);
            int actual = intc.getInterruptLevel(MASTER);
            Assertions.assertEquals(sint.ordinal(), actual);

            //mask it
            intc.setIntsMasked(MASTER, 0);
            actual = intc.getInterruptLevel(MASTER);
            if (sint == CMD_8) {
                Assertions.assertEquals(0, actual);
            } else {
                Assertions.assertEquals(sint.ordinal(), actual);
            }

            //clears it
            intc.clearInterrupt(MASTER, sint);
            actual = intc.getInterruptLevel(MASTER);
            Assertions.assertEquals(0, actual);
        }
    }

    @Test
    public void testCMD_INT() {
        Sh2Interrupt sint = CMD_8;
        //regMask = (sh2Int - 6)/2
        int regMask = 1 << ((sint.ordinal() - 6) >> 1);
        intc.setIntsMasked(MASTER, regMask); //mask all but current
        intc.setIntPending(MASTER, sint, true);
        int actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(sint.ordinal(), actual);

        //mask it
        intc.setIntsMasked(MASTER, 0);
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(0, actual);

        //unmask it
        intc.setIntsMasked(MASTER, regMask);
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(sint.ordinal(), actual);

        //clears it
        intc.clearInterrupt(MASTER, sint);
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(0, actual);
    }

    @Test
    public void testPendingWhenMasking() {
        Sh2Interrupt vint = VINT_12;

        //discarded
        intc.setIntsMasked(MASTER, 0);
        intc.setIntPending(MASTER, vint, true);

        int actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(0, actual);

        //unmask vint
        int regMask = (1 << ((vint.ordinal() - 6) >> 1));
        intc.setIntsMasked(MASTER, regMask);

        //no vint
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(0, actual);
    }

    @Test
    public void testTwoInterrupts() {
        Sh2Interrupt vint = VINT_12;
        Sh2Interrupt hint = HINT_10;
        intc.setIntPending(MASTER, hint, true);
        intc.setIntPending(MASTER, vint, true);
        //regMask = (sh2Int - 6)/2
        int regMask = (1 << ((hint.ordinal() - 6) >> 1)) | (1 << ((vint.ordinal() - 6) >> 1));
        intc.setIntsMasked(MASTER, regMask); //unmask VINT,HINT
        //no VINT, no hint
        int actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(0, actual);

        //set pending
        intc.setIntPending(MASTER, hint, true);
        intc.setIntPending(MASTER, vint, true);

        //VINT
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(vint.ordinal(), actual);

        //clears VINT
        intc.clearInterrupt(MASTER, vint);

        //TODO check this
        //HINT is left
        actual = intc.getInterruptLevel(MASTER);
        Assertions.assertEquals(hint.ordinal(), actual);
    }

}
