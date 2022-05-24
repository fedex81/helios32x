package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.Sh2Dict.RegSpec;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.sh2.device.IntControl.OnChipSubType.DMA_C0;
import static sh2.sh2.device.IntControl.OnChipSubType.RXI;
import static sh2.sh2.device.IntControl.Sh2Interrupt.CMD_8;
import static sh2.sh2.device.IntControl.Sh2InterruptSource.getSh2InterruptSource;
import static sh2.sh2.device.IntControl.Sh2InterruptSource.vals;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 */
public class IntControlImplNew implements IntControl {

    private static final Logger LOG = LogManager.getLogger(IntControlImplNew.class.getSimpleName());

    private static final boolean verbose = false;

    private final Map<Sh2DeviceType, Integer> onChipDevicePriority;
    private final Map<Sh2InterruptSource, InterruptContext> s32xInt;

    static final int VALID_BIT_POS = 0;
    static final int PENDING_BIT_POS = 1;
    static final int TRIGGER_BIT_POS = 2;

    static final int INT_VALID_MASK = 1 << VALID_BIT_POS;
    static final int INT_PENDING_MASK = 1 << PENDING_BIT_POS;
    static final int INT_TRIGGER_MASK = 1 << TRIGGER_BIT_POS;

    private InterruptContext currentInterrupt = LEV_0;

    // V, H, CMD and PWM each possesses exclusive address on the master side and the slave side.
    private ByteBuffer sh2_int_mask;
    private ByteBuffer regs;
    private CpuDeviceAccess cpu;

    private static final boolean legacy = true;

    public static IntControl createInstance(CpuDeviceAccess cpu, ByteBuffer regs) {
        return legacy ? new IntControlImplOld(cpu, regs) : new IntControlImplNew(cpu, regs);
    }

    public IntControlImplNew(CpuDeviceAccess cpu, ByteBuffer regs) {
        sh2_int_mask = ByteBuffer.allocateDirect(2);
        this.regs = regs;
        this.cpu = cpu;
        this.s32xInt = new HashMap<>(Sh2InterruptSource.values().length);
        this.onChipDevicePriority = new HashMap<>();
        init();
    }

    @Override
    public void init() {
        s32xInt.clear();
        onChipDevicePriority.clear();
        Arrays.stream(Sh2DeviceType.values()).forEach(d -> onChipDevicePriority.put(d, 0));
        Arrays.stream(Sh2InterruptSource.values()).forEach(s -> {
            InterruptContext intCtx = new InterruptContext();
            intCtx.source = s;
            s32xInt.put(s, intCtx);
        });
        setIntsMasked(0);
    }

    @Override
    public void write(RegSpec regSpec, int pos, int value, Size size) {
        int val = 0;
        writeBuffer(regs, pos, value, size);
        switch (regSpec) {
            case INTC_IPRA:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                onChipDevicePriority.put(DIV, val >> 12);
                onChipDevicePriority.put(DMA, (val >> 8) & 0xF);
                onChipDevicePriority.put(WDT, (val >> 4) & 0xF);
                logExternalIntLevel(regSpec, val);
                break;
            case INTC_IPRB:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                onChipDevicePriority.put(SCI, val >> 12);
                onChipDevicePriority.put(FRT, (val >> 8) & 0xF);
                logExternalIntLevel(regSpec, val);
                break;
            case INTC_ICR:
                val = readBuffer(regs, regSpec.addr, Size.WORD);
                if ((val & 1) > 0) {
                    LOG.error("{} Not supported: IRL Interrupt vector mode: External Vector", cpu);
                }
                break;
            case INTC_VCRA:
            case INTC_VCRB:
            case INTC_VCRC:
            case INTC_VCRD:
                LOG.error("{} Not supported: {}, val {} {}", cpu, regSpec.name, th(value), size);
                break;
        }
    }

    @Override
    public int read(RegSpec regSpec, int reg, Size size) {
        if (verbose) LOG.info("{} Read {} value: {} {}", cpu, regSpec.name, th(readBuffer(regs, reg, size)), size);
        return readBuffer(regs, reg, size);
    }

    private InterruptContext getContextFromExternalInterrupt(Sh2Interrupt intp) {
        InterruptContext source = null;
        for (var e : s32xInt.entrySet()) {
            if (e.getKey().externalInterrupt == intp) {
                source = e.getValue();
                break;
            }
        }
        assert source != null;
        return source;
    }

    private void setIntMasked(int ipt, int isValid) {
        Sh2Interrupt sh2Interrupt = intVals[ipt];
        InterruptContext source = getContextFromExternalInterrupt(sh2Interrupt);
        boolean change = (source.intState & INT_VALID_MASK) != isValid;
        if (change) {
            setBit(source, VALID_BIT_POS, isValid);
            //TODO check
//            if (!isTrigger || ipt == CMD_8.ordinal()) {
            if (ipt == CMD_8.ordinal()) {
                setBit(source, TRIGGER_BIT_POS, (isValid > 0) && (source.intState & INT_PENDING_MASK) > 0);
            }
            resetInterruptLevel();
            logInfo("MASK", source);
        }
    }

    public void setIntsMasked(int value) {
        for (int i = 0; i < 4; i++) {
            int imask = value & (1 << i);
            //0->PWM_6, 1->CMD_8, 2->HINT_10, 3->VINT_12
            int sh2Int = 6 + (i << 1);
            setIntMasked(sh2Int, imask > 0 ? 1 : 0);
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

    public void setOnChipDeviceIntPending(Sh2DeviceType deviceType, OnChipSubType subType) {
        int level = onChipDevicePriority.get(deviceType);
        Sh2InterruptSource source = getSh2InterruptSource(deviceType, subType);
        assert source != null;
        InterruptContext intCtx = s32xInt.get(source);
        assert intCtx != null;
        intCtx.source = source;
        intCtx.level = onChipDevicePriority.get(deviceType);
        if (level > 0) {
            setBit(intCtx, PENDING_BIT_POS, 1);
            setBit(intCtx, TRIGGER_BIT_POS, 1);
            if (verbose) LOG.info("{} {}{} interrupt pending: {}", cpu, deviceType, subType, level);
            resetInterruptLevel();
        }
    }

    public int readSh2IntMaskReg(int pos, Size size) {
        return readBuffer(sh2_int_mask, pos, size);
    }

    private void setIntPending(int ipt, boolean isPending) {
        InterruptContext source = getContextFromExternalInterrupt(intVals[ipt]);
        boolean val = (source.intState & INT_PENDING_MASK) > 0;
        if (val != isPending) {
            boolean valid = (source.intState & INT_VALID_MASK) > 0;
            if (valid) {
                setBit(source, PENDING_BIT_POS, isPending);
                if (valid && isPending) {
                    setBit(source, TRIGGER_BIT_POS, 1);
                    source.level = ipt;
                    resetInterruptLevel();
                } else {
                    setBit(source, TRIGGER_BIT_POS, 0);
                }
                logInfo("PENDING", source);
            }
        }
    }

    private void resetInterruptLevel() {
        int maxLevel = s32xInt.values().stream().filter(s -> s.intState > INT_TRIGGER_MASK).
                max((c1, c2) -> Integer.compare(c1.level, c2.level)).map(ctx -> ctx.level).orElse(0);
        assert maxLevel >= 0;
        if (maxLevel > 0) {
//            assert s32xInt.values().stream().filter(c -> c.level == maxLevel).count() < 2; //TODO debug if it happens
            //order is important
            InterruptContext ctx = Arrays.stream(vals).map(s32xInt::get).filter(ic -> ic.level == maxLevel).
                    findFirst().orElse(null);
            assert ctx != null;
            if (verbose && currentInterrupt != ctx) {
                LOG.info("{} Level change: {} -> {}", cpu, currentInterrupt, ctx);
            }
            currentInterrupt = ctx;
        } else {
            currentInterrupt = LEV_0;
        }
    }

    public void clearInterrupt(Sh2Interrupt intType) {
        clearInterrupt(getContextFromExternalInterrupt(intType));
    }

    public void clearInterrupt(InterruptContext source) {
        source.intState &= ~(INT_PENDING_MASK | INT_TRIGGER_MASK);
        source.level = 0;
        resetInterruptLevel();
        logInfo("CLEAR", source);
    }

    public void clearCurrentInterrupt() {
        //only autoclear onChip (ie.DMA,SCI, etc) interrupts
        if (currentInterrupt.source.externalInterrupt.internal == 0) {
            clearInterrupt(currentInterrupt);
            currentInterrupt = LEV_0;
        }
    }

    public InterruptContext getInterruptContext() {
        return currentInterrupt;
    }

    public int getVectorNumber() {
        if (currentInterrupt.source.externalInterrupt.internal == 0) {
            return getOnChipDeviceVectorNumber(currentInterrupt);
        }
        return 64 + (currentInterrupt.level >> 1);
    }

    private int getOnChipDeviceVectorNumber(InterruptContext ctx) {
        int vn = -1;
        if (verbose) LOG.info("{} {} interrupt exec: {}, vector: {}", cpu, ctx.source.deviceType, ctx.level, th(vn));
        //TODO the vector number should be coming from the device itself
        switch (ctx.source.deviceType) {
            case DMA:
                int offset = ctx.source.subType == DMA_C0 ? 0 : 1;
                vn = readBuffer(regs, INTC_VCRDMA0.addr + (offset << 3), Size.LONG) & 0xFF;
                break;
            case WDT:
                vn = readBuffer(regs, INTC_VCRWDT.addr, Size.BYTE) & 0xFF;
                break;
            case DIV:
                vn = readBuffer(regs, INTC_VCRDIV.addr, Size.BYTE) & 0xFF;
                break;
            case SCI:
                //TODO
                //RIE vs TIE
                int offset1 = ctx.source.subType == RXI ? 0 : 1;
                int pos = offset1 == 1 ? INTC_VCRA.addr + 1 : INTC_VCRB.addr;
                vn = readBuffer(regs, pos, Size.BYTE) & 0xFF;
                break;
            case NONE:
                break;
            default:
                LOG.error("{} Unhandled interrupt for device: {}, level: {}", cpu, ctx.source.deviceType, ctx.level);
                break;
        }
        return vn;
    }

    public ByteBuffer getSh2_int_mask_regs() {
        return sh2_int_mask;
    }

    private void logInfo(String action, InterruptContext source) {
        if (verbose) {
            LOG.info("{}: {} {} valid (unmasked): {}, pending: {}, willTrigger: {}, intLevel: {}",
                    action, cpu, source, (source.intState & INT_VALID_MASK) > 0,
                    (source.intState & INT_PENDING_MASK) > 0, (source.intState & INT_TRIGGER_MASK) > 0,
                    source.level);
        }
    }

    private void logExternalIntLevel(RegSpec regSpec, int val) {
        if (regSpec == INTC_IPRA) {
            LOG.info("{} set IPRA levels, {}:{}, {}:{}, {}:{}", cpu, DIV, val >> 12,
                    DMA, (val >> 8) & 0xF, WDT, (val >> 4) & 0xF);
        } else if (regSpec == INTC_IPRB) {
            LOG.info("{} set IPRB levels, {}:{}, {}:{}", cpu, SCI, val >> 12,
                    FRT, (val >> 8) & 0xF);
        }
    }

    private static void setBit(InterruptContext ctx, int bitPos, boolean bitValue) {
        setBit(ctx, bitPos, bitValue ? 1 : 0);
    }

    private static void setBit(InterruptContext ctx, int bitPos, int bitValue) {
        ctx.intState = (ctx.intState & ~(1 << bitPos)) | (bitValue << bitPos);
    }

    @Override
    public void reset() {
        writeRegBuffer(INTC_IPRA, regs, 0, Size.WORD);
        writeRegBuffer(INTC_IPRB, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRA, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRB, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRC, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRD, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRWDT, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRDIV, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRDMA0, regs, 0, Size.WORD);
        writeRegBuffer(INTC_VCRDMA1, regs, 0, Size.WORD);
        writeRegBuffer(INTC_ICR, regs, 0, Size.WORD);
    }
}