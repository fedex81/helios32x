package sh2;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.slf4j.Logger;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.event.SysEventManager;
import sh2.pwm.Pwm;
import sh2.sh2.device.IntControl;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.MarsVdpContext;
import sh2.vdp.MarsVdpImpl;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.dict.S32xDict.S32xRegType.*;
import static sh2.sh2.device.IntControl.Sh2Interrupt.CMD_8;
import static sh2.sh2.device.IntControl.Sh2Interrupt.VRES_14;

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

    //0 = cart inserted, 1 = otherwise
    private int cart = CART_NOT_INSERTED;
    //0 = md access, 1 = sh2 access
    public int fm = 0;
    //0 = disabled, 1 = 32x enabled
    public int aden = 0;
    //0 = Hint disabled during VBlank, 1 = enabled
    private int hen = 0;

    public static class RegContext {
        public ByteBuffer sysRegsSh2 = ByteBuffer.allocate(SIZE_32X_SYSREG);
        public ByteBuffer sysRegsMd = ByteBuffer.allocate(SIZE_32X_SYSREG);
        public ByteBuffer vdpRegs = ByteBuffer.allocate(SIZE_32X_VDPREG);
    }

    public RegContext regContext = new RegContext();
    private final ByteBuffer sysRegsSh2 = regContext.sysRegsSh2;
    private final ByteBuffer sysRegsMd = regContext.sysRegsMd;

    public IntControl[] interruptControls;
    public Pwm pwm;
    public DmaFifo68k dmaFifoControl;
    private MarsVdp vdp;
    private S32xBus bus;
    private S32xDictLogContext logCtx;
    private MarsVdpContext vdpContext;
    private int deviceAccessType;

    public S32XMMREG() {
        init();
    }

    @Override
    public void init() {
        vdpContext = new MarsVdpContext();
        writeBufferWord(MD_ADAPTER_CTRL, P32XS_REN | P32XS_nRES); //from Picodrive
        vdp = MarsVdpImpl.createInstance(vdpContext, this);
        logCtx = new S32xDictLogContext();
        z80RegAccess.clear();
    }

    public void setBus(S32xBus bus) {
        this.bus = bus;
    }

    public MarsVdp getVdp() {
        return vdp;
    }

    public void setHBlank(boolean hBlankOn) {
        vdp.setHBlank(hBlankOn, hen);
    }

    public void setVBlank(boolean vBlankOn) {
        vdp.setVBlank(vBlankOn);
    }

    public void write(int address, int value, Size size) {
        address &= SH2_CACHE_THROUGH_MASK;
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            handleRegWrite(address, value, size);
            S32xMemAccessDelay.addWriteCpuDelay(deviceAccessType);
        } else {
            vdp.write(address, value, size);
        }
    }

    public int read(int address, Size size) {
        address &= SH2_CACHE_THROUGH_MASK;
        int res = 0;
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            res = handleRegRead(address, size);
            S32xMemAccessDelay.addReadCpuDelay(deviceAccessType);
        } else {
            res = vdp.read(address, size);
        }
        return res;
    }

    private int handleRegRead(int address, Size size) {
        CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        if (regSpec == INVALID) {
            LOG.error("{} unable to handle read, addr: {} {}", cpu, th(address), size);
            return (int) size.getMask();
        }
        deviceAccessType = regSpec.deviceAccessTypeDelay;
        if (verboseRead) {
            doLog(cpu, regSpec, address, -1, size, true);
        }
        int res = 0;
        switch (regSpec.deviceType) {
            case DMA -> {
                assert (regSpec != MD_DMAC_CTRL ? cpu != Z80 : true) : regSpec;
                res = dmaFifoControl.read(regSpec, cpu, address & S32X_REG_MASK, size);
            }
            case PWM -> res = pwm.read(cpu, regSpec, address & S32X_MMREG_MASK, size);
            case COMM -> res = readBufferReg(regContext, regSpec, address, size);
            default -> {
                assert (regSpec.addr >= MD_DREQ_SRC_ADDR_H.addr ? cpu != Z80 : true) : regSpec;
                res = readBufferReg(regContext, regSpec, address, size);
                if (regSpec == SH2_INT_MASK) {
                    res = interruptControls[cpu.ordinal()].readSh2IntMaskReg(address & S32X_REG_MASK, size);
                }
            }
        }
        //RegAccessLogger.regAccess(regSpec.toString(), address, res, size, true);
        return res;
    }

    private boolean handleRegWrite(int address, int value, Size size) {
        final int reg = address & S32X_MMREG_MASK;
        final CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        final RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        //RegAccessLogger.regAccess(regSpec.toString(), reg, value, size, false);
        boolean regChanged = false;

        if (verbose) checkWriteLongAccess(regSpec, reg, size);
        deviceAccessType = regSpec.deviceAccessTypeDelay;

        switch (regSpec.deviceType) {
            case VDP -> {
                assert cpu != Z80 : regSpec;
                regChanged = vdp.vdpRegWrite(regSpec, reg, value, size);
            }
            case PWM -> pwm.write(cpu, regSpec, reg, value, size);
            case COMM -> regChanged = handleCommRegWrite(regSpec, reg, value, size);
            case SYS -> {
                assert (regSpec.addr >= MD_DREQ_SRC_ADDR_H.addr ? cpu != Z80 : true) : regSpec;
                regChanged = handleSysRegWrite(cpu, regSpec, reg, value, size);
            }
            case DMA -> {
                assert (regSpec != MD_DMAC_CTRL ? cpu != Z80 : true) : regSpec;
                dmaFifoControl.write(regSpec, cpu, reg, value, size);
            }
            default -> {
                LOG.error("{} unexpected reg write, addr: {}, {} {}", cpu, th(address), th(value), size);
                writeBufferReg(regContext, regSpec, reg, value, size);
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

    private boolean handleSysRegWrite(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        assert size != Size.LONG;
        boolean regChanged = false;
        switch (regSpec) {
            case SH2_INT_MASK, MD_ADAPTER_CTRL -> regChanged = handleReg0Write(cpu, reg, value, size);
            case SH2_STBY_CHANGE, MD_INT_CTRL -> regChanged = handleReg2Write(cpu, reg, value, size);
            case SH2_HCOUNT_REG, MD_BANK_SET -> regChanged = handleReg4Write(cpu, reg, value, size);
            case SH2_VINT_CLEAR, SH2_HINT_CLEAR, SH2_PWM_INT_CLEAR, SH2_CMD_INT_CLEAR, SH2_VRES_INT_CLEAR -> {
                handleIntClearWrite(cpu, regSpec.addr, value, size);
                regChanged = true;
            }
            case MD_SEGA_TV -> {
                LOG.warn("{} {} unexpected write, addr: {}, {} {}", cpu, regSpec, th(reg), th(value), size);
                writeBufferReg(regContext, regSpec, reg, value, size);
            }
            default -> {
                LOG.error("{} sysReg unexpected write, addr: {}, {} {}", cpu, th(reg), th(value), size);
                writeBufferReg(regContext, regSpec, reg, value, size);
            }
        }
        return regChanged;
    }

    private boolean handleCommRegWrite(final RegSpecS32x regSpec, int reg, int value, Size size) {
        int currentVal = readBufferReg(regContext, regSpec, reg, size);
        boolean regChanged = currentVal != value;
        if (regChanged) {
            //comm regs are shared
            writeBuffers(sysRegsMd, sysRegsSh2, reg, value, size);
        }
        return regChanged;
    }

    private void doLog(CpuDeviceAccess cpu, RegSpecS32x regSpec, int address, int value, Size size, boolean read) {
        boolean isSys = address < END_32X_SYSREG_CACHE;
        ByteBuffer regArea = isSys ? (cpu == M68K ? sysRegsMd : sysRegsSh2) : regContext.vdpRegs;
        logCtx.cpu = Md32xRuntimeData.getAccessTypeExt();
        logCtx.regSpec = regSpec;
        logCtx.regArea = regArea;
        logCtx.read = read;
        logCtx.fbD = vdpContext.frameBufferDisplay;
        logCtx.fbW = vdpContext.frameBufferWritable;
        checkName(logCtx.cpu, regSpec, address, size);
        S32xDict.logAccess(logCtx, address, value, size);
        S32xDict.detectRegAccess(logCtx, address, value, size);
        logZ80Access(cpu, regSpec, address, size, read);
    }

    private void handleIntClearWrite(CpuDeviceAccess cpu, int regEven, int value, Size size) {
        assert cpu == MASTER || cpu == SLAVE;
        int intIdx = VRES_14.ordinal() - (regEven - 0x14);
        IntControl.Sh2Interrupt intType = IntControl.intVals[intIdx];
        interruptControls[cpu.ordinal()].clearInterrupt(intType);
        //autoclear Int_control_reg too
        if (intType == CMD_8) {
            int newVal = readWordFromBuffer(MD_INT_CTRL) & ~(1 << cpu.ordinal());
            boolean change = handleIntControlWriteMd(MD_INT_CTRL.addr, newVal, Size.WORD);
            if (change && verbose) {
                LOG.info("{} auto clear {}", cpu, intType);
            }
        }
    }

    private boolean handleReg4Write(CpuDeviceAccess cpu, int reg, int value, Size size) {
        return switch (cpu.regSide) {
            case MD -> {
                if (size == Size.BYTE && (reg & 1) == 0) {
                    LOG.warn("Ignore bank set write on byte {}, {} {}", th(reg), th(value), size);
                    yield false;
                } else {
                    yield writeBufferHasChangedWithMask(MD_BANK_SET, sysRegsMd, reg, value & 3, size);
                }
            }
            case SH2 -> writeBufferHasChangedWithMask(SH2_HCOUNT_REG, sysRegsSh2, reg, value & 0xFF, size);
        };
    }

    private boolean handleReg2Write(CpuDeviceAccess cpu, int reg, int value, Size size) {
        boolean res = switch (cpu.regSide) {
            case MD -> handleIntControlWriteMd(reg, value, size);
            case SH2 -> writeBuffer(sysRegsSh2, reg, value, size);
        };
        return res;
    }

    private boolean handleIntControlWriteMd(int reg, int value, Size size) {
        boolean changed = writeBufferHasChangedWithMask(MD_INT_CTRL, sysRegsMd, reg, value, size);
        if (changed) {
            int newVal = readBuffer(sysRegsMd, MD_INT_CTRL.addr, Size.WORD) & MD_INT_CTRL.writeAndMask;
            boolean intm = (newVal & 1) > 0;
            boolean ints = (newVal & 2) > 0;
            interruptControls[0].setIntPending(CMD_8, intm);
            interruptControls[1].setIntPending(CMD_8, ints);
            writeBufferWord(MD_INT_CTRL, newVal);
        }
        return changed;
    }

    private boolean handleReg0Write(CpuDeviceAccess cpu, int reg, int value, Size size) {
        boolean res = switch (cpu.regSide) {
            case MD -> handleAdapterControlRegWriteMd(reg, value, size);
            case SH2 -> handleIntMaskRegWriteSh2(cpu, reg, value, size);
        };
        return res;
    }


    private boolean handleAdapterControlRegWriteMd(int reg, int value, Size size) {
        assert size != Size.LONG;
        int val = readWordFromBuffer(MD_ADAPTER_CTRL);
        writeBufferReg(regContext, MD_ADAPTER_CTRL, reg, value, size);

        int newVal = (readWordFromBuffer(MD_ADAPTER_CTRL) & MD_ADAPTER_CTRL.writeAndMask) |
                MD_ADAPTER_CTRL.writeOrMask; //force REN
        if (aden > 0 && (newVal & 1) == 0) {
            System.out.println("#### Disabling ADEN not allowed");
            newVal |= 1;
        }
        handleReset(val, newVal);
        writeBufferWord(MD_ADAPTER_CTRL, newVal);
        setAdenSh2Reg(newVal & 1); //sh2 side read-only
        updateFmShared(newVal); //sh2 side r/w too
        return val != newVal;
    }

    private void handleReset(int val, int newVal) {
        //reset cancel
        if ((val & P32XS_nRES) == 0 && (newVal & P32XS_nRES) > 0) {
            LOG.info("{} unset reset Sh2s (nRes = 0)", Md32xRuntimeData.getAccessTypeExt());
            SysEventManager.instance.fireSysEvent(M68K, SysEventManager.SysEvent.SH2_RESET_OFF);
//            bus.resetSh2(); //TODO check
        }
        //reset
        if ((val & P32XS_nRES) > 0 && (newVal & P32XS_nRES) == 0) {
            LOG.info("{} set reset SH2s (nRes = 1)", Md32xRuntimeData.getAccessTypeExt());
            SysEventManager.instance.fireSysEvent(M68K, SysEventManager.SysEvent.SH2_RESET_ON);
        }
    }

    private void updateFmShared(int wordVal) {
        if (fm != ((wordVal >> 15) & 1)) {
            setFmSh2Reg((wordVal >> 15) & 1);
        }
    }

    private void updateHenShared(int newVal) {
        int nhen = (newVal >> INTMASK_HEN_BIT_POS) & 1;
        if (nhen != hen) {
            hen = nhen;
            if (verbose) LOG.info("{} HEN: {}", Md32xRuntimeData.getAccessTypeExt(), hen);
        }
        setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 1, INTMASK_HEN_BIT_POS, hen, Size.BYTE);
    }

    private boolean handleIntMaskRegWriteSh2(CpuDeviceAccess cpu, int reg, int value, Size size) {
        assert size != Size.LONG;
        int baseReg = reg & ~1;
        final IntControl ic = interruptControls[cpu.ordinal()];
        int prevW = ic.readSh2IntMaskReg(baseReg, Size.WORD);
        ic.writeSh2IntMaskReg(reg, value, size);
        int newVal = ic.readSh2IntMaskReg(baseReg, Size.WORD) | (cart << 8);
        ic.writeSh2IntMaskReg(baseReg, newVal & SH2_INT_MASK.writeAndMask, Size.WORD);
        updateFmShared(newVal); //68k side r/w too
        updateHenShared(newVal); //M,S share the same value
        return newVal != prevW;
    }

    public void setCart(int cartSize) {
        this.cart = (cartSize > 0) ? CART_INSERTED : CART_NOT_INSERTED;
        setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 0, cart, Size.BYTE);
        LOG.info("Cart set to {}inserted: {}", (cart > 0 ? "not " : ""), cart);
    }

    private void setAdenSh2Reg(int aden) {
        this.aden = aden;
        setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 1, aden, Size.BYTE);
    }

    private void setFmSh2Reg(int fm) {
        this.fm = fm;
        setBit(interruptControls[0].getSh2_int_mask_regs(),
                interruptControls[1].getSh2_int_mask_regs(), 0, 7, fm, Size.BYTE);
        setBit(sysRegsMd, MD_ADAPTER_CTRL.addr, 7, fm, Size.BYTE);
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

    private int readWordFromBuffer(RegSpecS32x reg) {
        return S32xUtil.readWordFromBuffer(regContext, reg);
    }

    private void writeBufferWord(RegSpecS32x reg, int value) {
        writeBufferReg(regContext, reg, reg.addr, value, Size.WORD);
    }

    private void setBitFromWord(RegSpecS32x reg, int pos, int value) {
        setBitReg(regContext, reg, reg.addr, pos, value, Size.WORD);
    }

    private void checkWriteLongAccess(RegSpecS32x regSpec, int reg, Size size) {
        if (regSpec.deviceType != COMM && regSpec.deviceType != VDP && regSpec.deviceType != PWM && size == Size.LONG) {
            LOG.error("unsupported 32 bit access, reg: {} {}", regSpec.name, th(reg));
//            throw new RuntimeException("unsupported 32 bit access, reg: " + th(reg));
        }
    }

    public void updateVideoMode(VideoMode value) {
        vdp.updateVideoMode(value);
    }
}