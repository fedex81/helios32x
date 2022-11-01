package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.device.IntControl;
import sh2.sh2.device.IntControl.Sh2Interrupt;

import static s32x.MarsRegTestUtil.*;
import static sh2.S32XMMREG.CART_INSERTED;
import static sh2.S32XMMREG.CART_NOT_INSERTED;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.INTMASK_HEN_BIT_POS;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xSharedRegsTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;

    @BeforeEach
    public void before() {
        lc = createTestInstance();
    }

    @Test
    public void testFm() {
        int expFm, fm;
        checkFm(lc, 0);

        testFm(Z80, MD_ADAPTER_CTRL_REG);
        testFm(M68K, MD_ADAPTER_CTRL_REG);
        testFm(MASTER, SH2_INT_MASK);
        testFm(SLAVE, SH2_INT_MASK);
    }

    @Test
    public void testSh2IntMask() {
        testSh2IntMaskCpu(MASTER, SLAVE);
        testSh2IntMaskCpu(SLAVE, MASTER);
    }

    //not shared
    private void testSh2IntMaskCpu(CpuDeviceAccess cpu, CpuDeviceAccess other) {
        System.out.println(cpu);
        IntControl intC = cpu == MASTER ? lc.masterCtx.devices.intC : lc.slaveCtx.devices.intC;
        IntControl otherIntC = other == MASTER ? lc.masterCtx.devices.intC : lc.slaveCtx.devices.intC;
        Sh2Interrupt[] ints = {PWM_6, CMD_8, HINT_10, VINT_12};

        int reg = SH2_INT_MASK;
        int value = 0xF; //all valid ints
        writeBus(lc, cpu, reg, value, Size.WORD);
        writeBus(lc, other, reg, 0, Size.WORD);

        //set FM, check that intValid is not changed
        writeBus(lc, cpu, reg, 1 << 7, Size.BYTE);
        int res = readBus(lc, cpu, reg + 1, Size.BYTE);
        int otherRes = readBus(lc, other, reg + 1, Size.BYTE);
        Assertions.assertEquals(value, res);
        Assertions.assertEquals(0, otherRes);

        for (Sh2Interrupt inter : ints) {
            intC.setIntPending(inter, true);
            otherIntC.setIntPending(inter, true);
            Assertions.assertEquals(intC.getInterruptLevel(), inter.ordinal());
            Assertions.assertEquals(otherIntC.getInterruptLevel(), 0);
            intC.clearCurrentInterrupt();
            otherIntC.setIntPending(inter, false);
        }
    }


    private void testFm(CpuDeviceAccess cpu, int reg) {
        int expFm, fm;

        expFm = fm = 1;
        writeBus(lc, cpu, reg, fm << 7, Size.BYTE);
        checkFm(lc, expFm);

        expFm = fm = 0;
        writeBus(lc, cpu, reg, fm << 7, Size.BYTE);
        checkFm(lc, expFm);

        if (cpu != Z80) {
            expFm = fm = 1;
            writeBus(lc, cpu, reg, fm << 15, Size.WORD);
            checkFm(lc, expFm);

            expFm = fm = 0;
            writeBus(lc, cpu, reg, fm << 15, Size.WORD);
            checkFm(lc, expFm);
        }
    }

    @Test
    public void testCart() {
        int cartSize = 0x100;
        //defaults to 0
        checkCart(lc, CART_INSERTED);

        //cart inserted
        lc.s32XMMREG.setCart(cartSize);
        int exp = CART_INSERTED << 8;

        checkCart(lc, exp);

        //cart removed, size = 0
        lc.s32XMMREG.setCart(0);
        checkCart(lc, CART_NOT_INSERTED << 8);
    }

    @Test
    public void testAden01_BYTE_M68K() {
        testAden01_BYTE_internal(M68K);
    }

    @Test
    public void testAden01_BYTE_Z80() {
        testAden01_BYTE_internal(Z80);
    }

    private void testAden01_BYTE_internal(CpuDeviceAccess cpu) {
        int aden, expAden;
        //defaults to 0
        checkAden(lc, 0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        writeBus(lc, cpu, MD_ADAPTER_CTRL_REG + 1, aden, Size.BYTE);
        checkAden(lc, expAden);

        //m68k clears Aden, not supported
        aden = 0;
        expAden = 1;
        writeBus(lc, cpu, MD_ADAPTER_CTRL_REG + 1, aden, Size.BYTE);
        if (cpu != Z80) {
            writeBus(lc, cpu, MD_ADAPTER_CTRL_REG, aden, Size.WORD);
        }
        checkAden(lc, expAden);
    }

    @Test
    public void testAden02_WORD() {
        int aden, expAden;
        //defaults to 0
        checkAden(lc, 0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        writeBus(lc, M68K, MD_ADAPTER_CTRL_REG, aden, Size.WORD);
        checkAden(lc, expAden);
    }

    //TODO fix
    @Test
    public void testSh2SetAden() {
        Assumptions.assumeTrue(false);
        //defaults to 0
        checkAden(lc, 0);

        //sh2 sets Aden, not supported, read only
        int aden = 1;
        int expAden = 0;
        writeBus(lc, MASTER, SH2_INT_MASK, aden << 1, Size.BYTE);
        writeBus(lc, SLAVE, SH2_INT_MASK, aden << 1, Size.BYTE);
        checkAden(lc, expAden);
    }

    @Test
    public void testHEN() {
        //defaults to 0
        checkHen(lc, 0);

        int val = 1 << INTMASK_HEN_BIT_POS;
        writeBus(lc, MASTER, SH2_INT_MASK, val, Size.WORD);
        checkHen(lc, val);
        writeBus(lc, SLAVE, SH2_INT_MASK, val, Size.WORD);
        checkHen(lc, val);
        writeBus(lc, SLAVE, SH2_INT_MASK, 0, Size.WORD);
        checkHen(lc, 0);

        writeBus(lc, SLAVE, SH2_INT_MASK + 1, val, Size.BYTE);
        checkHen(lc, val);
        writeBus(lc, MASTER, SH2_INT_MASK + 1, val, Size.BYTE);
        checkHen(lc, val);
        writeBus(lc, MASTER, SH2_INT_MASK + 1, 0, Size.BYTE);
        checkHen(lc, 0);
    }


}
