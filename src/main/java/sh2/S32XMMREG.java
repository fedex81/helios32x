package sh2;

import omegadrive.Device;
import omegadrive.system.BaseSystem;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.*;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.device.IntControl;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.BitmapMode;
import sh2.vdp.MarsVdp.MarsVdpContext;
import sh2.vdp.MarsVdpImpl;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.S32xUtil.*;
import static sh2.Sh2Memory.CACHE_THROUGH_OFFSET;
import static sh2.dict.S32xDict.*;
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


    public ByteBuffer sysRegsSh2 = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer sysRegsMd = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer vdpRegs = ByteBuffer.allocate(SIZE_32X_VDPREG);
    public ByteBuffer colorPalette = ByteBuffer.allocateDirect(SIZE_32X_COLPAL);
    public ByteBuffer[] dramBanks = new ByteBuffer[2];

    public IntControl[] interruptControls;
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
        writeBuffer(sysRegsMd, ADAPTER_CTRL, P32XS_REN | P32XS_nRES, Size.WORD); //from Picodrive
        writeBuffer(vdpRegs, VDP_BITMAP_MODE, pal * P32XV_PAL, Size.WORD);
        writeBuffer(vdpRegs, FBCR, (vdpContext.vBlankOn ? 1 : 0) * P32XV_VBLK | (pen * P32XV_PEN), Size.WORD);
        dramBanks[0] = ByteBuffer.allocateDirect(DRAM_SIZE);
        dramBanks[1] = ByteBuffer.allocateDirect(DRAM_SIZE);
        vdp = MarsVdpImpl.createInstance(vdpContext, dramBanks, colorPalette);
        logCtx = new S32xDictLogContext();
        interruptControls = new IntControl[2];
    }

    public void setBus(S32xBus bus) {
        this.bus = bus;
    }

    public MarsVdp getVdp() {
        return vdp;
    }

    public void setHBlankOn(boolean hBlankOn) {
        vdpContext.hBlankOn = hBlankOn;
        int val = readBuffer(vdpRegs, FBCR, Size.WORD);
        val = (hBlankOn ? 1 : 0) << 14 | (val & 0xBFFF);
        writeBuffer(vdpRegs, FBCR, val, Size.WORD);
        if (hBlankOn) {
            int hCnt = readBuffer(sysRegsSh2, HCOUNT_REG, Size.WORD);
            if (--vdpContext.hCount < 0) {
                vdpContext.hCount = readBuffer(sysRegsSh2, HCOUNT_REG, Size.WORD) & 0xFF;
                interruptControls[0].setIntPending(HINT_10, true);
                interruptControls[1].setIntPending(HINT_10, true);
            }
        }
        setPen(hBlankOn || vdpContext.vBlankOn ? 1 : 0);
//        System.out.println("HBlank: " + hBlankOn);
    }

    public void setVBlankOn(boolean vBlankOn) {
        vdpContext.vBlankOn = vBlankOn;
        int val = readBuffer(vdpRegs, FBCR, Size.WORD);
        val = (vBlankOn ? 1 : 0) << 15 | (val & 0x7FFF);
        writeBuffer(vdpRegs, FBCR, val, Size.WORD);
        if (vBlankOn) {
            vdpContext.screenShift = readBuffer(vdpRegs, SSCR, Size.WORD) & 1;
            vdp.draw(vdpContext);
            int currentFb = val & 1;
            if (currentFb != vdpContext.fsLatch) {
                int newVal = ((val & 0xFFFE) | vdpContext.fsLatch);
                writeBuffer(vdpRegs, FBCR, newVal, Size.WORD);
                updateFrameBuffer(newVal);
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
                    LOG.error(BaseSystem.getAccessType() + " write, unable to access colorPalette as " + size);
                    break;
            }
            deviceAccessType = S32xMemAccessDelay.PALETTE;
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            if (size == Size.BYTE && value == 0) { //value =0 on byte access is ignored
                return;
            }
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, size);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, size);
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
                LOG.error(BaseSystem.getAccessType() + " read, unable to access colorPalette as " + size);
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
        CpuDeviceAccess sh2Access = BaseSystem.getAccessType();
        int reg = address & S32X_MMREG_MASK;
        if (size == Size.LONG && reg < COMM0 && reg != DREQ_DEST_ADDR_H && reg != DREQ_SRC_ADDR_H) {
            throw new RuntimeException("unsupported 32 bit access: " + address);
        }
        boolean isSys = address < END_32X_SYSREG_CACHE;
        deviceAccessType = isSys ? S32xMemAccessDelay.SYS_REG : S32xMemAccessDelay.VDP_REG;
        ByteBuffer regArea = isSys ? (sh2Access == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
        if (verboseRead) {
            doLog(regArea, isSys, address, reg, -1, size, true);
        }
        int res = readBuffer(regArea, reg, size);
        if (sh2Access != M68K && isSys && reg < INT_CTRL_REG) {
            res = interruptControls[sh2Access.ordinal()].readSh2IntMaskReg(reg, size);
        }
        //TODO autoClear int reg?
        //Both are automatically cleared if SH2 does not interrupt clear.
        else if (sh2Access == M68K && isSys && (reg == INT_CTRL_REG || reg == INT_CTRL_REG + 1)) {
            LOG.info("{} READ INT_CTRL_REG, addr: {}, data: {}", sh2Access, Integer.toHexString(address), res);
        } else if (isSys && reg >= DREQ_CTRL && reg <= DREQ_DEST_ADDR_L + 1) {
            res = dmaFifoControl.read(sh2Access, reg, size);
        }
        return res;
    }

    private boolean handleRegWrite(int address, int value, Size size) {
        CpuDeviceAccess sh2Access = BaseSystem.getAccessType();
        boolean skipWrite = false;
        boolean regChanged = false;
        int reg = address & S32X_MMREG_MASK;
        final int regEven = reg & ~1; //even
        if (size == Size.LONG && regEven < COMM0) {
            throw new RuntimeException("unsupported 32 bit access, reg: " + address);
        }
        final boolean isSys = address < END_32X_SYSREG_CACHE;
        deviceAccessType = isSys ? S32xMemAccessDelay.SYS_REG : S32xMemAccessDelay.VDP_REG;
        final ByteBuffer regArea = isSys ? (sh2Access == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
        boolean logAccess = false;

        if (!isSys) {
            switch (regEven) {
                case VDP_BITMAP_MODE:
                    regChanged = handleBitmapModeWrite(reg, value, size);
                    skipWrite = true;
                    break;
                case FBCR:
                    regChanged = handleFBCRWrite(reg, value, size);
                    skipWrite = true;
                    break;
                case AFDR:
                    runAutoFill(value);
                    skipWrite = true;
                    break;
            }
        } else {
            switch (regEven) {
                case INT_MASK:
                    regChanged = handleReg0Write(sh2Access, reg, value, size);
                    skipWrite = true;
                    break;
                case INT_CTRL_REG:
                    regChanged = handleReg2Write(sh2Access, reg, value, size);
                    skipWrite = true;
                    break;
                case HCOUNT_REG:
                    regChanged = handleReg4Write(sh2Access, reg, value, size);
                    skipWrite = true;
                    break;
                case VINT_CLEAR:
                case HINT_CLEAR:
                case PWM_INT_CLEAR:
                case CMD_INT_CLEAR:
                    handleIntClearWrite(sh2Access, regEven, value, size);
                    break;
                case FIFO_REG:
                case DREQ_CTRL:
                case DREQ_LEN:
                case DREQ_SRC_ADDR_H:
                case DREQ_SRC_ADDR_L:
                case DREQ_DEST_ADDR_L:
                case DREQ_DEST_ADDR_H:
                    dmaFifoControl.write(sh2Access, reg, value, size);
                    skipWrite = true;
                    break;
            }
        }
        if (!skipWrite) {
            regChanged = internalRegWriteCOMM(regArea, reg, value, size);
        }
        if (verbose && regChanged) {
            doLog(regArea, isSys, address, reg, value, size, false);
        }

        return regChanged;
    }

    //COMM and other regs
    private boolean internalRegWriteCOMM(final ByteBuffer regArea, int reg, int value, Size size) {
        int currentVal = readBuffer(regArea, reg, size);
        boolean regChanged = currentVal != value;
        if (regChanged) {
            switch (reg) {
                case COMM0:
                case COMM1:
                case COMM2:
                case COMM3:
                case COMM4:
                case COMM5:
                case COMM6:
                case COMM7:
                    //comm regs are shared
                    writeBuffers(sysRegsMd, sysRegsSh2, reg, value, size);
                    break;
                default:
                    writeBuffer(regArea, reg, value, size);
                    break;
            }
        }
        return regChanged;
    }

    private void doLog(ByteBuffer regArea, boolean isSys, int address, int reg, int value, Size size, boolean read) {
        logCtx.sh2Access = BaseSystem.getAccessType();
        logCtx.regArea = regArea;
        logCtx.isSys = isSys;
        logCtx.read = read;
        logCtx.fbD = vdpContext.frameBufferDisplay;
        logCtx.fbW = vdpContext.frameBufferWritable;
        checkName(logCtx.sh2Access, address, size);
        S32xDict.logAccess(logCtx, address, value, size, reg);
        S32xDict.detectRegAccess(logCtx, address, value, size);
    }

    private void handleIntClearWrite(CpuDeviceAccess sh2Access, int regEven, int value, Size size) {
        if (sh2Access != M68K) {
            int intIdx = VRES_14.ordinal() - (regEven - 0x14);
            IntControl.Sh2Interrupt intType = IntControl.intVals[intIdx];
            interruptControls[sh2Access.ordinal()].clearInterrupt(intType);
            //autoclear Int_control_reg too
            if (intType == CMD_8) {
                int newVal = (readBuffer(sysRegsMd, INT_CTRL_REG, Size.WORD)) & ~(1 << sh2Access.ordinal());
                boolean change = handleIntControlWrite68k(INT_CTRL_REG, newVal, Size.WORD);
                if (change) {
                    LOG.info(sh2Access + " auto clear " + intType);
                }
            }
        } else {
            LOG.error("Unexpected write {}, reg {}", sh2Access, regEven);
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
            int newVal = readBuffer(sysRegsMd, INT_CTRL_REG + 1, Size.BYTE);
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
        int val = readBuffer(sysRegsMd, ADAPTER_CTRL, Size.WORD);
        writeBuffer(sysRegsMd, reg, value, size);

        int newVal = readBuffer(sysRegsMd, ADAPTER_CTRL, Size.WORD) | P32XS_REN; //force REN
        if (aden > 0 && (newVal & 1) == 0) {
            System.out.println("#### Disabling ADEN not allowed");
            newVal |= 1;
        }
        //reset cancel
        if ((val & P32XS_nRES) == 0 && (newVal & P32XS_nRES) > 0) {
            LOG.info("{} Reset Cancel?", BaseSystem.getAccessType());
            //TODO this breaks test2
//                bus.resetSh2();
        }
        //reset
        if ((val & P32XS_nRES) > 0 && (newVal & P32XS_nRES) == 0) {
            LOG.info("{} Reset?", BaseSystem.getAccessType());
            //TODO this breaks test2
//                bus.resetSh2();
        }
        writeBuffer(sysRegsMd, ADAPTER_CTRL, newVal, Size.WORD);
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
        return newVal != prevW;
    }

    private boolean handleBitmapModeWrite(int reg, int value, Size size) {
        int val = readBuffer(vdpRegs, VDP_BITMAP_MODE, Size.WORD);
        int prevPrio = (val >> 7) & 1;
        writeBuffer(vdpRegs, reg, value, size);
        int newVal = readBuffer(vdpRegs, VDP_BITMAP_MODE, Size.WORD) & ~(P32XV_PAL | P32XV_240);
        int v240 = pal == 0 && vdpContext.videoMode.isV30() ? 1 : 0;
        newVal = newVal | (pal * P32XV_PAL) | (v240 * P32XV_240);
        writeBuffer(vdpRegs, VDP_BITMAP_MODE, newVal, Size.WORD);
        vdpContext.bitmapMode = BitmapMode.vals[newVal & 3];
        int prio = (newVal >> 7) & 1;
        if (prevPrio != prio) {
            vdpContext.priority = prio == 0 ? MD : S32X;
            LOG.info("Vdp priority: {} -> {}", prevPrio == 0 ? "MD" : "32x", vdpContext.priority);
        }
        return val != newVal;
    }

    private boolean handleFBCRWrite(int reg, int value, Size size) {
        int val = readBuffer(vdpRegs, FBCR, Size.WORD);
        writeBuffer(vdpRegs, reg, value, size);
        int val1 = readBuffer(vdpRegs, FBCR, Size.WORD);
        int regVal = 0;
        if (vdpContext.vBlankOn || vdpContext.bitmapMode == BitmapMode.BLANK) {
            regVal = (val & 0xFFFC) | (val1 & 3);
            updateFrameBuffer(regVal);
        } else {
            //during display the register always shows the current frameBuffer being displayed
            regVal = (val & 0xFFFD) | (val1 & 2);
        }
        writeBuffer(vdpRegs, FBCR, regVal, Size.WORD);
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
        setBit(sysRegsMd, ADAPTER_CTRL, 7, fm, Size.BYTE);
        LOG.info("{} FM: {}", BaseSystem.getAccessType(), fm);
    }

    private void setPen(int pen) {
        this.pen = pen;
        int val = (pen << 5) | (readBuffer(vdpRegs, FBCR, Size.BYTE) & 0xDF);
        writeBuffer(vdpRegs, FBCR, val, Size.BYTE);
    }

    private void updateFrameBuffer(int val) {
        vdpContext.frameBufferDisplay = val & 1;
        vdpContext.frameBufferWritable = (vdpContext.frameBufferDisplay + 1) & 1;
    }

    private void runAutoFill(int data) {
        writeBuffer(vdpRegs, AFDR, data, Size.WORD);
        int startAddr = readBuffer(vdpRegs, AFSAR, Size.WORD);
        int len = readBuffer(vdpRegs, AFLR, Size.WORD) & 0xFF;
        runAutoFillInternal(dramBanks[vdpContext.frameBufferWritable], startAddr, data, len);
    }

    public void runAutoFillInternal(ByteBuffer buffer, int startAddrWord, int data, int len) {
        int addrFixed = startAddrWord & 0xFF00;
        int addrVariable = startAddrWord & 0xFF;
//     String s1 = "start %08X, len %04X, data %04X";
//                    System.out.println(String.format(s1, startAddr, len, data));
        len = len == 0 ? 0xFF : len;
        final int dataWord = (data << 8) | data;
        do {
            writeBuffer(buffer, (addrFixed + addrVariable) << 1, dataWord, Size.WORD);
            addrVariable = (addrVariable + 1) & 0xFF;
            len--;
        } while (len > 0);
        writeBuffer(vdpRegs, AFSAR, addrFixed + addrVariable, Size.WORD);
    }

    public void updateVideoMode(VideoMode video) {
        pal = video.isPal() ? 0 : 1;
        int v240 = video.isPal() && video.isV30() ? 1 : 0;
        int val = readBuffer(vdpRegs, VDP_BITMAP_MODE, Size.WORD) & ~(P32XV_PAL | P32XV_240);
        writeBuffer(vdpRegs, VDP_BITMAP_MODE, val | (pal * P32XV_PAL) | (v240 * P32XV_240), Size.WORD);
        vdp.updateVideoMode(video);
    }

    public void setDmaControl(DmaFifo68k dmaFifoControl) {
        this.dmaFifoControl = dmaFifoControl;
    }

    public void setInterruptControl(IntControl... interruptControls) {
        this.interruptControls = interruptControls;
    }
}