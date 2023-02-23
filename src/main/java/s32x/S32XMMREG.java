package s32x;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import omegadrive.util.VideoMode;
import org.slf4j.Logger;
import s32x.dict.S32xDict;
import s32x.dict.S32xMemAccessDelay;
import s32x.event.PollSysEventManager;
import s32x.pwm.Pwm;
import s32x.savestate.Gs32xStateHandler;
import s32x.sh2.device.IntControl;
import s32x.sh2.prefetch.Sh2Prefetch;
import s32x.util.Md32xRuntimeData;
import s32x.util.S32xUtil;
import s32x.vdp.MarsVdp;
import s32x.vdp.MarsVdp.MarsVdpContext;
import s32x.vdp.MarsVdpImpl;

import java.io.Serializable;
import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static s32x.dict.S32xDict.SIZE_32X_SYSREG;
import static s32x.dict.S32xDict.SIZE_32X_VDPREG;
import static s32x.sh2.device.IntControl.Sh2Interrupt.CMD_8;
import static s32x.sh2.device.IntControl.Sh2Interrupt.VRES_14;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32XMMREG implements Device {

    private static final Logger LOG = LogHelper.getLogger(S32XMMREG.class.getSimpleName());

    private static final boolean verbose = false, verboseRead = false;

    public static final int CART_INSERTED = 0;
    public static final int CART_NOT_INSERTED = 1;
    //0 = md access, 1 = sh2 access
    public int fm = 0;
    //0 = disabled, 1 = 32x enabled
    public int aden = 0;

    public static class RegContext {
        public final ByteBuffer sysRegsSh2 = ByteBuffer.allocateDirect(SIZE_32X_SYSREG);
        public final ByteBuffer sysRegsMd = ByteBuffer.allocateDirect(SIZE_32X_SYSREG);
        public final ByteBuffer vdpRegs = ByteBuffer.allocateDirect(SIZE_32X_VDPREG);
    }

    private S32XMMREGContext ctx = new S32XMMREGContext();

    public final RegContext regContext = new RegContext();
    private final ByteBuffer sysRegsSh2 = regContext.sysRegsSh2;
    private final ByteBuffer sysRegsMd = regContext.sysRegsMd;

    public IntControl[] interruptControls;
    public Pwm pwm;
    public DmaFifo68k dmaFifoControl;
    private MarsVdp vdp;
    private S32xDict.S32xDictLogContext logCtx;
    private MarsVdpContext vdpContext;
    private int deviceAccessType;

    private static class S32XMMREGContext implements Serializable {
        private int cart = CART_NOT_INSERTED;
        //0 = md access, 1 = sh2 access
        public int fm = 0;
        //0 = disabled, 1 = 32x enabled
        public int aden = 0;
        //0 = Hint disabled during VBlank, 1 = enabled
        private int hen = 0;
        private byte[] sysRegsSh2 = new byte[SIZE_32X_SYSREG], sysRegsMd = new byte[SIZE_32X_SYSREG], vdpRegs = new byte[SIZE_32X_VDPREG];
    }

    public S32XMMREG() {
        init();
    }

    @Override
    public void init() {
        vdpContext = new MarsVdpContext();
        writeBufferWord(S32xDict.RegSpecS32x.MD_ADAPTER_CTRL, S32xDict.P32XS_REN | S32xDict.P32XS_nRES); //from Picodrive
        vdp = MarsVdpImpl.createInstance(vdpContext, this);
        logCtx = new S32xDict.S32xDictLogContext();
        S32xDict.z80RegAccess.clear();
        Gs32xStateHandler.addDevice(this);
    }
    public MarsVdp getVdp() {
        return vdp;
    }

    public void setHBlank(boolean hBlankOn) {
        vdp.setHBlank(hBlankOn, ctx.hen);
    }

    public void setVBlank(boolean vBlankOn) {
        vdp.setVBlank(vBlankOn);
    }

    public void write(int address, int value, Size size) {
        address &= S32xDict.SH2_CACHE_THROUGH_MASK;
        if (address >= S32xDict.START_32X_SYSREG_CACHE && address < S32xDict.END_32X_VDPREG_CACHE) {
            handleRegWrite(address, value, size);
            S32xMemAccessDelay.addWriteCpuDelay(deviceAccessType);
        } else {
            vdp.write(address, value, size);
        }
    }

    public int read(int address, Size size) {
        address &= S32xDict.SH2_CACHE_THROUGH_MASK;
        int res = 0;
        if (address >= S32xDict.START_32X_SYSREG_CACHE && address < S32xDict.END_32X_VDPREG_CACHE) {
            res = handleRegRead(address, size);
            S32xMemAccessDelay.addReadCpuDelay(deviceAccessType);
        } else {
            res = vdp.read(address, size);
        }
        return res;
    }

    private int handleRegRead(int address, Size size) {
        S32xUtil.CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        S32xDict.RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        if (regSpec == S32xDict.RegSpecS32x.INVALID) {
            LOG.error("{} unable to handle read, addr: {} {}", cpu, th(address), size);
            return size.getMask();
        }
        deviceAccessType = regSpec.deviceAccessTypeDelay;
        if (verboseRead) {
            doLog(cpu, regSpec, address, -1, size, true);
        }
        int res = 0;
        switch (regSpec.deviceType) {
            case DMA -> {
                assert (regSpec != S32xDict.RegSpecS32x.MD_DMAC_CTRL ? cpu != S32xUtil.CpuDeviceAccess.Z80 : true) : regSpec;
                res = dmaFifoControl.read(regSpec, cpu, address & S32xDict.S32X_REG_MASK, size);
            }
            case PWM -> res = pwm.read(cpu, regSpec, address & S32xDict.S32X_MMREG_MASK, size);
            case COMM -> res = S32xUtil.readBufferReg(regContext, regSpec, address, size);
            default -> {
                assert (regSpec.addr >= S32xDict.RegSpecS32x.MD_DREQ_SRC_ADDR_H.addr ? cpu != S32xUtil.CpuDeviceAccess.Z80 : true) : regSpec;
                res = S32xUtil.readBufferReg(regContext, regSpec, address, size);
                if (regSpec == S32xDict.RegSpecS32x.SH2_INT_MASK) {
                    res = interruptControls[cpu.ordinal()].readSh2IntMaskReg(address & S32xDict.S32X_REG_MASK, size);
                }
            }
        }
        //RegAccessLogger.regAccess(regSpec.toString(), address, res, size, true);
        return res;
    }

    private boolean handleRegWrite(int address, int value, Size size) {
        final int reg = address & S32xDict.S32X_MMREG_MASK;
        final S32xUtil.CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        final S32xDict.RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        //RegAccessLogger.regAccess(regSpec.toString(), reg, value, size, false);
        boolean regChanged = false;

        assert checkWriteLongAccess(regSpec, reg, size);
        deviceAccessType = regSpec.deviceAccessTypeDelay;

        switch (regSpec.deviceType) {
            case VDP -> {
                assert cpu != S32xUtil.CpuDeviceAccess.Z80 : regSpec;
                regChanged = vdp.vdpRegWrite(regSpec, reg, value, size);
            }
            case PWM -> pwm.write(cpu, regSpec, reg, value, size);
            case COMM -> regChanged = handleCommRegWrite(regSpec, reg, value, size);
            case SYS -> {
                assert (regSpec.addr >= S32xDict.RegSpecS32x.MD_DREQ_SRC_ADDR_H.addr ? cpu != S32xUtil.CpuDeviceAccess.Z80 : true) : regSpec;
                regChanged = handleSysRegWrite(cpu, regSpec, reg, value, size);
            }
            case DMA -> {
                assert (regSpec != S32xDict.RegSpecS32x.MD_DMAC_CTRL ? cpu != S32xUtil.CpuDeviceAccess.Z80 : true) : regSpec;
                dmaFifoControl.write(regSpec, cpu, reg, value, size);
            }
            default -> {
                LOG.error("{} unexpected reg write, addr: {}, {} {}", cpu, th(address), th(value), size);
                S32xUtil.writeBufferReg(regContext, regSpec, reg, value, size);
                regChanged = true;
            }
        }
        if (verbose && regChanged) {
            doLog(cpu, regSpec, address, value, size, false);
        }
        if (regChanged) {
            Sh2Prefetch.checkPoller(cpu, regSpec.deviceType, address, value, size);
        }
        return regChanged;
    }

    private boolean handleSysRegWrite(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        assert size != Size.LONG;
        boolean regChanged = false;
        switch (regSpec) {
            case SH2_INT_MASK, MD_ADAPTER_CTRL -> regChanged = handleReg0Write(cpu, reg, value, size);
            case SH2_STBY_CHANGE, MD_INT_CTRL -> regChanged = handleReg2Write(cpu, reg, value, size);
            case SH2_HCOUNT_REG, MD_BANK_SET -> regChanged = handleReg4Write(cpu, reg, value, size);
            case SH2_VINT_CLEAR, SH2_HINT_CLEAR, SH2_PWM_INT_CLEAR, SH2_CMD_INT_CLEAR, SH2_VRES_INT_CLEAR -> {
                handleIntClearWrite(cpu, regSpec, reg, value, size);
                regChanged = true;
            }
            case MD_SEGA_TV -> {
                LOG.warn("{} {} unexpected write, addr: {}, {} {}", cpu, regSpec, th(reg), th(value), size);
                S32xUtil.writeBufferReg(regContext, regSpec, reg, value, size);
            }
            default -> {
                LOG.error("{} sysReg unexpected write, addr: {}, {} {}", cpu, th(reg), th(value), size);
                S32xUtil.writeBufferReg(regContext, regSpec, reg, value, size);
            }
        }
        return regChanged;
    }

    private boolean handleCommRegWrite(final S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        int currentVal = S32xUtil.readBufferReg(regContext, regSpec, reg, size);
        boolean regChanged = currentVal != value;
        if (regChanged) {
            //comm regs are shared
            S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, reg, value, size);
        }
        return regChanged;
    }

    private void doLog(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int address, int value, Size size, boolean read) {
        boolean isSys = address < S32xDict.END_32X_SYSREG_CACHE;
        ByteBuffer regArea = isSys ? (cpu == S32xUtil.CpuDeviceAccess.M68K ? sysRegsMd : sysRegsSh2) : regContext.vdpRegs;
        logCtx.cpu = Md32xRuntimeData.getAccessTypeExt();
        logCtx.regSpec = regSpec;
        logCtx.regArea = regArea;
        logCtx.read = read;
        logCtx.fbD = vdpContext.frameBufferDisplay;
        logCtx.fbW = vdpContext.frameBufferWritable;
        S32xDict.checkName(logCtx.cpu, regSpec, address, size);
        S32xDict.logAccess(logCtx, address, value, size);
        S32xDict.detectRegAccess(logCtx, address, value, size);
        S32xDict.logZ80Access(cpu, regSpec, address, size, read);
    }

    private void handleIntClearWrite(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        assert cpu == S32xUtil.CpuDeviceAccess.MASTER || cpu == S32xUtil.CpuDeviceAccess.SLAVE;
        int intIdx = VRES_14.ordinal() - ((reg & ~1) - 0x14); //regEven
        IntControl.Sh2Interrupt intType = IntControl.intVals[intIdx];
        interruptControls[cpu.ordinal()].clearInterrupt(intType);
        //autoclear Int_control_reg too
        if (intType == CMD_8) {
            int newVal = readWordFromBuffer(S32xDict.RegSpecS32x.MD_INT_CTRL) & ~(1 << cpu.ordinal());
            boolean change = handleIntControlWriteMd(S32xDict.RegSpecS32x.MD_INT_CTRL.addr, newVal, Size.WORD);
            if (change && verbose) {
                LOG.info("{} auto clear {}", cpu, intType);
            }
        }
        //TODO xmen reads the write-only reg but ignores the value
        //TODO other sw doing it?
//        writeBufferReg(regContext, regSpec, reg, value, size);
    }

    private boolean handleReg4Write(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        return switch (cpu.regSide) {
            case MD -> {
                if (size == Size.BYTE && (reg & 1) == 0) {
                    LOG.warn("Ignore bank set write on byte {}, {} {}", th(reg), th(value), size);
                    yield false;
                } else {
                    yield S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.MD_BANK_SET, sysRegsMd, reg, value & 3, size);
                }
            }
            case SH2 ->
                    S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.SH2_HCOUNT_REG, sysRegsSh2, reg, value & 0xFF, size);
        };
    }

    private boolean handleReg2Write(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        boolean res = switch (cpu.regSide) {
            case MD -> handleIntControlWriteMd(reg, value, size);
            case SH2 -> S32xUtil.writeBuffer(sysRegsSh2, reg, value, size);
        };
        return res;
    }

    private boolean handleIntControlWriteMd(int reg, int value, Size size) {
        boolean changed = S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.MD_INT_CTRL, sysRegsMd, reg, value, size);
        if (changed) {
            int newVal = S32xUtil.readBufferWord(sysRegsMd, S32xDict.RegSpecS32x.MD_INT_CTRL.addr) & S32xDict.RegSpecS32x.MD_INT_CTRL.writeAndMask;
            boolean intm = (newVal & 1) > 0;
            boolean ints = (newVal & 2) > 0;
            interruptControls[0].setIntPending(CMD_8, intm);
            interruptControls[1].setIntPending(CMD_8, ints);
            writeBufferWord(S32xDict.RegSpecS32x.MD_INT_CTRL, newVal);
        }
        return changed;
    }

    private boolean handleReg0Write(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        boolean res = switch (cpu.regSide) {
            case MD -> handleAdapterControlRegWriteMd(reg, value, size);
            case SH2 -> handleIntMaskRegWriteSh2(cpu, reg, value, size);
        };
        return res;
    }


    private boolean handleAdapterControlRegWriteMd(int reg, int value, Size size) {
        assert size != Size.LONG;
        int val = readWordFromBuffer(S32xDict.RegSpecS32x.MD_ADAPTER_CTRL);
        S32xUtil.writeBufferReg(regContext, S32xDict.RegSpecS32x.MD_ADAPTER_CTRL, reg, value, size);

        int newVal = (readWordFromBuffer(S32xDict.RegSpecS32x.MD_ADAPTER_CTRL) & S32xDict.RegSpecS32x.MD_ADAPTER_CTRL.writeAndMask) |
                S32xDict.RegSpecS32x.MD_ADAPTER_CTRL.writeOrMask; //force REN
        if (aden > 0 && (newVal & 1) == 0) {
            System.out.println("#### Disabling ADEN not allowed");
            newVal |= 1;
        }
        handleReset(val, newVal);
        writeBufferWord(S32xDict.RegSpecS32x.MD_ADAPTER_CTRL, newVal);
        setAdenSh2Reg(newVal & 1); //sh2 side read-only
        updateFmShared(newVal); //sh2 side r/w too
        return val != newVal;
    }

    private void handleReset(int val, int newVal) {
        //reset cancel
        if ((val & S32xDict.P32XS_nRES) == 0 && (newVal & S32xDict.P32XS_nRES) > 0) {
            LOG.info("{} unset reset Sh2s (nRes = 0)", Md32xRuntimeData.getAccessTypeExt());
            PollSysEventManager.instance.fireSysEvent(S32xUtil.CpuDeviceAccess.M68K, PollSysEventManager.SysEvent.SH2_RESET_OFF);
//            bus.resetSh2(); //TODO check
        }
        //reset
        if ((val & S32xDict.P32XS_nRES) > 0 && (newVal & S32xDict.P32XS_nRES) == 0) {
            LOG.info("{} set reset SH2s (nRes = 1)", Md32xRuntimeData.getAccessTypeExt());
            PollSysEventManager.instance.fireSysEvent(S32xUtil.CpuDeviceAccess.M68K, PollSysEventManager.SysEvent.SH2_RESET_ON);
        }
    }

    private void updateFmShared(int wordVal) {
        if (fm != ((wordVal >> 15) & 1)) {
            setFmSh2Reg((wordVal >> 15) & 1);
        }
    }

    private void updateHenShared(int newVal) {
        int nhen = (newVal >> S32xDict.INTMASK_HEN_BIT_POS) & 1;
        if (nhen != ctx.hen) {
            ctx.hen = nhen;
            if (verbose) LOG.info("{} HEN: {}", Md32xRuntimeData.getAccessTypeExt(), ctx.hen);
        }
        S32xUtil.setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 1, S32xDict.INTMASK_HEN_BIT_POS, ctx.hen, Size.BYTE);
    }

    private boolean handleIntMaskRegWriteSh2(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        assert size != Size.LONG;
        int baseReg = reg & ~1;
        final IntControl ic = interruptControls[cpu.ordinal()];
        int prevW = ic.readSh2IntMaskReg(baseReg, Size.WORD);
        S32xUtil.writeBuffer(ic.getSh2_int_mask_regs(), reg, value, size);
        //reset ctx.cart and aden bits
        int newVal = (ic.readSh2IntMaskReg(baseReg, Size.WORD) & 0x808F) | (ctx.cart << 8 | aden << 9);
        assert (newVal & 0x7c70) == 0; //unused bits
        S32xUtil.writeBuffer(ic.getSh2_int_mask_regs(), baseReg, newVal, Size.WORD);
//        assert (newVal & P32XS2_ADEN) > 0 && (newVal & P32XS_nCART) == CART_INSERTED;
        ic.reloadSh2IntMask();
        updateFmShared(newVal); //68k side r/w too
        updateHenShared(newVal); //M,S share the same value
        return newVal != prevW;
    }

    public void setCart(int cartSize) {
        ctx.cart = (cartSize > 0) ? CART_INSERTED : CART_NOT_INSERTED;
        S32xUtil.setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 0, ctx.cart, Size.BYTE);
        LOG.info("Cart set to {}inserted: {}", (ctx.cart > 0 ? "not " : ""), ctx.cart);
    }

    private void setAdenSh2Reg(int aden) {
        this.aden = aden;
        S32xUtil.setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 1, aden, Size.BYTE);
    }

    private void setFmSh2Reg(int fm) {
        this.fm = fm;
        S32xUtil.setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 7, fm, Size.BYTE);
        S32xUtil.setBit(sysRegsMd, S32xDict.RegSpecS32x.MD_ADAPTER_CTRL.addr, 7, fm, Size.BYTE);
        if (verbose) LOG.info("{} FM: {}", Md32xRuntimeData.getAccessTypeExt(), fm);
    }

    public void setDmaControl(DmaFifo68k dmaFifoControl) {
        this.dmaFifoControl = dmaFifoControl;
    }

    public void setInterruptControl(IntControl... interruptControls) {
        this.interruptControls = interruptControls;
    }

    public void setPwm(Pwm pwm) {
        this.pwm = pwm;
    }

    private int readWordFromBuffer(S32xDict.RegSpecS32x reg) {
        return S32xUtil.readWordFromBuffer(regContext, reg);
    }

    private void writeBufferWord(S32xDict.RegSpecS32x reg, int value) {
        S32xUtil.writeBufferReg(regContext, reg, reg.addr, value, Size.WORD);
    }

    private void setBitFromWord(S32xDict.RegSpecS32x reg, int pos, int value) {
        S32xUtil.setBitReg(regContext, reg, reg.addr, pos, value, Size.WORD);
    }

    private boolean checkWriteLongAccess(S32xDict.RegSpecS32x regSpec, int reg, Size size) {
        if (regSpec.deviceType != S32xDict.S32xRegType.COMM && regSpec.deviceType != S32xDict.S32xRegType.VDP && regSpec.deviceType != S32xDict.S32xRegType.PWM && size == Size.LONG) {
            LOG.error("unsupported 32 bit access, reg: {} {}", regSpec.name, th(reg));
            return reg == 0x2c; //FIFA
        }
        return true;
    }

    public void updateVideoMode(VideoMode value) {
        vdp.updateVideoMode(value);
    }

    @Override
    public void saveContext(ByteBuffer buffer) {
        Device.super.saveContext(buffer);
        regContext.sysRegsSh2.rewind().get(ctx.sysRegsSh2).rewind();
        regContext.sysRegsMd.rewind().get(ctx.sysRegsMd).rewind();
        regContext.vdpRegs.rewind().get(ctx.vdpRegs).rewind();
        ctx.fm = fm;
        ctx.aden = aden;
        buffer.put(Util.serializeObject(ctx));
    }

    @Override
    public void loadContext(ByteBuffer buffer) {
        Device.super.loadContext(buffer);
        Serializable s = Util.deserializeObject(buffer.array(), 0, buffer.capacity());
        assert s instanceof S32XMMREGContext;
        ctx = (S32XMMREGContext) s;
        regContext.sysRegsMd.rewind().put(ctx.sysRegsMd).rewind();
        regContext.sysRegsSh2.rewind().put(ctx.sysRegsSh2).rewind();
        regContext.vdpRegs.rewind().put(ctx.vdpRegs).rewind();
        fm = ctx.fm;
        aden = ctx.aden;
    }
}