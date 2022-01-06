package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static sh2.S32xUtil.readBuffer;
import static sh2.S32xUtil.writeBuffer;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 */
public class IntControl {

    private static final Logger LOG = LogManager.getLogger(IntControl.class.getSimpleName());

    public enum Sh2Interrupt {
        NONE_0, NONE_1, NONE_2, NONE_3, NONE_4, NONE_5,
        PWM_6, NONE_7, CMD_8, NONE_9, HINT_10, NONE_11, VINT_12,
        NONE_13, VRES_14, NONE_15, NMI_16;
    }

    public static final Sh2Interrupt[] intVals = Sh2Interrupt.values();

    private static final boolean verbose = false;

    //valid = not masked
    private boolean[] intValid = new boolean[intVals.length];
    private boolean[] intPending = new boolean[intVals.length];
    private boolean[] intTrigger = new boolean[intVals.length];

    // V, H, CMD and PWM each possesses exclusive address on the master side and the slave side.
    private ByteBuffer sh2_int_mask;
    private int interruptLevel;
    private CpuDeviceAccess cpu;

    public IntControl(CpuDeviceAccess cpu) {
        sh2_int_mask = ByteBuffer.allocateDirect(2);
        this.cpu = cpu;
        intc[cpu.ordinal()] = this;
        Arrays.fill(intValid, true);
        setIntsMasked(0);
    }

    //hack
    public static final IntControl[] intc = new IntControl[2];

    private void setIntMasked(int ipt, boolean isValid) {
        boolean val = this.intValid[ipt];
        if (val != isValid) {
            this.intValid[ipt] = isValid;
            boolean isPending = this.intPending[ipt];
            boolean isTrigger = this.intTrigger[ipt];
            //TODO check
//            if (!isTrigger || ipt == CMD_8.ordinal()) {
            if (ipt == CMD_8.ordinal()) {
                this.intTrigger[ipt] = isValid && isPending;
            }
            resetInterruptLevel();
            logInfo("MASK", ipt);
        }
    }

    public void setIntsMasked(int value) {
        for (int i = 0; i < 4; i++) {
            int imask = value & (1 << i);
            //0->PWM_6, 1->CMD_8, 2->HINT_10, 3->VINT_12
            int sh2Int = 6 + (i << 1);
            setIntMasked(sh2Int, imask > 0);
        }
    }

    public void writeSh2IntMaskReg(int reg, int value, Size size) {
        writeBuffer(sh2_int_mask, reg, value, size);
        int newVal = readBuffer(sh2_int_mask, reg, size);
        setIntsMasked(newVal);
    }

    public void setIntPending(Sh2Interrupt interrupt, boolean isPending) {
        setIntPending(interrupt.ordinal(), isPending);
    }

    public int readSh2IntMaskReg(int pos, Size size) {
        return readBuffer(sh2_int_mask, pos, size);
    }

    private void setIntPending(int ipt, boolean isPending) {
        boolean val = this.intPending[ipt];
        if (val != isPending) {
            boolean valid = this.intValid[ipt];
            if (valid) {
                this.intPending[ipt] = isPending;
                this.intTrigger[ipt] = valid && isPending;
                if (valid && isPending) {
                    resetInterruptLevel();
                }
                logInfo("PENDING", ipt);
            }
        }
    }

    private void resetInterruptLevel() {
        boolean[] ints = this.intTrigger;
        int newLevel = 0;
        for (int i = NMI_16.ordinal(); i >= NONE_0.ordinal(); i--) {
            if (ints[i]) {
                newLevel = i;
                break;
            }
        }
        interruptLevel = newLevel;
    }

    public void clearInterrupt(Sh2Interrupt intType) {
        clearInterrupt(intType.ordinal());
    }

    public void clearInterrupt(int ipt) {
        this.intPending[ipt] = false;
        this.intTrigger[ipt] = false;
        resetInterruptLevel();
        logInfo("CLEAR", ipt);
    }

    public int getInterruptLevel() {
        return interruptLevel;
    }

    public int getVectorNumber() {
        if (interruptLevel == NONE_15.ordinal()) {
            return 72; //TODO hack, should use IPRA/B
        }
        return 64 + (interruptLevel >> 1);
    }

    public ByteBuffer getSh2_int_mask_regs() {
        return sh2_int_mask;
    }

    private void logInfo(String action, int ipt) {
        if (verbose) {
            LOG.info("{}: {} {} valid (unmasked): {}, pending: {}, willTrigger: {}, intLevel: {}",
                    action, cpu, intVals[ipt], intValid[ipt], intPending[ipt], intTrigger[ipt], interruptLevel);
        }
    }
}