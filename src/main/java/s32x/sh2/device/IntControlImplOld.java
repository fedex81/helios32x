package s32x.sh2.device;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.slf4j.Logger;
import s32x.dict.S32xDict;
import s32x.dict.Sh2Dict.RegSpec;
import s32x.event.SysEventManager;
import s32x.sh2.drc.Ow2DrcOptimizer;
import s32x.util.S32xUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;
import static s32x.dict.Sh2Dict.RegSpec.*;
import static s32x.dict.Sh2Dict.writeBufferWithMask;
import static s32x.sh2.drc.Ow2DrcOptimizer.NO_POLLER;
import static s32x.util.S32xUtil.readBufferLong;

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

    private final Map<Sh2DeviceHelper.Sh2DeviceType, Integer> onChipDevicePriority = new HashMap<>();
    private final Map<Integer, Sh2Interrupt> s32xInt = new HashMap<>(MAX_LEVEL);

    //valid = not masked
    private final boolean[] intValid = new boolean[MAX_LEVEL];
    private final boolean[] intPending = new boolean[MAX_LEVEL];
    private final boolean[] intTrigger = new boolean[MAX_LEVEL];

    // V, H, CMD and PWM each possesses exclusive address on the master side and the slave side.
    private final ByteBuffer sh2_int_mask;
    private final ByteBuffer regs;
    private int interruptLevel;
    private final S32xUtil.CpuDeviceAccess cpu;
    private int additionalIntData = 0;

    public IntControlImplOld(S32xUtil.CpuDeviceAccess cpu, ByteBuffer regs) {
        sh2_int_mask = ByteBuffer.allocateDirect(2);
        this.regs = regs;
        this.cpu = cpu;
        init();
    }

    @Override
    public void init() {
        Arrays.fill(intValid, true);
        setIntsMasked(0);
        Arrays.stream(Sh2DeviceHelper.Sh2DeviceType.values()).forEach(d -> onChipDevicePriority.put(d, 0));
    }

    @Override
    public void write(RegSpec regSpec, int pos, int value, Size size) {
        boolean changed = S32xUtil.writeBuffer(regs, pos, value, size);
        int val = writeBufferWithMask(regs, regSpec);
        if (!changed) {
            return;
        }
        switch (regSpec) {
            case INTC_IPRA:
                onChipDevicePriority.put(Sh2DeviceHelper.Sh2DeviceType.DIV, val >> 12);
                onChipDevicePriority.put(Sh2DeviceHelper.Sh2DeviceType.DMA, (val >> 8) & 0xF);
                onChipDevicePriority.put(Sh2DeviceHelper.Sh2DeviceType.WDT, (val >> 4) & 0xF);
                logOnChipIntLevel(regSpec, val);
                break;
            case INTC_IPRB:
                onChipDevicePriority.put(Sh2DeviceHelper.Sh2DeviceType.SCI, val >> 12);
                onChipDevicePriority.put(Sh2DeviceHelper.Sh2DeviceType.FRT, (val >> 8) & 0xF);
                logOnChipIntLevel(regSpec, val);
                break;
            case INTC_ICR:
                //TODO do not overwrite bit#15
                if ((val & 1) > 0) {
                    LOG.error("{} Not supported: IRL Interrupt vector mode: External Vector", cpu);
                }
                break;
        }
    }

    @Override
    public int read(RegSpec regSpec, int reg, Size size) {
        if (verbose)
            LOG.info("{} Read {} value: {} {}", cpu, regSpec.name, Util.th(S32xUtil.readBuffer(regs, reg, size)), size);
        return S32xUtil.readBuffer(regs, reg, size);
    }

    private void setIntMasked(int ipt, boolean isValid) {
        boolean val = this.intValid[ipt];
        if (val != isValid) {
            this.intValid[ipt] = isValid;
            boolean isPending = this.intPending[ipt];
            boolean isTrigger = this.intTrigger[ipt];
            //TODO check
//            if (!isTrigger || ipt == CMD_8.ordinal()) {
            if (ipt == Sh2Interrupt.CMD_8.ordinal()) {
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

    @Override
    public void reloadSh2IntMask() {
        int newVal = S32xUtil.readBufferWord(sh2_int_mask, S32xDict.RegSpecS32x.SH2_INT_MASK.addr);
        setIntsMasked(newVal & 0xF);
    }

    @Override
    public void setOnChipDeviceIntPending(Sh2DeviceHelper.Sh2DeviceType deviceType, OnChipSubType subType) {
        int data = subType == OnChipSubType.DMA_C1 ? 1 : 0;
        data = subType == OnChipSubType.RXI ? 1 : data;
        setExternalIntPending(deviceType, data, true);
    }

    public void setIntPending(Sh2Interrupt interrupt, boolean isPending) {
        setIntPending(interrupt.ordinal(), isPending);
    }

    public void setExternalIntPending(Sh2DeviceHelper.Sh2DeviceType deviceType, int intData, boolean isPending) {
        int level = onChipDevicePriority.get(deviceType);
        if (interruptLevel > 0 && interruptLevel < level) {
            LOG.info("{} {}{} ext interrupt pending: {}, level: {}", cpu, deviceType, intData, level, interruptLevel);
        }
        if (level > 0) {
            setIntPending(level, isPending);
            additionalIntData = intData;
            if (verbose) LOG.info("{} {}{} interrupt pending: {}", cpu, deviceType, intData, level);
        }
    }

    public int readSh2IntMaskReg(int pos, Size size) {
        return S32xUtil.readBuffer(sh2_int_mask, pos, size);
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
        int prev = interruptLevel;
        for (int i = MAX_LEVEL - 1; i >= 0; i--) {
            if (ints[i]) {
                newLevel = i;
                break;
            }
        }
        interruptLevel = newLevel;
        if (interruptLevel != prev && interruptLevel > 0) {
            Ow2DrcOptimizer.PollerCtx ctx = SysEventManager.instance.getPoller(cpu);
            if (ctx != NO_POLLER && (ctx.isPollingActive() || ctx.isPollingBusyLoop())) {
                SysEventManager.instance.fireSysEvent(cpu, SysEventManager.SysEvent.INT);
            }
        }
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

    public void clearCurrentInterrupt() {
        //only autoclear external (ie.DMA,SCI, etc) interrupts? NO
        //36 Great Holes Starring Fred Couples (Prototype - Nov 05, 1994) (32X).32x
        //doesn't clear VINT=12
//        if(intVals[interruptLevel].internal == 0) {
        clearInterrupt(interruptLevel);
//        }
    }

    public int getInterruptLevel() {
        return interruptLevel;
    }

    public int getVectorNumber() {
        Sh2Interrupt intType = intVals[interruptLevel];
        //TODO perf
        boolean onDev = onChipDevicePriority.values().stream().anyMatch(v -> v.intValue() == interruptLevel);
        if (onDev && intType.internal != 0) { //sopwith32x
            LOG.warn("OnChipDevice interrupt using the same level as an internal interrupt: {]", interruptLevel);
        }
        if (onDev || intType.internal == 0) {
            return getExternalDeviceVectorNumber();
        } else if (intType == Sh2Interrupt.NMI_16) {
            return 11;
        }
        return 64 + (interruptLevel >> 1);
    }

    @Override
    public InterruptContext getInterruptContext() {
        throw new RuntimeException("Unexpected!");
    }

    private int getExternalDeviceVectorNumber() {
        Sh2DeviceHelper.Sh2DeviceType deviceType = Sh2DeviceHelper.Sh2DeviceType.NONE;
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
                vn = readBufferLong(regs, INTC_VCRDMA0.addr + (additionalIntData << 3)) & 0x7F;
                break;
            case WDT:
                vn = S32xUtil.readBufferByte(regs, INTC_VCRWDT.addr) & 0x7F;
                break;
            case DIV:
                vn = S32xUtil.readBufferByte(regs, INTC_VCRDIV.addr) & 0x7F;
                break;
            case SCI:
                //RIE vs TIE
                int pos = additionalIntData == 1 ? INTC_VCRA.addr + 1 : INTC_VCRB.addr;
                vn = S32xUtil.readBufferByte(regs, pos) & 0x7F;
                break;
            case FRT:
                vn = S32xUtil.readBufferByte(regs, INTC_VCRD.addr) & 0x7F; //TODO
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
            LOG.info("{}: {} {} valid (unmasked): {}, pending: {}, willTrigger: {}, intLevel: {}",
                    action, cpu, ipt, intValid[ipt], intPending[ipt], intTrigger[ipt], interruptLevel);
        }
    }

    private void logOnChipIntLevel(RegSpec regSpec, int val) {
        if (regSpec == INTC_IPRA) {
            LOG.info("{} set IPRA levels, {}:{}, {}:{}, {}:{}", cpu, Sh2DeviceHelper.Sh2DeviceType.DIV, val >> 12,
                    Sh2DeviceHelper.Sh2DeviceType.DMA, (val >> 8) & 0xF, Sh2DeviceHelper.Sh2DeviceType.WDT, (val >> 4) & 0xF);
        } else if (regSpec == INTC_IPRB) {
            LOG.info("{} set IPRB levels, {}:{}, {}:{}", cpu, Sh2DeviceHelper.Sh2DeviceType.SCI, val >> 12,
                    Sh2DeviceHelper.Sh2DeviceType.FRT, (val >> 8) & 0xF);
        }
    }
}