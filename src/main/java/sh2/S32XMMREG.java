package sh2;

import omegadrive.Device;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.Sh2Util.Sh2Access;
import sh2.dict.S32xDict;

import java.nio.ByteBuffer;

import static sh2.IntC.Sh2Interrupt.*;
import static sh2.Sh2Memory.CACHE_THROUGH_OFFSET;
import static sh2.Sh2Util.Sh2Access.*;
import static sh2.dict.S32xDict.*;

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

    public static Sh2Access sh2Access = MASTER;

    private static final boolean verbose = false;

    //0 = no cart, 1 = otherwise
    private volatile int cart = 0;
    //0 = md access, 1 = sh2 access
    private volatile int fm = 0;
    //0 = disabled, 1 = 32x enabled
    public volatile int aden = 0;
    //0 = palette access disabled, 1 = enabled
    public volatile int pen = 1;

    public ByteBuffer sysRegsSh2 = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer sysRegsMd = ByteBuffer.allocate(SIZE_32X_SYSREG);
    public ByteBuffer vdpRegs = ByteBuffer.allocate(SIZE_32X_VDPREG);
    public ByteBuffer colorPalette = ByteBuffer.allocateDirect(SIZE_32X_COLPAL);
    public ByteBuffer[] dramBanks = new ByteBuffer[2];

    public IntC interruptControl;
    private MarsVdp vdp;
    int frameBufferDisplay = 0;
    int frameBufferWritable = 1;
    int fsLatch = 0;

    private boolean hBlankOn, vBlankOn;
    private BITMAP_MODE bitmap_mode = BITMAP_MODE.BLANK;

    private static final int NTSC_MASK = 0x8000;
    private VideoMode videoMode = VideoMode.NTSCU_H40_V28;
    private int hCount = 0;
    private S32xBus bus;

    enum BITMAP_MODE {
        BLANK, PACKED_PX, DIRECT_COL, RUN_LEN;

        public static BITMAP_MODE[] vals = BITMAP_MODE.values();
    }

    public S32XMMREG() {
        init();
    }

    @Override
    public void init() {
        Sh2Util.writeBuffer(sysRegsMd, ADAPTER_CTRL, P32XS_REN | P32XS_nRES, Size.WORD); //from Picodrive
        Sh2Util.writeBuffer(vdpRegs, VDP_BITMAP_MODE, NTSC_MASK, Size.WORD);
        Sh2Util.writeBuffer(vdpRegs, FBCR, P32XV_VBLK | P32XV_PEN, Size.WORD);
        dramBanks[0] = ByteBuffer.allocateDirect(DRAM_SIZE);
        dramBanks[1] = ByteBuffer.allocateDirect(DRAM_SIZE);
        vdp = MarsVdp.createInstance(dramBanks, colorPalette);
    }

    public void setBus(S32xBus bus) {
        this.bus = bus;
    }

    public MarsVdp getVdp() {
        return vdp;
    }

    public void setHBlankOn(boolean hBlankOn) {
        this.hBlankOn = hBlankOn;
        int val = Sh2Util.readBuffer(vdpRegs, FBCR, Size.WORD);
        val = (hBlankOn ? 1 : 0) << 14 | (val & 0xBFFF);
        Sh2Util.writeBuffer(vdpRegs, FBCR, val, Size.WORD);
        if (hBlankOn) {
            int hCnt = Sh2Util.readBuffer(sysRegsSh2, HCOUNT_REG, Size.WORD);
            if (--hCount < 0) {
                hCount = Sh2Util.readBuffer(sysRegsSh2, HCOUNT_REG, Size.WORD) & 0xFF;
                interruptControl.setIntPending(MASTER, HINT_10, true);
                interruptControl.setIntPending(SLAVE, HINT_10, true);
            }
        }
        setPen(hBlankOn || vBlankOn ? 1 : 0);
//        System.out.println("HBlank: " + hBlankOn);
    }

    public void setVBlankOn(boolean vBlankOn) {
        this.vBlankOn = vBlankOn;
        int val = Sh2Util.readBuffer(vdpRegs, FBCR, Size.WORD);
        val = (vBlankOn ? 1 : 0) << 15 | (val & 0x7FFF);
        Sh2Util.writeBuffer(vdpRegs, FBCR, val, Size.WORD);
        if (vBlankOn) {
            int screenShift = Sh2Util.readBuffer(vdpRegs, SSCR, Size.WORD) & 1;
            int currentFb = val & 1;
            vdp.draw(videoMode, bitmap_mode, val & 1, screenShift);
            if (currentFb != fsLatch) {
                int newVal = ((val & 0xFFFE) | fsLatch);
                Sh2Util.writeBuffer(vdpRegs, FBCR, newVal, Size.WORD);
                updateFrameBuffer(newVal);
//                System.out.println("##### VBLANK, D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
            }
            interruptControl.setIntPending(MASTER, VINT_12, true);
            interruptControl.setIntPending(SLAVE, VINT_12, true);
        }
        setPen(hBlankOn || vBlankOn ? 1 : 0);
//        System.out.println("VBlank: " + vBlankOn);
    }

    public void write(int address, int value, Size size) {
        address &= 0xFFF_FFFF;
        value &= size.getMask();
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            handleRegWrite(address, value, size);
        } else if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            if (size == Size.WORD) {
                Sh2Util.writeBuffer(colorPalette, address & S32X_COLPAL_MASK, value, size);
            } else {
                System.err.println("Unable to access colorPalette as " + size);
            }
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            if (size == Size.BYTE && value == 0) { //value =0 on byte access is ignored
                return;
            }
            Sh2Util.writeBuffer(dramBanks[frameBufferWritable], address & DRAM_MASK, value, size);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            Sh2Util.writeBuffer(dramBanks[frameBufferWritable], address & DRAM_MASK, value, size);
        } else {
            throw new RuntimeException();
        }
    }

    public int read(int address, Size size) {
        address &= 0xFFF_FFFF;
        int res = 0;
        if (address >= START_32X_SYSREG_CACHE && address < END_32X_VDPREG_CACHE) {
            res = handleRegRead(address, size);
        } else if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            if (size == Size.WORD) {
                res = Sh2Util.readBuffer(colorPalette, address & S32X_COLPAL_MASK, size);
            } else {
                System.err.println("Unable to access colorPalette as " + size);
            }
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            res = Sh2Util.readBuffer(dramBanks[frameBufferWritable], address & DRAM_MASK, size);
        } else {
            throw new RuntimeException();
        }
        return (int) (res & size.getMask());
    }

    private int handleRegRead(int address, Size size) {
        int reg = address & S32X_MMREG_MASK;
        if (size == Size.LONG && reg < COMM0) {
            throw new RuntimeException("unsupported 32 bit access: " + address);
        }
        boolean isSys = address < END_32X_SYSREG_CACHE;
        ByteBuffer regArea = isSys ? (sh2Access == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
        if (verbose) {
            S32xDict.checkName(sh2Access, address, size);
            detectRegAccess(isSys, address, -1, size, true);
        }
        int res = Sh2Util.readBuffer(regArea, reg, size);
        if (sh2Access != M68K && reg < INT_CTRL_REG) {
            res = interruptControl.readSh2IntMaskReg(sh2Access, reg, size);
        }
        return res;
    }

    private boolean handleRegWrite(int address, int value, Size size) {
        boolean skipWrite = false;
        boolean regChanged = false;
        int reg = address & S32X_MMREG_MASK;
        final int regEven = reg & ~1; //even
        if (size == Size.LONG && regEven < COMM0) {
            throw new RuntimeException("unsupported 32 bit access, reg: " + address);
        }
        final boolean isSys = address < END_32X_SYSREG_CACHE;
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
                    regChanged = handleIntMaskRegWrite(sh2Access, reg, value, size);
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
            }
        }
        if (!skipWrite) {
            if (reg < COMM0) {
                final ByteBuffer regArea = isSys ? (sh2Access == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
                Sh2Util.writeBuffer(regArea, reg, value, size);
            } else {
                //comm regs are shared
                Sh2Util.writeBuffer(sysRegsMd, reg, value, size);
                Sh2Util.writeBuffer(sysRegsSh2, reg, value, size);
            }

        }
        if (verbose && regChanged) {
            detectRegAccess(isSys, address, value, size, false);
            logAccess("write", address, value, size, reg, isSys);
        }
        checkName(sh2Access, address, size);
        return regChanged;
    }

    private void handleIntClearWrite(Sh2Access sh2Access, int regEven, int value, Size size) {
        if (sh2Access != M68K) {
            int intIdx = VRES_14.ordinal() - (regEven - 0x14);
            IntC.Sh2Interrupt intType = IntC.intVals[intIdx];
            interruptControl.clearInterrupt(sh2Access, intType);
        }
    }


    private boolean handleReg4Write(Sh2Access sh2Access, int reg, int value, Size size) {
        boolean res = false;
        int baseReg = reg & ~1;
        ByteBuffer b = sh2Access == M68K ? sysRegsMd : sysRegsSh2;
        int val = Sh2Util.readBuffer(b, baseReg, Size.WORD);
        if (val != value || size == Size.BYTE) {
            Sh2Util.writeBuffer(b, reg, value, size);
            int newVal = Sh2Util.readBuffer(b, baseReg, Size.WORD);
            res = val != newVal;
            if (res && sh2Access != M68K) {
                System.out.println(sh2Access + "HCount reg: " + newVal);
            } else if (res) {
                System.out.println(sh2Access + " BankSet reg: " + newVal);
            }
        }
        return res;
    }

    private boolean handleReg2Write(Sh2Access sh2Access, int reg, int value, Size size) {
        boolean res = false;
        int baseReg = reg & ~1;
        ByteBuffer b = sh2Access == M68K ? sysRegsMd : sysRegsSh2;
        int val = Sh2Util.readBuffer(b, baseReg, Size.WORD);
        if (val != value || size == Size.BYTE) {
            Sh2Util.writeBuffer(b, reg, value, size);
            int newVal = Sh2Util.readBuffer(b, baseReg, Size.WORD);
            res = val != newVal;
            if (res && sh2Access != M68K) {
                //Both are automatically cleared if SH2 does not interrupt clear.
                int intm = newVal & 1;
                int ints = (newVal >> 1) & 1;
                if (intm > 0) {
                    interruptControl.setIntPending(MASTER, CMD_8, true);
                }
                if (ints > 0) {
                    interruptControl.setIntPending(SLAVE, CMD_8, true);
                }
            } else if (res) {
                System.out.println(sh2Access + " StandByChange reg: " + newVal);
            }
        }
        return res;
    }

    private boolean handleIntMaskRegWrite(Sh2Access sh2Access, int reg, int value, Size size) {
        boolean res = false;
        switch (sh2Access) {
            case M68K:
                res = handleIntMaskRegWrite68k(reg, value, size);
                break;
            case SLAVE:
            case MASTER:
                res = handleIntMaskRegWriteSh2(sh2Access, reg, value, size);
                break;
        }
        return res;
    }

    private boolean handleIntMaskRegWrite68k(int reg, int value, Size size) {
        int val = Sh2Util.readBuffer(sysRegsMd, INT_MASK, Size.WORD);
        if (val != value || size == Size.BYTE) { //TODO dodgy
            Sh2Util.writeBuffer(sysRegsMd, reg, value, size);
            int newVal = Sh2Util.readBuffer(sysRegsMd, INT_MASK, Size.WORD) | P32XS_REN; //force REN
            if (aden > 0 && (newVal & 1) == 0) {
                System.out.println("#### Disabling ADEN not allowed");
                newVal |= 1;
            }
            //reset cancel
            if ((val & P32XS_nRES) == 0 && (newVal & P32XS_nRES) > 0) {
                //TODO this breaks test2
//                bus.resetSh2();
            }
            Sh2Util.writeBuffer(sysRegsMd, INT_MASK, newVal, Size.WORD);
            setAdenSh2Reg(newVal & 1); //sh2 side read-only
            updateFmShared(newVal); //sh2 side r/w too
            return val != newVal;
        }
        return false;
    }

    private void updateFmShared(int wordVal) {
        if (fm != ((wordVal >> 15) & 1)) {
            setFmSh2Reg((wordVal >> 15) & 1);
        }
    }

    private boolean handleIntMaskRegWriteSh2(Sh2Access sh2Access, int reg, int value, Size size) {
        int baseReg = reg & ~1;
        int val = interruptControl.readSh2IntMaskReg(sh2Access, reg, size);
        if (size == Size.BYTE || val != value) { //TODO dodgy
            interruptControl.writeSh2IntMaskReg(sh2Access, reg, value, size);
            int newVal = interruptControl.readSh2IntMaskReg(sh2Access, baseReg, Size.WORD) | (cart << 8);
            interruptControl.writeSh2IntMaskReg(sh2Access, baseReg, newVal, Size.WORD);
            updateFmShared(newVal); //68k side r/w too
            return newVal != val;
        }
        return false;
    }

    private boolean handleBitmapModeWrite(int reg, int value, Size size) {
        int val = Sh2Util.readBuffer(vdpRegs, VDP_BITMAP_MODE, Size.WORD);
        if (size == Size.BYTE || val != value) {
            Sh2Util.writeBuffer(vdpRegs, reg, value, size);
            val = Sh2Util.readBuffer(vdpRegs, VDP_BITMAP_MODE, Size.WORD) | NTSC_MASK;
            Sh2Util.writeBuffer(vdpRegs, VDP_BITMAP_MODE, val, Size.WORD);
            bitmap_mode = BITMAP_MODE.vals[val & 3];
            return true;
        }
        return false;
    }

    private boolean handleFBCRWrite(int reg, int value, Size size) {
        int val = Sh2Util.readBuffer(vdpRegs, FBCR, Size.WORD);
        if (size == Size.BYTE || val != value) { //TODO dodgy
            Sh2Util.writeBuffer(vdpRegs, reg, value, size);
            int val1 = Sh2Util.readBuffer(vdpRegs, FBCR, Size.WORD);
            int regVal = 0;
            if (vBlankOn || bitmap_mode == BITMAP_MODE.BLANK) {
                regVal = (val & 0xFFFC) | (val1 & 3);
                updateFrameBuffer(regVal);
            } else {
                //during display the register always shows the current frameBuffer being displayed
                regVal = (val & 0xFFFD) | (val1 & 2);
            }
            Sh2Util.writeBuffer(vdpRegs, FBCR, regVal, Size.WORD);
            fsLatch = val1 & 1;
//            System.out.println("###### FBCR write: D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
            return true;
        }
        return false;
    }

    public void setInterruptControl(IntC interruptControl) {
        this.interruptControl = interruptControl;
    }

    public void setCart(int cart) {
        this.cart = cart;
        int valM = interruptControl.readSh2IntMaskReg(MASTER, 0, Size.BYTE);
        int valS = interruptControl.readSh2IntMaskReg(SLAVE, 0, Size.BYTE);
        interruptControl.writeSh2IntMaskReg(MASTER, 0, valM | cart, Size.BYTE);
        interruptControl.writeSh2IntMaskReg(SLAVE, 0, valS | cart, Size.BYTE);
        LOG.info("Cart set to: " + cart);
    }

    private void setAdenSh2Reg(int aden) {
        this.aden = aden;
        int valM = interruptControl.readSh2IntMaskReg(MASTER, 0, Size.BYTE);
        int valS = interruptControl.readSh2IntMaskReg(SLAVE, 0, Size.BYTE);
        interruptControl.writeSh2IntMaskReg(MASTER, 0, valM | (aden << 1), Size.BYTE);
        interruptControl.writeSh2IntMaskReg(SLAVE, 0, valS | (aden << 1), Size.BYTE);
    }

    private void setFmSh2Reg(int fm) {
        this.fm = fm;
        int valM = interruptControl.readSh2IntMaskReg(MASTER, 0, Size.BYTE) & 0x7F;
        int valS = interruptControl.readSh2IntMaskReg(SLAVE, 0, Size.BYTE) & 0x7F;
        int val68k = Sh2Util.readBuffer(sysRegsMd, 0, Size.BYTE) & 0x7F;
        interruptControl.writeSh2IntMaskReg(MASTER, 0, valM | (fm << 7), Size.BYTE);
        interruptControl.writeSh2IntMaskReg(SLAVE, 0, valS | (fm << 7), Size.BYTE);
        Sh2Util.writeBuffer(sysRegsMd, INT_MASK, val68k | (fm << 7), Size.BYTE);
        System.out.println(sh2Access + " FM: " + fm);
    }

    private void setPen(int pen) {
        this.pen = pen;
        int val = (pen << 5) | (Sh2Util.readBuffer(vdpRegs, FBCR, Size.BYTE) & 0xDF);
        Sh2Util.writeBuffer(vdpRegs, FBCR, val, Size.BYTE);
    }

    private void updateFrameBuffer(int val) {
        frameBufferDisplay = val & 1;
        frameBufferWritable = (frameBufferDisplay + 1) & 1;
    }

    private void runAutoFill(int data) {
        int startAddr = Sh2Util.readBuffer(vdpRegs, AFSAR, Size.WORD);
        int len = Sh2Util.readBuffer(vdpRegs, AFLR, Size.WORD) & 0xFF;
        runAutoFillInternal(dramBanks[frameBufferWritable], startAddr, data, len);
    }

    public void runAutoFillInternal(ByteBuffer buffer, int startAddrWord, int data, int len) {
        int addrFixed = startAddrWord & 0xFF00;
        int addrVariable = startAddrWord & 0xFF;
//     String s1 = "start %08X, len %04X, data %04X";
//                    System.out.println(String.format(s1, startAddr, len, data));
        len = len == 0 ? 0xFF : len;
        final int dataWord = (data << 8) | data;
        do {
            Sh2Util.writeBuffer(buffer, (addrFixed + addrVariable) << 1, dataWord, Size.WORD);
            addrVariable = (addrVariable + 1) & 0xFF;
            len--;
        } while (len > 0);
        Sh2Util.writeBuffer(vdpRegs, AFSAR, addrFixed + addrVariable, Size.WORD);
    }

    private static void logAccess(String type, int address, int value, Size size, int reg, boolean isSys) {
        reg &= ~1;
        int rns = sh2Access == M68K ? M68K.ordinal() : MASTER.ordinal();
        System.out.println(sh2Access + " 32X reg " + type + " " +
                size + ", (" + s32xRegNames[rns][isSys ? reg : reg + 0x100] + ") " + Integer.toHexString(address) + ": " +
                Integer.toHexString(value));
    }

    private void detectRegAccess(boolean isSys, int address, int value, Size size, boolean read) {
        String sformat = "%s %s %s, %s(%X), %4X %s %s";
        final String evenOdd = (address & 1) == 0 ? "E" : "O";
        String type = read ? "R" : "W";
        int reg = (address & 0xFF) & ~1;
        String s = null;
        final ByteBuffer regArea = isSys ? (sh2Access == M68K ? sysRegsMd : sysRegsSh2) : vdpRegs;
        int currentWord = Sh2Util.readBuffer(regArea, reg, Size.WORD);
        value = read ? currentWord : value;
        int rns = sh2Access == M68K ? M68K.ordinal() : MASTER.ordinal();
        if (!isSys) {
            final int regNamePos = reg + 0x100;
            switch (reg) {
                case VDP_BITMAP_MODE:
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            BITMAP_MODE.vals[value & 3].name(), value & 3, value, size.name(), evenOdd);
                    break;
                case FBCR:
                    String s1 = "D" + frameBufferDisplay + "W" + frameBufferWritable +
                            "|H" + ((value >> 14) & 1) + "V" + ((value >> 15) & 1);
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            s1, value & 3, value, size.name(), evenOdd);
                    break;
                default:
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            "", value, value, size.name(), evenOdd);
                    break;
            }
        } else {
            switch (reg) {
                case INT_MASK:
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][reg],
                            "[RESET: " + ((value & 3) >> 1) + ", ADEN: " + (value & 1) + "]", value & 3,
                            value, size.name(), evenOdd);
                    break;
                case BANK_SET_REG:
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][reg],
                            "", value & 3, value, size.name(), evenOdd);
                    break;
                case COMM0:
                case COMM1:
                case COMM2:
                case COMM3:
                case COMM4:
                case COMM5:
                case COMM6:
                case COMM7:
                    if (read) {
                        return;
                    }
                    int valueMem = Sh2Util.readBuffer(sysRegsSh2, reg, Size.LONG);
                    String s1 = "";
                    if (valueMem > 0x10_00_00_00) { //might be ASCII
                        s1 = "'" + (char) ((valueMem & 0xFF000000) >> 24) +
                                (char) ((valueMem & 0x00FF0000) >> 16) +
                                (char) ((valueMem & 0x0000FF00) >> 8) +
                                (char) ((valueMem & 0x000000FF) >> 0) + "'";
                    }
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][reg],
                            s1, value, valueMem, size.name(), evenOdd);
                    break;
                default:
                    s = String.format(sformat, sh2Access.toString(), type, s32xRegNames[rns][reg], "",
                            value, value, size.name(), evenOdd);
                    break;
            }
        }
        if (s != null) {
            System.out.println(s);
        }
    }
}