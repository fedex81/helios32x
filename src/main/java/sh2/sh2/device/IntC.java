package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.sh2.device.IntC.Sh2Interrupt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class IntC {

    public static final Sh2Interrupt[] intVals = Sh2Interrupt.values();
    private static final Logger LOG = LogManager.getLogger(IntC.class.getSimpleName());
    public static boolean DISABLE_INT = false;
    private static final boolean verbose = false;

    //master and slave, valid = not masked
    private boolean[][] intValid = new boolean[2][intVals.length];
    private boolean[][] intPending = new boolean[2][intVals.length];
    private boolean[][] intTrigger = new boolean[2][intVals.length];

    //master and slave
    // V, H, CMD and PWM each possesses exclusive address on the master side and the slave side.
    private ByteBuffer[] sh2_int_mask = new ByteBuffer[2];

    private int[] interruptLevel = new int[2];

    public IntC() {
        sh2_int_mask[MASTER.ordinal()] = ByteBuffer.allocateDirect(2);
        sh2_int_mask[SLAVE.ordinal()] = ByteBuffer.allocateDirect(2);
        if (DISABLE_INT) {
            LOG.warn("#### Sh2 Interrupts disabled!");
        }
    }

    private void setIntMasked(int sh2, int ipt, boolean isValid) {
        boolean val = this.intValid[sh2][ipt];
        if (val != isValid) {
            this.intValid[sh2][ipt] = isValid;
            boolean isPending = this.intPending[sh2][ipt];
            boolean isTrigger = this.intTrigger[sh2][ipt];
            //TODO check
//            if (!isTrigger || ipt == CMD_8.ordinal()) {
            if (ipt == CMD_8.ordinal()) {
                this.intTrigger[sh2][ipt] = isValid && isPending;
            }
            resetInterruptLevel(sh2);
            logInfo("MASK", sh2, ipt);
        }
    }

    public void setIntsMasked(CpuDeviceAccess sh2Access, int value) {
        final int sh2 = sh2Access.ordinal();
        for (int i = 0; i < 4; i++) {
            int imask = value & (1 << i);
            //0->PWM_6, 1->CMD_8, 2->HINT_10, 3->VINT_12
            int sh2Int = 6 + (i << 1);
            setIntMasked(sh2, sh2Int, imask > 0);
        }
    }

    public int readSh2IntMaskReg(CpuDeviceAccess sh2Access, int pos, Size size) {
        return S32xUtil.readBuffer(sh2_int_mask[sh2Access.ordinal()], pos, size);
    }

    public void writeSh2IntMaskReg(CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        S32xUtil.writeBuffer(sh2_int_mask[sh2Access.ordinal()], reg, value, size);
        int newVal = readSh2IntMaskReg(sh2Access, 0, Size.WORD);
        setIntsMasked(sh2Access, newVal);
    }

    public void setIntPending(CpuDeviceAccess sh2Access, Sh2Interrupt interrupt, boolean isPending) {
        setIntPending(sh2Access.ordinal(), interrupt.ordinal(), isPending);
    }

    private void setIntPending(int sh2, int ipt, boolean isPending) {
        boolean val = this.intPending[sh2][ipt];
        if (val != isPending) {
            boolean valid = this.intValid[sh2][ipt];
            if (valid) {
                this.intPending[sh2][ipt] = isPending;
                this.intTrigger[sh2][ipt] = valid && isPending;
                if (valid && isPending) {
                    resetInterruptLevel(sh2);
                }
                logInfo("PENDING", sh2, ipt);
            }
        }
    }

    private void resetInterruptLevel(int sh2) {
        boolean[] ints = this.intTrigger[sh2];
        int newLevel = 0;
        for (int i = VRES_14.ordinal(); i > NONE_5.ordinal(); i--) {
            if (ints[i]) {
                newLevel = i;
                break;
            }
        }
        interruptLevel[sh2] = newLevel;
    }

    public void clearInterrupt(CpuDeviceAccess sh2Access, Sh2Interrupt intType) {
        clearInterrupt(sh2Access.ordinal(), intType.ordinal());
    }

    public void clearInterrupt(int sh2, int ipt) {
        this.intPending[sh2][ipt] = false;
        this.intTrigger[sh2][ipt] = false;
        //TODO check
//        Arrays.fill(intPending[sh2], false);
//        Arrays.fill(intTrigger[sh2], false);
        resetInterruptLevel(sh2);
        logInfo("CLEAR", sh2, ipt);
    }

    public int getInterruptLevel(CpuDeviceAccess sh2Access) {
        return DISABLE_INT ? 0 : this.interruptLevel[sh2Access.ordinal()];
    }

    public ByteBuffer[] getSh2_int_mask_regs() {
        return sh2_int_mask;
    }

    private void logInfo(String action, int sh2, int ipt) {
        if (verbose) {
            LOG.info("{}: {} {} valid (unmasked): {}, pending: {}, willTrigger: {}, intLevel: {}",
                    action, CpuDeviceAccess.vals[sh2], intVals[ipt], intValid[sh2][ipt], intPending[sh2][ipt], intTrigger[sh2][ipt], interruptLevel[sh2]);
        }
    }

    public enum Sh2Interrupt {
        NONE_0, NONE_1, NONE_2, NONE_3, NONE_4, NONE_5,
        PWM_6, NONE_7, CMD_8, NONE_9, HINT_10, NONE_11, VINT_12,
        NONE_13, VRES_14, NONE_15, NMI_16;
    }
}
