package sh2;

import omegadrive.Device;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.pwm.Pwm;
import sh2.sh2.device.IntControl;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.BitmapMode;
import sh2.vdp.MarsVdp.MarsVdpContext;
import sh2.vdp.MarsVdpImpl;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.Sh2Memory.CACHE_THROUGH_OFFSET;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.dict.S32xDict.S32xRegType.*;
import static sh2.sh2.device.IntControl.Sh2Interrupt.*;
import static sh2.vdp.MarsVdp.VdpPriority.MD;
import static sh2.vdp.MarsVdp.VdpPriority.S32X;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32XMMREG implements Device {

    private static final Logger LOG = LogManager.getLogger(S32XMMREG.class.getSimpleName());

    public static final int SIZE_32X_SYSREG = 0x100;
    public static final int SIZE_32X_VDPREG = 0x100;
    public static final int SIZE_32X_COLPAL = 0x200; // 512 bytes, 256 words
    public static final int DRAM_SIZE = 0x20000; //128 kb window, 2 DRAM banks 128kb each
    public static final int DRAM_MASK = DRAM_SIZE - 1;
    private static final int S32X_MMREG_MASK = 0xFF;
    private static final int S32X_COLPAL_MASK = SIZE_32X_COLPAL - 1;

    public static final int START_32X_SYSREG_CACHE = 0x4000;
    public static final int END_32X_SYSREG_CACHE = START_32X_SYSREG_CACHE + SIZE_32X_SYSREG;
    public static final int START_32X_VDPREG_CACHE = END_32X_SYSREG_CACHE;
    public static final int END_32X_VDPREG_CACHE = START_32X_VDPREG_CACHE + SIZE_32X_VDPREG;
    public static final int START_32X_COLPAL_CACHE = END_32X_VDPREG_CACHE;
    public static final int END_32X_COLPAL_CACHE = START_32X_COLPAL_CACHE + SIZE_32X_COLPAL;

    public static final int START_32X_SYSREG = START_32X_SYSREG_CACHE + CACHE_THROUGH_OFFSET;
    public static final int START_32X_VDPREG = START_32X_VDPREG_CACHE + CACHE_THROUGH_OFFSET;
    public static final int START_32X_COLPAL = START_32X_COLPAL_CACHE + CACHE_THROUGH_OFFSET;
    public static final int END_32X_SYSREG = START_32X_SYSREG + SIZE_32X_SYSREG;
    public static final int END_32X_VDPREG = START_32X_VDPREG + SIZE_32X_VDPREG;
    public static final int END_32X_COLPAL = START_32X_COLPAL + SIZE_32X_COLPAL;

    public static final int START_DRAM_CACHE = 0x400_0000;
    public static final int END_DRAM_CACHE = START_DRAM_CACHE + DRAM_SIZE;
    public static final int START_DRAM = START_DRAM_CACHE + CACHE_THROUGH_OFFSET;
    public static final int END_DRAM = END_DRAM_CACHE + CACHE_THROUGH_OFFSET;

    public static final int START_OVER_IMAGE_CACHE = 0x402_0000;
    public static final int END_OVER_IMAGE_CACHE = START_OVER_IMAGE_CACHE + DRAM_SIZE;
    public static final int START_OVER_IMAGE = START_OVER_IMAGE_CACHE + CACHE_THROUGH_OFFSET;
    public static final int END_OVER_IMAGE = END_OVER_IMAGE_CACHE + CACHE_THROUGH_OFFSET;

    private static final boolean verbose = false, verboseRead = false;

    public static final int CART_INSERTED = 0;
    public static final int CART_NOT_INSERTED = 1;

    //0 = cart inserted, 1 = otherwise
    private int cart = CART_NOT_INSERTED;
    //0 = md access, 1 = sh2 access
    private int fm = 0;
    //0 = disabled, 1 = 32x enabled
    public int aden = 0;
    //0 = palette access disabled, 1 = enabled
    private int pen = 1;
    //0 - pal, 1 - NTSC
    private int pal = 1;
    //0 = Hint disabled during VBlank, 1 = enabled
    private int hen = 0;

    public ByteBuffer sysRegsSh2 = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer sysRegsMd = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer vdpRegs = ByteBuffer.allocate(SIZE_32X_VDPREG);
    public ByteBuffer colorPalette = ByteBuffer.allocateDirect(SIZE_32X_COLPAL);
    public ByteBuffer[] dramBanks = new ByteBuffer[2];

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
        writeBufferWord(M68K_ADAPTER_CTRL, P32XS_REN | P32XS_nRES); //from Picodrive
        writeBufferWord(VDP_BITMAP_MODE, pal * P32XV_PAL);
        writeBufferWord(FBCR, (vdpContext.vBlankOn ? 1 : 0) * P32XV_VBLK | (pen * P32XV_PEN));
        dramBanks[0] = ByteBuffer.allocateDirect(DRAM_SIZE);
        dramBanks[1] = ByteBuffer.allocateDirect(DRAM_SIZE);
        vdp = MarsVdpImpl.createInstance(vdpContext, dramBanks, colorPalette);
        logCtx = new S32xDictLogContext();
    }

    public void setBus(S32xBus bus) {
        this.bus = bus;
    }

    public MarsVdp getVdp() {
        return vdp;
    }

    public void setHBlank(boolean hBlankOn) {
        vdpContext.hBlankOn = hBlankOn;
        setBitFromWord(FBCR, FBCR_HBLK_BIT_POS, hBlankOn ? 1 : 0);
        setBitFromWord(FBCR, FBCR_nFEN_BIT_POS, hBlankOn ? 1 : 0); //TODO hack, FEN =0 after 40 cycles @ 23Mhz
        if (hBlankOn) {
            if (hen > 0 || !vdpContext.vBlankOn) {
                if (--vdpContext.hCount < 0) {
                    vdpContext.hCount = readWordFromBuffer(SH2_HCOUNT_REG) & 0xFF;
                    interruptControls[0].setIntPending(HINT_10, true);
                    interruptControls[1].setIntPending(HINT_10, true);
                }
            } else {
                vdpContext.hCount = readWordFromBuffer(SH2_HCOUNT_REG) & 0xFF;
            }
        }
        setPen(hBlankOn || vdpContext.vBlankOn ? 1 : 0);
//        System.out.println("HBlank: " + hBlankOn);
    }

    public void setVBlank(boolean vBlankOn) {
        vdpContext.vBlankOn = vBlankOn;
        setBitFromWord(FBCR, FBCR_VBLK_BIT_POS, vBlankOn ? 1 : 0);
        if (vBlankOn) {
            vdpContext.screenShift = readWordFromBuffer(SSCR) & 1;
            vdp.draw(vdpContext);
            int currentFb = readWordFromBuffer(FBCR) & 1;
            if (currentFb != vdpContext.fsLatch) {
                setBitFromWord(FBCR, FBCR_FRAMESEL_BIT_POS, vdpContext.fsLatch);
                updateFrameBuffer(vdpContext.fsLatch);
//                System.out.println("##### VBLANK, D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
            }
            interruptControls[0].setIntPending(VINT_12, true);
            interruptControls[1].setIntPending(VINT_12, true);
        }
        setPen(vdpContext.hBlankOn || vBlankOn ? 1 : 0);
//        System.out.println("VBlank: " + vBlankOn);
    }

    public void write(int address, int value, Size size) {
        address &= 0xFFF_FFFF;
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            handleRegWrite(address, value, size);
        } else if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            switch (size) {
                case WORD:
                case LONG:
                    writeBuffer(colorPalette, address & S32X_COLPAL_MASK, value, size);
                    break;
                default:
                    LOG.error(Md32xRuntimeData.getAccessTypeExt() + " write, unable to access colorPalette as " + size);
                    break;
            }
            deviceAccessType = S32xMemAccessDelay.PALETTE;
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            if (size == Size.BYTE && value == 0) { //value =0 on byte is ignored
                return;
            }
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, size);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            //see Space Harrier, brutal, doom resurrection
            writeFrameBufferOver(address, value, size);
        } else {
            throw new RuntimeException();
        }
        S32xMemAccessDelay.addWriteCpuDelay(deviceAccessType);
    }

    public int read(int address, Size size) {
        address &= 0xFFF_FFFF;
        int res = 0;
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            res = handleRegRead(address, size);
        } else if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            if (size == Size.WORD) {
                res = readBuffer(colorPalette, address & S32X_COLPAL_MASK, size);
            } else {
                LOG.error(Md32xRuntimeData.getAccessTypeExt() + " read, unable to access colorPalette as " + size);
            }
            deviceAccessType = S32xMemAccessDelay.PALETTE;
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            res = readBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, size);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            res = readBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, size);
        } else {
            throw new RuntimeException();
        }
        S32xMemAccessDelay.addReadCpuDelay(deviceAccessType);
        return res;
    }

    private int handleRegRead(int address, Size size) {
        CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        if (regSpec == null) {
            LOG.error("{} unable to handle read, addr: {} {}", cpu, th(address), size);
            return (int) size.getMask();
        }
        deviceAccessType = regSpec.deviceAccessTypeDelay;
        if (verboseRead) {
            doLog(cpu, regSpec, address, -1, size, true);
        }
        int res = 0;
        switch (regSpec.deviceType) {
            case DMA:
                res = dmaFifoControl.read(regSpec, cpu, address & S32X_REG_MASK, size);
                break;
            case PWM:
                res = pwm.read(cpu, regSpec, address & S32X_MMREG_MASK, size);
                break;
            default:
                res = readBufferInt(regSpec, address, size);
                if (regSpec == SH2_INT_MASK) {
                    res = interruptControls[cpu.ordinal()].readSh2IntMaskReg(address & S32X_REG_MASK, size);
                }
                break;
        }
        return res;
    }

    private boolean handleRegWrite(int address, int value, Size size) {
        final int reg = address & S32X_MMREG_MASK;
        final CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        final RegSpecS32x regSpec = S32xDict.getRegSpec(cpu, address);
        boolean regChanged = false;

        checkWriteLongAccess(regSpec, reg, size);
        deviceAccessType = regSpec.deviceAccessTypeDelay;

        switch (regSpec.deviceType) {
            case VDP:
                regChanged = handleVdpRegWrite(regSpec, reg, value, size);
                break;
            case PWM:
                pwm.write(cpu, regSpec, reg, value, size);
                break;
            case COMM:
                regChanged = handleCommRegWrite(regSpec, reg, value, size);
                break;
            case SYS:
                regChanged = handleSysRegWrite(cpu, regSpec, reg, value, size);
                break;
            case DMA:
                dmaFifoControl.write(regSpec, cpu, reg, value, size);
                break;
            default:
                LOG.error("{} unexpected reg write, addr: {}, {} {}", cpu, th(address), th(value), size);
                writeBufferInt(regSpec, reg, value, size);
                regChanged = true;
                break;
        }
        if (verbose && regChanged) {
            doLog(cpu, regSpec, address, value, size, false);
        }
        return regChanged;
    }

    private boolean handleSysRegWrite(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        boolean regChanged = false;
        switch (regSpec) {
            case SH2_INT_MASK:
            case M68K_ADAPTER_CTRL:
                regChanged = handleReg0Write(cpu, reg, value, size);
                break;
            case SH2_STBY_CHANGE:
            case M68K_INT_CTRL:
                regChanged = handleReg2Write(cpu, reg, value, size);
                break;
            case SH2_HCOUNT_REG:
            case M68K_BANK_SET:
                regChanged = handleReg4Write(cpu, reg, value, size);
                break;
            case SH2_VINT_CLEAR:
            case SH2_HINT_CLEAR:
            case SH2_PWM_INT_CLEAR:
            case SH2_CMD_INT_CLEAR:
            case SH2_VRES_INT_CLEAR:
                handleIntClearWrite(cpu, regSpec.addr, value, size);
                regChanged = true;
                break;
            case M68K_SEGA_TV:
                writeBufferInt(regSpec, reg, value, size);
                break;
            default:
                LOG.error("{} sysReg unexpected write, addr: {}, {} {}", cpu, th(reg), th(value), size);
                writeBufferInt(regSpec, reg, value, size);
                break;
        }
        return regChanged;
    }

    private boolean handleVdpRegWrite(RegSpecS32x regSpec, int reg, int value, Size size) {
        switch (size) {
            case WORD:
            case BYTE:
                return handleVdpRegWriteInternal(regSpec, reg, value, size);
            case LONG:
                RegSpecS32x regSpec2 = getRegSpec(regSpec.regCpuType, regSpec.fullAddress + 2);
                boolean res = handleVdpRegWriteInternal(regSpec, reg, value >> 16, Size.WORD);
                res |= handleVdpRegWriteInternal(regSpec2, reg + 2, value & 0xFFFF, Size.WORD);
                return res;
        }
        return false;
    }


    private boolean handleVdpRegWriteInternal(RegSpecS32x regSpec, int reg, int value, Size size) {
        boolean regChanged = false;
        switch (regSpec) {
            case VDP_BITMAP_MODE:
                regChanged = handleBitmapModeWrite(reg, value, size);
                break;
            case FBCR:
                regChanged = handleFBCRWrite(reg, value, size);
                break;
            case AFDR:
                runAutoFill(value);
                regChanged = true;
                break;
            default:
                int res = readBufferInt(regSpec, reg, size);
                if (res != value) {
                    writeBufferInt(regSpec, reg, value, size);
                    regChanged = true;
                }
                break;
        }
        return regChanged;
    }

    private boolean handleCommRegWrite(final RegSpecS32x regSpec, int reg, int value, Size size) {
        int currentVal = readBufferInt(regSpec, reg, size);
        boolean regChanged = currentVal != value;
        if (regChanged) {
            //comm regs are shared
            writeBuffers(sysRegsMd, sysRegsSh2, reg, value, size);
        }
        return regChanged;
    }

    private void doLog(CpuDeviceAccess cpu, RegSpecS32x regSpec, int address, int value, Size size, boolean read) {
        boolean isSys = address < END_32X_SYSREG_CACHE;
        ByteBuffer regArea = isSys ? (cpu == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
        logCtx.sh2Access = Md32xRuntimeData.getAccessTypeExt();
        logCtx.regSpec = regSpec;
        logCtx.regArea = regArea;
        logCtx.read = read;
        logCtx.fbD = vdpContext.frameBufferDisplay;
        logCtx.fbW = vdpContext.frameBufferWritable;
        checkName(logCtx.sh2Access, regSpec, address, size);
        S32xDict.logAccess(logCtx, address, value, size);
        S32xDict.detectRegAccess(logCtx, address, value, size);
    }

    private void writeFrameBufferOver(int address, int value, Size size) {
        if (value == 0) {
            return;
        }
        switch (size) {
            case WORD:
                writeFrameBufferByte(address, (value >> 8) & 0xFF);
                writeFrameBufferByte(address + 1, value & 0xFF);
                break;
            case BYTE:
                //guaranteed not be zero
                writeFrameBufferByte(address, value);
                break;
            case LONG:
//                LOG.error("Unexpected writeFrameBufferOver: {}", size);
                writeFrameBufferByte(address, (value >> 24) & 0xFF);
                writeFrameBufferByte(address + 1, (value >> 16) & 0xFF);
                writeFrameBufferByte(address + 2, (value >> 8) & 0xFF);
                writeFrameBufferByte(address + 3, (value >> 0) & 0xFF);
                break;
        }
    }

    private void writeFrameBufferByte(int address, int value) {
        if (value != 0) {
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, Size.BYTE);
        }
    }

    private void handleIntClearWrite(CpuDeviceAccess sh2Access, int regEven, int value, Size size) {
        if (sh2Access != M68K) {
            int intIdx = VRES_14.ordinal() - (regEven - 0x14);
            IntControl.Sh2Interrupt intType = IntControl.intVals[intIdx];
            interruptControls[sh2Access.ordinal()].clearInterrupt(intIdx);
            //autoclear Int_control_reg too
            if (intType == CMD_8) {
                int newVal = readWordFromBuffer(M68K_INT_CTRL) & ~(1 << sh2Access.ordinal());
                boolean change = handleIntControlWrite68k(M68K_INT_CTRL.addr, newVal, Size.WORD);
                if (change) {
                    LOG.debug("{} auto clear {}", sh2Access, intType);
                }
            }
        } else {
            LOG.error("Unexpected intClear write {}, reg {}", sh2Access, regEven);
        }
    }

    private boolean handleReg4Write(CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        ByteBuffer b = sh2Access == M68K ? sysRegsMd : sysRegsSh2;
        return writeBufferHasChanged(b, reg, value, size);
    }

    private boolean handleReg2Write(CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        boolean res = false;
        switch (sh2Access) {
            case M68K:
                res = handleIntControlWrite68k(reg, value, size);
                break;
            case SLAVE:
            case MASTER:
                res = writeBufferHasChanged(sysRegsSh2, reg, value, size);
                break;
        }
        return res;
    }

    private boolean handleIntControlWrite68k(int reg, int value, Size size) {
        boolean changed = writeBufferHasChanged(sysRegsMd, reg, value, size);
        if (changed) {
            int newVal = readBuffer(sysRegsMd, M68K_INT_CTRL.addr + 1, Size.BYTE);
            boolean intm = (newVal & 1) > 0;
            boolean ints = ((newVal >> 1) & 1) > 0;
            interruptControls[0].setIntPending(CMD_8, intm);
            interruptControls[1].setIntPending(CMD_8, ints);
        }
        return changed;
    }

    private boolean handleReg0Write(CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        boolean res = false;
        switch (sh2Access) {
            case M68K:
                res = handleAdapterControlRegWrite68k(reg, value, size);
                break;
            case SLAVE:
            case MASTER:
                res = handleIntMaskRegWriteSh2(sh2Access, reg, value, size);
                break;
        }
        return res;
    }

    private boolean handleAdapterControlRegWrite68k(int reg, int value, Size size) {
        int val = readWordFromBuffer(M68K_ADAPTER_CTRL);
        writeBufferInt(M68K_ADAPTER_CTRL, reg, value, size);

        int newVal = readWordFromBuffer(M68K_ADAPTER_CTRL) | P32XS_REN; //force REN
        if (aden > 0 && (newVal & 1) == 0) {
            System.out.println("#### Disabling ADEN not allowed");
            newVal |= 1;
        }
        //reset cancel
        if ((val & P32XS_nRES) == 0 && (newVal & P32XS_nRES) > 0) {
            LOG.info("{} Reset Cancel?", Md32xRuntimeData.getAccessTypeExt());
            //TODO this breaks test2
//                bus.resetSh2();
        }
        //reset
        if ((val & P32XS_nRES) > 0 && (newVal & P32XS_nRES) == 0) {
            LOG.info("{} Reset?", Md32xRuntimeData.getAccessTypeExt());
            //TODO this breaks test2
//                bus.resetSh2();
        }
        writeBufferWord(M68K_ADAPTER_CTRL, newVal);
        setAdenSh2Reg(newVal & 1); //sh2 side read-only
        updateFmShared(newVal); //sh2 side r/w too
        return val != newVal;
    }

    private void updateFmShared(int wordVal) {
        if (fm != ((wordVal >> 15) & 1)) {
            setFmSh2Reg((wordVal >> 15) & 1);
        }
    }

    private boolean handleIntMaskRegWriteSh2(CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        int baseReg = reg & ~1;
        final IntControl ic = interruptControls[sh2Access.ordinal()];
        int prevW = ic.readSh2IntMaskReg(baseReg, Size.WORD);
        ic.writeSh2IntMaskReg(reg, value, size);
        int newVal = ic.readSh2IntMaskReg(baseReg, Size.WORD) | (cart << 8);
        ic.writeSh2IntMaskReg(baseReg, newVal, Size.WORD);
        updateFmShared(newVal); //68k side r/w too
        int nhen = (newVal >> INTMASK_HEN_BIT_POS) & 1;
        if (nhen != hen) {
            hen = nhen;
            LOG.info("{} HEN: {}", sh2Access, hen);
        }
        return newVal != prevW;
    }

    private boolean handleBitmapModeWrite(int reg, int value, Size size) {
        int val = readWordFromBuffer(VDP_BITMAP_MODE);
        int prevPrio = (val >> 7) & 1;
        writeBufferInt(VDP_BITMAP_MODE, reg, value, size);
        int newVal = readWordFromBuffer(VDP_BITMAP_MODE) & ~(P32XV_PAL | P32XV_240);
        int v240 = pal == 0 && vdpContext.videoMode.isV30() ? 1 : 0;
        newVal = newVal | (pal * P32XV_PAL) | (v240 * P32XV_240);
        writeBufferWord(VDP_BITMAP_MODE, newVal);
        vdpContext.bitmapMode = BitmapMode.vals[newVal & 3];
        int prio = (newVal >> 7) & 1;
        if (prevPrio != prio) {
            vdpContext.priority = prio == 0 ? MD : S32X;
            if (verbose) LOG.info("Vdp priority: {} -> {}", prevPrio == 0 ? "MD" : "32x", vdpContext.priority);
        }
        return val != newVal;
    }

    private boolean handleFBCRWrite(int reg, int value, Size size) {
        int val = readWordFromBuffer(FBCR);
        writeBufferInt(FBCR, reg, value, size);
        int val1 = readWordFromBuffer(FBCR);
        int regVal = 0;
        if (vdpContext.vBlankOn || vdpContext.bitmapMode == BitmapMode.BLANK) {
            regVal = (val & 0xFFFC) | (val1 & 3);
            updateFrameBuffer(regVal);
        } else {
            //during display the register always shows the current frameBuffer being displayed
            regVal = (val & 0xFFFD) | (val1 & 2);
        }
        writeBufferWord(FBCR, regVal);
        vdpContext.fsLatch = val1 & 1;
//            System.out.println("###### FBCR write: D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
        return val != regVal;
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
        setBit(sysRegsMd, M68K_ADAPTER_CTRL.addr, 7, fm, Size.BYTE);
        if (verbose) LOG.info("{} FM: {}", Md32xRuntimeData.getAccessTypeExt(), fm);
    }

    private void setPen(int pen) {
        this.pen = pen;
        int val = (pen << 5) | (readBuffer(vdpRegs, FBCR.addr, Size.BYTE) & 0xDF);
        writeBuffer(vdpRegs, FBCR.addr, val, Size.BYTE);
    }

    private void updateFrameBuffer(int val) {
        vdpContext.frameBufferDisplay = val & 1;
        vdpContext.frameBufferWritable = (vdpContext.frameBufferDisplay + 1) & 1;
    }

    private void runAutoFill(int data) {
        writeBufferWord(AFDR, data);
        int startAddr = readWordFromBuffer(AFSAR);
        int len = readWordFromBuffer(AFLR) & 0xFF;
        runAutoFillInternal(dramBanks[vdpContext.frameBufferWritable], startAddr, data, len);
    }

    public void runAutoFillInternal(ByteBuffer buffer, int startAddrWord, int data, int len) {
        int addrFixed = startAddrWord & 0xFF00;
        int addrVariable = startAddrWord & 0xFF;
        if (verbose) LOG.info("AutoFill start {}, len {}, data {}", th(startAddrWord), th(len), th(data));
        final int dataWord = data & 0xFFFF;
        int afsarEnd = addrFixed + (len & 0xFF);
        do {
            writeBuffer(buffer, (addrFixed + addrVariable) << 1, dataWord, Size.WORD);
            if (verbose) LOG.info("AutoFill write(byte): {}, len(word) {}, data {}",
                    th((addrFixed + addrVariable) << 1), th(len), th(dataWord));
            addrVariable = (addrVariable + 1) & 0xFF;
            len--;
        } while (len >= 0);
        writeBufferWord(AFSAR, addrFixed + addrVariable); //star wars arcade
        if (verbose) LOG.info("AutoFill done, AFSAR {}, len {}", th(afsarEnd), th(Math.max(len, 0)));
    }

    public void updateVideoMode(VideoMode video) {
        pal = video.isPal() ? 0 : 1;
        int v240 = video.isPal() && video.isV30() ? 1 : 0;
        int val = readWordFromBuffer(VDP_BITMAP_MODE) & ~(P32XV_PAL | P32XV_240);
        writeBufferWord(VDP_BITMAP_MODE, val | (pal * P32XV_PAL) | (v240 * P32XV_240));
        vdp.updateVideoMode(video);
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
        return readBufferInt(reg, reg.addr, Size.WORD);
    }

    private int readBufferInt(RegSpecS32x reg, int address, Size size) {
        address &= S32X_REG_MASK;
        if (reg.deviceType == VDP) {
            return readBuffer(vdpRegs, address & S32X_VDP_REG_MASK, size);
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
            case REG_M68K:
                return readBuffer(sysRegsMd, address, size);
            case REG_SH2:
                return readBuffer(sysRegsSh2, address, size);
        }
        LOG.error("Unable to read buffer: {}, addr: {} {}", reg.name, th(address), size);
        return (int) size.getMask();
    }

    private void writeBufferWord(RegSpecS32x reg, int value) {
        writeBufferInt(reg, reg.addr, value, Size.WORD);
    }

    private void setBitFromWord(RegSpecS32x reg, int pos, int value) {
        setBitInt(reg, reg.addr, pos, value, Size.WORD);
    }

    private void setBitInt(RegSpecS32x reg, int address, int pos, int value, Size size) {
        address &= S32X_REG_MASK;
        if (reg.deviceType == VDP) {
            S32xUtil.setBit(vdpRegs, address & S32X_VDP_REG_MASK, pos, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
                S32xUtil.setBit(sysRegsMd, sysRegsSh2, address, pos, value, size);
                return;
            case REG_M68K:
                S32xUtil.setBit(sysRegsMd, address, pos, value, size);
                return;
            case REG_SH2:
                S32xUtil.setBit(sysRegsSh2, address, pos, value, size);
                return;
        }
        LOG.error("Unable to setBit: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    private void writeBufferInt(RegSpecS32x reg, int address, int value, Size size) {
        address &= S32X_REG_MASK;
        if (reg.deviceType == VDP) {
            writeBuffer(vdpRegs, address & S32X_VDP_REG_MASK, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
                writeBuffer(sysRegsMd, address, value, size);
                writeBuffer(sysRegsSh2, address, value, size);
                return;
            case REG_M68K:
                writeBuffer(sysRegsMd, address, value, size);
                return;
            case REG_SH2:
                writeBuffer(sysRegsSh2, address, value, size);
                return;
        }
        LOG.error("Unable to write buffer: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    private void checkWriteLongAccess(RegSpecS32x regSpec, int reg, Size size) {
        if (regSpec.deviceType != COMM && regSpec.deviceType != VDP && regSpec.deviceType != PWM && size == Size.LONG) {
            LOG.error("unsupported 32 bit access, reg: {} {}", regSpec.name, th(reg));
            throw new RuntimeException("unsupported 32 bit access, reg: " + th(reg));
        }
    }
}