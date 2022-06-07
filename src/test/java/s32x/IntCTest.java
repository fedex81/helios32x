package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;
import sh2.Sh2MMREG;
import sh2.dict.S32xDict;
import sh2.sh2.device.IntControl;
import sh2.sh2.device.IntControl.Sh2Interrupt;

import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.RegSpecS32x.M68K_INT_CTRL;
import static sh2.dict.S32xDict.RegSpecS32x.SH2_CMD_INT_CLEAR;
import static sh2.dict.S32xDict.START_32X_SYSREG_CACHE;
import static sh2.dict.Sh2Dict.RegSpec.INTC_IPRA;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;
import static sh2.sh2.device.IntControl.Sh2InterruptSource;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.DIV;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class IntCTest {

    private IntControl mInt, sInt;
    private Sh2Interrupt[] vals = {PWM_6, CMD_8, HINT_10, VINT_12};
    private MarsLauncherHelper.Sh2LaunchContext lc;

    private static final int BASE_M68K_SYS_REG = 0xA15100;

    @BeforeEach
    public void before() {
        lc = MarsRegTestUtil.createTestInstance();
        mInt = lc.mDevCtx.intC;
        sInt = lc.sDevCtx.intC;
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
    public void testCMD_INT_M68KReg() {
        IntControl intc = mInt;
        Sh2Interrupt sint = CMD_8;
        //regMask = (sh2Int - 6)/2
        int regMask = 1 << ((sint.ordinal() - 6) >> 1);
        intc.setIntsMasked(regMask); //mask all but current
        intc.setIntPending(sint, true);
        int actual = intc.getInterruptLevel();
        Assertions.assertEquals(sint.ordinal(), actual);

        //MASTER
        //trigger sh2 Master CMD interrupt from 68k by setting INTM
        setM68kSysReg(M68K_INT_CTRL, 1);
        //clear m68k register for INTS
        setSh2SysReg(SLAVE, SH2_CMD_INT_CLEAR, 0xAA);
        //INTM still set
        checkM68kSysReg(M68K_INT_CTRL, 1);
        //clear m68k register for INTM
        setSh2SysReg(MASTER, SH2_CMD_INT_CLEAR, 0xAA);
        checkM68kSysReg(M68K_INT_CTRL, 0);

        //SLAVE
        setM68kSysReg(M68K_INT_CTRL, 2);
        setSh2SysReg(MASTER, SH2_CMD_INT_CLEAR, 0xAA);
        checkM68kSysReg(M68K_INT_CTRL, 2);
        setSh2SysReg(SLAVE, SH2_CMD_INT_CLEAR, 0xAA);
        checkM68kSysReg(M68K_INT_CTRL, 0);

        //BOTH
        setM68kSysReg(M68K_INT_CTRL, 3);
        setSh2SysReg(MASTER, SH2_CMD_INT_CLEAR, 0xAA);
        checkM68kSysReg(M68K_INT_CTRL, 2);
        setSh2SysReg(SLAVE, SH2_CMD_INT_CLEAR, 0xAA);
        checkM68kSysReg(M68K_INT_CTRL, 0);
    }

    /**
     * Master and slave have separate intClear regs
     */
    @Test
    public void testSh2InterruptClearRegisterSeparate() {
        for (Sh2Interrupt sint : vals) {
            //regMask = (sh2Int - 6)/2
            int regMask = 1 << ((sint.ordinal() - 6) >> 1);
            mInt.setIntsMasked(regMask); //mask all but current
            sInt.setIntsMasked(regMask);
            mInt.setIntPending(sint, true);
            sInt.setIntPending(sint, true);
            Assertions.assertEquals(sint.ordinal(), mInt.getInterruptLevel());
            Assertions.assertEquals(sint.ordinal(), sInt.getInterruptLevel());

            //clear MASTER only
            setSh2SysReg(MASTER, 0x22 - sint.ordinal(), 0xBB, Size.WORD);
            Assertions.assertEquals(0, mInt.getInterruptLevel());
            Assertions.assertEquals(sint.ordinal(), sInt.getInterruptLevel());

            //clear SLAVE
            setSh2SysReg(SLAVE, 0x22 - sint.ordinal(), 0xBB, Size.WORD);
            Assertions.assertEquals(0, mInt.getInterruptLevel());
            Assertions.assertEquals(0, sInt.getInterruptLevel());
        }
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

    @Test
    public void testTwoInterruptsOneOnChip() {
        Sh2MMREG s = lc.mDevCtx.sh2MMREG;
        //DIV interrupt prio 12
        int prio = 10;
        int res, actual;
        int iprA = prio << 12;
        s.write(INTC_IPRA.addr, iprA, Size.WORD);

        IntControl intc = mInt;
        Sh2Interrupt hint = HINT_10;

        int regMask = (1 << ((hint.ordinal() - 6) >> 1));
        intc.setIntsMasked(regMask); //unmask HINT

        intc.setOnChipDeviceIntPending(DIV);
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(prio, actual);
        Assertions.assertEquals(Sh2InterruptSource.DIVU, intc.getInterruptContext().source);

        intc.setIntPending(hint, true);
        actual = intc.getInterruptLevel();
        Assertions.assertEquals(prio, 10);
        Assertions.assertEquals(Sh2InterruptSource.HINT10, intc.getInterruptContext().source);
    }

    @Test
    public void testRegWrite() {
        Sh2MMREG s = lc.mDevCtx.sh2MMREG;
        int val, res;
        val = 0xffff;
        s.write(INTC_IPRA.addr, val, Size.WORD);
        res = s.read(INTC_IPRA.addr, Size.WORD);
        Assertions.assertEquals(val, res);

        val = 0xAA;
        s.write(INTC_IPRA.addr, 0, Size.WORD);
        s.write(INTC_IPRA.addr + 1, val, Size.BYTE);
        res = s.read(INTC_IPRA.addr, Size.WORD);
        Assertions.assertEquals(val, res);
        res = s.read(INTC_IPRA.addr, Size.BYTE);
        Assertions.assertEquals(0, res);
        res = s.read(INTC_IPRA.addr + 1, Size.BYTE);
        Assertions.assertEquals(val, res);

        val = 0xBB;
        s.write(INTC_IPRA.addr, val, Size.BYTE);
        res = s.read(INTC_IPRA.addr, Size.WORD);
        Assertions.assertEquals(0xBBAA, res);
        res = s.read(INTC_IPRA.addr, Size.BYTE);
        Assertions.assertEquals(val, res);
        res = s.read(INTC_IPRA.addr + 1, Size.BYTE);
        Assertions.assertEquals(0xAA, res);
    }

    private void setSh2SysReg(S32xUtil.CpuDeviceAccess cpu, int addr, int value, Size size) {
        Md32xRuntimeData.setAccessTypeExt(cpu);
        lc.s32XMMREG.write(START_32X_SYSREG_CACHE + addr, value, size);
    }

    private void setSh2SysReg(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x r, int value) {
        Md32xRuntimeData.setAccessTypeExt(cpu);
        lc.s32XMMREG.write(START_32X_SYSREG_CACHE + r.fullAddress, value, r.size);
    }

    private void setM68kSysReg(S32xDict.RegSpecS32x r, int value) {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        lc.bus.write(BASE_M68K_SYS_REG + r.fullAddress, value, r.size);
    }

    private void checkM68kSysReg(S32xDict.RegSpecS32x r, int expected) {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        int res = (int) lc.bus.read(BASE_M68K_SYS_REG + r.fullAddress, r.size);
        Assertions.assertEquals(expected, res, r.name);
    }
}