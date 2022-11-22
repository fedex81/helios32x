package sh2.sh2.device;

import com.google.common.annotations.VisibleForTesting;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.dict.Sh2Dict.RegSpec;
import sh2.event.SysEventManager;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType;
import sh2.sh2.drc.Ow2DrcOptimizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.RegSpecS32x.SH2_INT_MASK;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.event.SysEventManager.SysEvent.INT;
import static sh2.sh2.device.IntControl.Sh2Interrupt.NMI_16;
import static sh2.sh2.device.IntControl.Sh2Interrupt.VRES_14;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 */
public class IntControlImplOld implements IntControl {

    private static final Logger LOG = LogHelper.getLogger(IntControlImplOld.class.getSimpleName());

    public static final int MAX_LEVEL = 17; //[0-16]

    private static final boolean verbose = false;

    private final Map<Sh2DeviceType, Integer> onChipDevicePriority = new HashMap<>();
    private final Map<Integer, Sh2Interrupt> s32xInt = new HashMap<>(MAX_LEVEL);

    public static class InterruptState {
        Sh2Interrupt interrupt;
        boolean active;
        boolean enable;

        @Override
        public String toString() {
            return new StringJoiner(", ", InterruptState.class.getSimpleName() + "[", "]")
                    .add("interrupt=" + interrupt)
                    .add("active=" + active)
                    .add("enable=" + enable)
                    .toString();
        }
    }

    private final InterruptState[] istate = new InterruptState[MAX_LEVEL];

//    //valid = not masked
//    private final boolean[] intValid = new boolean[MAX_LEVEL];
//    private final boolean[] intPending = new boolean[MAX_LEVEL];
//    private final boolean[] intTrigger = new boolean[MAX_LEVEL];

    // V, H, CMD and PWM each possesses exclusive address on the master side and the slave side.
    private final ByteBuffer sh2_int_mask;
    private final ByteBuffer regs;
    private int interruptLevel;
    private final CpuDeviceAccess cpu;
    private int additionalIntData = 0;

    public IntControlImplOld(CpuDeviceAccess cpu, ByteBuffer regs) {
        sh2_int_mask = ByteBuffer.allocateDirect(2);
        this.regs = regs;
        this.cpu = cpu;
        init();
    }

    @Override
    public void init() {
        for (int i = 0; i < istate.length; i++) {
            InterruptState st = new InterruptState();
            st.enable = true;
            st.interrupt = intVals[i];
            istate[i] = st;
        }
        setIntsMasked(0);
        Arrays.stream(Sh2DeviceType.values()).forEach(d -> onChipDevicePriority.put(d, 0));
    }

    @Override
    public void write(RegSpec regSpec, int pos, int value, Size size) {
        int val = 0;
        writeBuffer(regs, pos, value, size);
        switch (regSpec) {
            case INTC_IPRA:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                onChipDevicePriority.put(Sh2DeviceType.DIV, val >> 12);
                onChipDevicePriority.put(Sh2DeviceType.DMA, (val >> 8) & 0xF);
                onChipDevicePriority.put(Sh2DeviceType.WDT, (val >> 4) & 0xF);
                logExternalIntLevel(regSpec, val);
                break;
            case INTC_IPRB:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                onChipDevicePriority.put(Sh2DeviceType.SCI, val >> 12);
                onChipDevicePriority.put(Sh2DeviceType.FRT, (val >> 8) & 0xF);
                logExternalIntLevel(regSpec, val);
                break;
            case INTC_ICR:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                if ((val & 1) > 0) {
                    LOG.error("{} Not supported: IRL Interrupt vector mode: External Vector", cpu);
                }
                break;
        }
    }

    @Override
    public int read(RegSpec regSpec, int reg, Size size) {
        if (verbose) LOG.info("{} Read {} value: {} {}", cpu, regSpec.name, th(readBuffer(regs, reg, size)), size);
        return readBuffer(regs, reg, size);
    }

    private void setIntEnable(int ipt, boolean enable) {
        istate[ipt].enable = enable;
        resetInterruptLevel();
        logInfo("ENABLE_MASK", ipt);
    }

    public void setIntsMasked(int value) {
        for (int i = 0; i < 4; i++) {
            int imask = value & (1 << i);
            //0->PWM_6, 1->CMD_8, 2->HINT_10, 3->VINT_12
            int sh2Int = 6 + (i << 1);
            setIntEnable(sh2Int, imask > 0);
        }
    }

    @Override
    public void writeSh2IntMaskReg(int reg, int value, Size size) {
        writeBuffer(sh2_int_mask, reg, value, size);
        int newVal = readBuffer(sh2_int_mask, SH2_INT_MASK.addr, Size.WORD);
        setIntsMasked(newVal & 0xF);
    }

    @Override
    public void setOnChipDeviceIntPending(Sh2DeviceType deviceType, OnChipSubType subType) {
        int data = subType == OnChipSubType.DMA_C1 ? 1 : 0;
        data = subType == OnChipSubType.RIE ? 1 : data;
        setExternalIntPending(deviceType, data, true);
    }

    public void setExternalIntPending(Sh2DeviceType deviceType, int intData, boolean isPending) {
        int level = onChipDevicePriority.get(deviceType);
        if (interruptLevel > 0 && interruptLevel < level) {
            LOG.info("{} {}{} ext interrupt pending: {}, level: {}", cpu, deviceType, intData, level, interruptLevel);
        }
        if (level > 0) {
            setIntActive(level, isPending);
            additionalIntData = intData;
            if (verbose) LOG.info("{} {}{} interrupt pending: {}", cpu, deviceType, intData, level);
        }
    }

    public int readSh2IntMaskReg(int pos, Size size) {
        return readBuffer(sh2_int_mask, pos, size);
    }

    public void setIntActive(Sh2Interrupt interrupt, boolean active) {
        setIntActive(interrupt.ordinal(), active);
    }

    private void setIntActive(int ipt, boolean isPending) {
        istate[ipt].active = isPending;
        resetInterruptLevel();
        logInfo("ACTIVE_PENDING", ipt);
    }

    private void resetInterruptLevel() {
        int newLevel = 0;
        int prev = interruptLevel;
        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            InterruptState ist = istate[i];
            if (ist.active && ist.enable) {
                newLevel = i;
                break;
            }
        }
        interruptLevel = newLevel;
        assert interruptLevel != VRES_14.ordinal();
        if (interruptLevel != prev && interruptLevel > 0) {
            Ow2DrcOptimizer.PollerCtx ctx = SysEventManager.instance.getPoller(cpu);
            if (ctx != NO_POLLER && (ctx.isPollingActive() || ctx.isPollingBusyLoop())) {
                SysEventManager.instance.fireSysEvent(cpu, INT);
            }
        }
    }

    @VisibleForTesting
    public void clearInterrupt(Sh2Interrupt intType) {
        clearInterrupt(intType.ordinal());
    }

    private void clearInterrupt(int ipt) {
//        istate[ipt].active = false;
//        resetInterruptLevel();
//        logInfo("CLEAR", ipt);
    }

    @Override
    public void clearCurrentInterrupt() {
        //only autoclear external (ie.DMA,SCI, etc) interrupts? Yes, according to Ares
        //CHECK: 36 Great Holes Starring Fred Couples (Prototype - Nov 05, 1994) (32X).32x -> doesn't clear VINT=12
        if (intVals[interruptLevel].internal == 0) {
            clearInterrupt(interruptLevel);
        }
    }

    public int getInterruptLevel() {
        return interruptLevel;
    }

    public int getVectorNumber() {
        Sh2Interrupt intType = intVals[interruptLevel];
        if (intType.internal == 0) {
            return getExternalDeviceVectorNumber();
        } else if (intType == NMI_16) {
            return 11;
        }
        return 64 + (interruptLevel >> 1);
    }

    @Override
    public InterruptContext getInterruptContext() {
        throw new RuntimeException("Unexpected!");
    }

    private int getExternalDeviceVectorNumber() {
        Sh2DeviceType deviceType = Sh2DeviceType.NONE;
        for (var entry : onChipDevicePriority.entrySet()) {
            if (interruptLevel == entry.getValue()) {
                deviceType = entry.getKey();
                break;
            }
        }
        int vn = -1;
        if (verbose) LOG.info("{} {} interrupt exec: {}, vector: {}", cpu, deviceType, interruptLevel, th(vn));
        //TODO the vector number should be coming from the device itself
        switch (deviceType) {
            case DMA:
                vn = readBuffer(regs, INTC_VCRDMA0.addr + (additionalIntData << 3), Size.LONG) & 0xFF;
                break;
            case WDT:
                vn = readBuffer(regs, INTC_VCRWDT.addr, Size.BYTE) & 0xFF;
                break;
            case DIV:
                vn = readBuffer(regs, INTC_VCRDIV.addr, Size.BYTE) & 0xFF;
                break;
            case SCI:
                //RIE vs TIE
                int pos = additionalIntData == 1 ? INTC_VCRA.addr + 1 : INTC_VCRB.addr;
                vn = readBuffer(regs, pos, Size.BYTE) & 0xFF;
                break;
            case NONE:
                break;
            default:
                LOG.error("{} Unhandled interrupt for device: {}, level: {}", cpu, deviceType, interruptLevel);
                break;
        }
        return vn;
    }

    public ByteBuffer getSh2_int_mask_regs() {
        return sh2_int_mask;
    }

    private void logInfo(String action, int ipt) {
        if (verbose) {
            LOG.info("{}: {} {}, {} intLevel: {}", action, cpu, ipt, istate[ipt], interruptLevel);
        }
    }

    private void logExternalIntLevel(RegSpec regSpec, int val) {
        if (regSpec == INTC_IPRA) {
            LOG.info("{} set IPRA levels, {}:{}, {}:{}, {}:{}", cpu, Sh2DeviceType.DIV, val >> 12,
                    Sh2DeviceType.DMA, (val >> 8) & 0xF, Sh2DeviceType.WDT, (val >> 4) & 0xF);
        } else if (regSpec == INTC_IPRB) {
            LOG.info("{} set IPRB levels, {}:{}, {}:{}", cpu, Sh2DeviceType.SCI, val >> 12,
                    Sh2DeviceType.FRT, (val >> 8) & 0xF);
        }
    }
}