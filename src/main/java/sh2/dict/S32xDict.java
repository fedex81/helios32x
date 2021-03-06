package sh2.dict;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil;
import sh2.vdp.MarsVdp;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.S32xRegCpuType.*;
import static sh2.dict.S32xDict.S32xRegType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xDict {

    private static final Logger LOG = LogManager.getLogger(S32xDict.class.getSimpleName());

    public static final int S32X_REG_SIZE = 0x200;
    public static final int S32X_REG_MASK = S32X_REG_SIZE - 1;
    public static final int S32X_VDP_REG_MASK = 0xFF;

    public enum S32xRegCpuType {REG_M68K, REG_SH2, REG_BOTH}

    public enum S32xRegType {NONE, VDP, PWM, SYS, COMM, DMA}

    public static S32xRegType[] s32xRegTypeMapping = new S32xRegType[S32X_REG_SIZE];
    public static RegSpecS32x[][] s32xRegMapping = new RegSpecS32x[S32xRegCpuType.values().length][S32X_REG_SIZE];

    private static S32xRegCpuType[] cpuToRegTypeMapper =
            new S32xRegCpuType[S32xRegCpuType.values().length];

    static {
        cpuToRegTypeMapper[MASTER.ordinal()] = REG_SH2;
        cpuToRegTypeMapper[SLAVE.ordinal()] = REG_SH2;
        cpuToRegTypeMapper[M68K.ordinal()] = REG_M68K;
    }

    public enum RegSpecS32x {
        SH2_INT_MASK(SYS, 0, "SH2_INT_MASK", Size.WORD),   //Interrupt Mask
        SH2_STBY_CHANGE(SYS, 2, "SH2_STBY_CHANGE", Size.WORD),   //StandBy Changer Register
        SH2_HCOUNT_REG(SYS, 4, "SH2_HCOUNT_REG", Size.WORD), //H Count Register
        SH2_DREQ_CTRL(DMA, 6, "SH2_DREQ_CTRL", Size.WORD), //DREQ Control Reg.
        SH2_DREQ_SRC_ADDR_H(DMA, 8, "SH2_DREQ_SRC_ADDR_H", Size.WORD),
        SH2_DREQ_SRC_ADDR_L(DMA, 0xA, "SH2_DREQ_SRC_ADDR_L", Size.WORD),
        SH2_DREQ_DEST_ADDR_H(DMA, 0xC, "SH2_DREQ_DEST_ADDR_H", Size.WORD),
        SH2_DREQ_DEST_ADDR_L(DMA, 0xE, "SH2_DREQ_DEST_ADDR_L", Size.WORD),
        SH2_DREQ_LEN(DMA, 0x10, "SH2_DREQ_LEN", Size.WORD),
        SH2_FIFO_REG(DMA, 0x12, "SH2_FIFO_REG", Size.WORD),
        SH2_VRES_INT_CLEAR(SYS, 0x14, "SH2_VRES_INT_CLEAR", Size.WORD),//VRES Interrupt Clear Register
        SH2_VINT_CLEAR(SYS, 0x16, "SH2_VINT_CLEAR", Size.WORD),
        SH2_HINT_CLEAR(SYS, 0x18, "SH2_HINT_CLEAR", Size.WORD),
        SH2_CMD_INT_CLEAR(SYS, 0x1A, "SH2_CMD_INT_CLEAR", Size.WORD),
        SH2_PWM_INT_CLEAR(SYS, 0x1C, "SH2_PWM_INT_CLEAR", Size.WORD),

        M68K_ADAPTER_CTRL(SYS, 0, "M68K_ADAPTER_CTRL", Size.WORD),
        M68K_INT_CTRL(SYS, 2, "M68K_INT_CTRL", Size.WORD),  //Interrupt Control Register
        M68K_BANK_SET(SYS, 4, "M68K_BANK_SET", Size.WORD),  //Bank Set Register
        M68K_DMAC_CTRL(DMA, 6, "M68K_DMAC_CTRL", Size.WORD), //Transfers Data to SH2 DMAC
        M68K_DREQ_SRC_ADDR_H(DMA, 8, "M68K_DREQ_SRC_ADDR_H", Size.WORD),
        M68K_DREQ_SRC_ADDR_L(DMA, 0xA, "M68K_DREQ_SRC_ADDR_L", Size.WORD),
        M68K_DREQ_DEST_ADDR_H(DMA, 0xC, "M68K_DREQ_DEST_ADDR_H", Size.WORD),
        M68K_DREQ_DEST_ADDR_L(DMA, 0xE, "M68K_DREQ_DEST_ADDR_L", Size.WORD),
        M68K_DREQ_LEN(DMA, 0x10, "M68K_DREQ_LEN", Size.WORD),
        M68K_FIFO_REG(DMA, 0x12, "M68K_FIFO_REG", Size.WORD),
        M68K_SEGA_TV(SYS, 0x1A, "M68K_SEGA_TV", Size.WORD),

        COMM0(COMM, 0x20, "COMM0", Size.WORD),
        COMM1(COMM, 0x22, "COMM1", Size.WORD),
        COMM2(COMM, 0x24, "COMM2", Size.WORD),
        COMM3(COMM, 0x26, "COMM3", Size.WORD),
        COMM4(COMM, 0x28, "COMM4", Size.WORD),
        COMM5(COMM, 0x2A, "COMM5", Size.WORD),
        COMM6(COMM, 0x2C, "COMM6", Size.WORD),
        COMM7(COMM, 0x2E, "COMM7", Size.WORD),

        PWM_CTRL(PWM, 0x30, "PWM_CTRL", Size.WORD),
        PWM_CYCLE(PWM, 0x32, "PWM_CYCLE", Size.WORD), //PWM Cycle Register
        PWM_LCH_PW(PWM, 0x34, "PWM_LCH_PW", Size.WORD), //PWM Left channel Pulse Width Reg
        PWM_RCH_PW(PWM, 0x36, "PWM_RCH_PW", Size.WORD), //PWM Right channel Pulse Width Reg
        PWM_MONO(PWM, 0x38, "PWM_MONO", Size.WORD), //PWM Mono Pulse Width Reg

        VDP_BITMAP_MODE(VDP, 0x100, "VDP_BITMAP_MODE", Size.WORD),
        SSCR(VDP, 0x102, "SSCR", Size.WORD), //Screen Shift Control Register
        AFLR(VDP, 0x104, "AFLR", Size.WORD), //Auto Fill Length Register
        AFSAR(VDP, 0x106, "AFSAR", Size.WORD), //Auto Fill Start Address Register
        AFDR(VDP, 0x108, "AFDR", Size.WORD), //Auto Fill Data Register
        FBCR(VDP, 0x10A, "FBCR", Size.WORD), //Frame Buffer Control Register

        INVALID(NONE, -1, "INVALID", Size.WORD);;

        public final S32xRegCpuType regCpuType;
        public final S32xRegType deviceType;
        public final int fullAddress, addr, addrMask;
        public final String name;
        public final Size size;
        public final int deviceAccessTypeDelay;

        private RegSpecS32x(S32xRegType deviceType, int addr, String name, Size size) {
            this.fullAddress = addr;
            this.addrMask = (deviceType != VDP ? S32X_REG_MASK : S32X_VDP_REG_MASK);
            this.addr = addr & addrMask;
            this.name = name;
            this.size = size;
            this.deviceType = deviceType;
            this.deviceAccessTypeDelay = deviceType == VDP ? S32xMemAccessDelay.VDP_REG : S32xMemAccessDelay.SYS_REG;
            this.regCpuType = deviceType == NONE || deviceType == COMM || deviceType == PWM || deviceType == VDP ? S32xRegCpuType.REG_BOTH :
                    S32xRegCpuType.valueOf("REG_" + name.split("_")[0]);
            init();
        }

        private void init() {
            if (deviceType == NONE) {
                return;
            }
            int addrLen = Math.max(1, size.ordinal() << 1);
            for (int i = fullAddress; i < fullAddress + addrLen; i++) {
                s32xRegMapping[regCpuType.ordinal()][i] = this;
                s32xRegTypeMapping[i] = deviceType;
                if (regCpuType == S32xRegCpuType.REG_BOTH) {
                    s32xRegMapping[REG_M68K.ordinal()][i] = this;
                    s32xRegMapping[REG_SH2.ordinal()][i] = this;
                }
            }
        }
    }

    public static final int P32XS_FM = (1 << 15);
    public static final int P32XS_nCART = (1 << 8);
    public static final int P32XS_REN = (1 << 7);
    public static final int P32XS_nRES = (1 << 1);
    public static final int P32XS_ADEN = (1 << 0);
    public static final int P32XS2_ADEN = (1 << 9);
    public static final int P32XS_FULL = (1 << 7); // DREQ FIFO full
    public static final int P32XS_68S = (1 << 2);
    public static final int P32XS_DMA = (1 << 1);
    public static final int P32XS_RV = (1 << 0);

    public static final int INTMASK_HEN_BIT_POS = 7;
    public static final int FBCR_VBLK_BIT_POS = 15;
    public static final int FBCR_HBLK_BIT_POS = 14;
    public static final int FBCR_FRAMESEL_BIT_POS = 0;
    public static final int FBCR_nFEN_BIT_POS = 1;
    public static final int P32XV_VBLK = (1 << FBCR_VBLK_BIT_POS);
    public static final int P32XV_PAL = (1 << 15);

    public static final int P32XV_PEN = (1 << 13);
    public static final int P32XV_PRIO = (1 << 7);
    public static final int P32XV_240 = (1 << 6);

    /**
     * SH2 memory map
     **/
    private static final int SH2_BOOT_ROM_SIZE = 0x4000; // 16kb
    public static final int SH2_SDRAM_SIZE = 0x4_0000; // 256kb
    private static final int SH2_MAX_ROM_SIZE = 0x40_0000; // 256kb
    public static final int SH2_SDRAM_MASK = SH2_SDRAM_SIZE - 1;
    private static final int SH2_ROM_MASK = SH2_MAX_ROM_SIZE - 1;

    public static final int SH2_CACHE_THROUGH_OFFSET = 0x2000_0000;

    public static final int SH2_START_BOOT_ROM = SH2_CACHE_THROUGH_OFFSET;
    public static final int SH2_END_BOOT_ROM = SH2_START_BOOT_ROM + SH2_BOOT_ROM_SIZE;

    public static final int SH2_START_SDRAM_CACHE = 0x600_0000;
    public static final int SH2_START_SDRAM = SH2_CACHE_THROUGH_OFFSET + SH2_START_SDRAM_CACHE;
    public static final int SH2_END_SDRAM_CACHE = SH2_START_SDRAM_CACHE + SH2_SDRAM_SIZE;
    public static final int SH2_END_SDRAM = SH2_START_SDRAM + SH2_SDRAM_SIZE;

    public static final int SH2_START_ROM_CACHE = 0x200_0000;
    public static final int SH2_START_ROM = SH2_CACHE_THROUGH_OFFSET + SH2_START_ROM_CACHE;
    public static final int SH2_END_ROM_CACHE = SH2_START_ROM_CACHE + 0x40_0000; //4 Mbit window;
    public static final int SH2_END_ROM = SH2_START_ROM + 0x40_0000; //4 Mbit window;

    public static final int SH2_START_CACHE_FLUSH = 0x6000_0000;
    public static final int SH2_END_CACHE_FLUSH = 0x8000_0000;
    public static final int SH2_START_DATA_ARRAY = 0xC000_0000;
    public static final int SH2_ONCHIP_REG_MASK = 0xE000_4000;
    public static final int SH2_START_DRAM_MODE = 0xFFFF_8000;
    public static final int SH2_END_DRAM_MODE = 0xFFFF_C000;

    public static final int SIZE_32X_SYSREG = 0x100;
    public static final int SIZE_32X_VDPREG = 0x100;
    public static final int SIZE_32X_COLPAL = 0x200; // 512 bytes, 256 words
    public static final int DRAM_SIZE = 0x20000; //128 kb window, 2 DRAM banks 128kb each
    public static final int DRAM_MASK = DRAM_SIZE - 1;
    public static final int S32X_MMREG_MASK = 0xFF;
    public static final int S32X_COLPAL_MASK = SIZE_32X_COLPAL - 1;

    public static final int START_32X_SYSREG_CACHE = 0x4000;
    public static final int END_32X_SYSREG_CACHE = START_32X_SYSREG_CACHE + SIZE_32X_SYSREG;
    public static final int START_32X_VDPREG_CACHE = END_32X_SYSREG_CACHE;
    public static final int END_32X_VDPREG_CACHE = START_32X_VDPREG_CACHE + SIZE_32X_VDPREG;
    public static final int START_32X_COLPAL_CACHE = END_32X_VDPREG_CACHE;
    public static final int END_32X_COLPAL_CACHE = START_32X_COLPAL_CACHE + SIZE_32X_COLPAL;

    public static final int START_32X_SYSREG = START_32X_SYSREG_CACHE + SH2_CACHE_THROUGH_OFFSET;
    public static final int START_32X_VDPREG = START_32X_VDPREG_CACHE + SH2_CACHE_THROUGH_OFFSET;
    public static final int START_32X_COLPAL = START_32X_COLPAL_CACHE + SH2_CACHE_THROUGH_OFFSET;
    public static final int END_32X_SYSREG = START_32X_SYSREG + SIZE_32X_SYSREG;
    public static final int END_32X_VDPREG = START_32X_VDPREG + SIZE_32X_VDPREG;
    public static final int END_32X_COLPAL = START_32X_COLPAL + SIZE_32X_COLPAL;

    public static final int START_DRAM_CACHE = 0x400_0000;
    public static final int END_DRAM_CACHE = START_DRAM_CACHE + DRAM_SIZE;
    public static final int START_DRAM = START_DRAM_CACHE + SH2_CACHE_THROUGH_OFFSET;
    public static final int END_DRAM = END_DRAM_CACHE + SH2_CACHE_THROUGH_OFFSET;

    public static final int START_OVER_IMAGE_CACHE = 0x402_0000;
    public static final int END_OVER_IMAGE_CACHE = START_OVER_IMAGE_CACHE + DRAM_SIZE;
    public static final int START_OVER_IMAGE = START_OVER_IMAGE_CACHE + SH2_CACHE_THROUGH_OFFSET;
    public static final int END_OVER_IMAGE = END_OVER_IMAGE_CACHE + SH2_CACHE_THROUGH_OFFSET;

    /**
     * M68K memory map
     **/
    public static final int M68K_START_HINT_VECTOR_WRITEABLE = 0x70;
    public static final int M68K_END_HINT_VECTOR_WRITEABLE = 0x74;
    public static final int M68K_END_VECTOR_ROM = 0x100;
    public static final int M68K_START_FRAME_BUFFER = 0x84_0000;
    public static final int M68K_END_FRAME_BUFFER = M68K_START_FRAME_BUFFER + DRAM_SIZE;
    public static final int M68K_START_OVERWRITE_IMAGE = 0x86_0000;
    public static final int M68K_END_OVERWRITE_IMAGE = M68K_START_OVERWRITE_IMAGE + DRAM_SIZE;
    public static final int M68K_START_ROM_MIRROR = 0x88_0000;
    public static final int M68K_END_ROM_MIRROR = 0x90_0000;
    public static final int M68K_START_ROM_MIRROR_BANK = M68K_END_ROM_MIRROR;
    public static final int M68K_END_ROM_MIRROR_BANK = 0xA0_0000;
    public static final int M68K_ROM_WINDOW_MASK = 0x7_FFFF; //according to docs, *NOT* 0xF_FFFF
    public static final int M68K_ROM_MIRROR_MASK = 0xF_FFFF;
    public static final int M68K_START_MARS_ID = 0xA130EC;
    public static final int M68K_END_MARS_ID = 0xA130F0;
    public static final int M68K_START_32X_SYSREG = 0xA1_5100;
    public static final int M68K_END_32X_SYSREG = M68K_START_32X_SYSREG + 0x80;
    public static final int M68K_MASK_32X_SYSREG = M68K_END_32X_SYSREG - M68K_START_32X_SYSREG - 1;
    public static final int M68K_START_32X_VDPREG = M68K_END_32X_SYSREG;
    public static final int M68K_END_32X_VDPREG = M68K_START_32X_VDPREG + 0x80;
    public static final int M68K_MASK_32X_VDPREG = M68K_MASK_32X_SYSREG;
    public static final int M68K_START_32X_COLPAL = M68K_END_32X_VDPREG;
    public static final int M68K_END_32X_COLPAL = M68K_START_32X_COLPAL + 0x200;
    public static final int M68K_MASK_32X_COLPAL = M68K_END_32X_COLPAL - M68K_START_32X_COLPAL - 1;

    public static final int SH2_SYSREG_32X_OFFSET = S32xDict.START_32X_SYSREG,
            SH2_VDPREG_32X_OFFSET = START_32X_VDPREG, SH2_COLPAL_32X_OFFSET = START_32X_COLPAL;

    public static class S32xDictLogContext {
        public S32xUtil.CpuDeviceAccess sh2Access;
        public ByteBuffer regArea;
        public RegSpecS32x regSpec;
        public int fbD, fbW;
        public boolean read;
    }

    public static void checkName(S32xUtil.CpuDeviceAccess sh2Access, RegSpecS32x regSpec, int address, Size size) {
        if (regSpec == null) {
            LOG.warn("{} 32X mmreg unknown reg: {} {}", sh2Access, th(address), size);
        }
    }

    public static void logAccess(S32xDictLogContext logCtx, int address, int value, Size size) {
        LOG.info("{} 32x reg {} {} ({}) {} {}", logCtx.sh2Access, logCtx.read ? "read" : "write",
                size, logCtx.regSpec.name, th(address), !logCtx.read ? ": " + th(value) : "");
    }

    public static void detectRegAccess(S32xDictLogContext logCtx, int address, int value, Size size) {
        String sformat = "%s %s %s, %s(%X), %4X %s %s";
        final String evenOdd = (address & 1) == 0 ? "EVEN" : "ODD";
        String type = logCtx.read ? "R" : "W";
        RegSpecS32x regSpec = logCtx.regSpec;
        String s = null;
        int currentWord = S32xUtil.readBuffer(logCtx.regArea, regSpec.addr, Size.WORD);
        value = logCtx.read ? currentWord : value;
        switch (regSpec) {
            case VDP_BITMAP_MODE:
                s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name,
                        MarsVdp.BitmapMode.vals[value & 3].name(), value & 3, value, size.name(), evenOdd);
                break;
            case FBCR:
                String s1 = "D" + logCtx.fbD + "W" + logCtx.fbW +
                        "|H" + ((value >> 14) & 1) + "V" + ((value >> 15) & 1);
                s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name,
                        s1, value & 3, value, size.name(), evenOdd);
                break;
            case SH2_INT_MASK:
                if (logCtx.sh2Access == S32xUtil.CpuDeviceAccess.M68K) {
                    s = String.format(sformat, logCtx.sh2Access, type, regSpec.name,
                            "[RESET: " + ((value & 3) >> 1) + ", ADEN: " + (value & 1) + "]", value & 3,
                            value, size.name(), evenOdd);
                } else {
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name, "", value,
                            value, size.name(), evenOdd);
                }
                break;
            case M68K_BANK_SET:
                s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name,
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
                if (logCtx.read) {
                    return;
                }
                int valueMem = S32xUtil.readBuffer(logCtx.regArea, address & S32X_REG_MASK, Size.LONG);
                String s2 = decodeComm(valueMem);
                s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name,
                        s2, value, valueMem, size.name(), evenOdd);
                break;
            default:
                s = String.format(sformat, logCtx.sh2Access.toString(), type, regSpec.name,
                        "", value, value, size.name(), evenOdd);
                break;
        }
        if (s != null) {
            LOG.info(s);
        }
    }

    public static RegSpecS32x getRegSpec(S32xRegCpuType regCpuType, int address) {
        RegSpecS32x r = s32xRegMapping[regCpuType.ordinal()][address & S32X_REG_MASK];
        if (r == null) {
            LOG.error("{} unknown register at address: {}", regCpuType, th(address));
        }
        return r;
    }

    public static RegSpecS32x getRegSpec(S32xUtil.CpuDeviceAccess cpu, int address) {
        RegSpecS32x r = s32xRegMapping[REG_BOTH.ordinal()][address & S32X_REG_MASK];
        if (r != null) {
            return r;
        }
        r = s32xRegMapping[cpuToRegTypeMapper[cpu.ordinal()].ordinal()][address & S32X_REG_MASK];
        if (r == null) {
            LOG.error("{} unknown register at address: {}", cpu, th(address));
            r = RegSpecS32x.INVALID;
        }
        return r;
    }

    public static String decodeComm(int valueMem) {
        String s1 = "";
        if (valueMem > 0x10_00_00_00) { //might be ASCII
            s1 = "'" + (char) ((valueMem & 0xFF000000) >> 24) +
                    (char) ((valueMem & 0x00FF0000) >> 16) +
                    (char) ((valueMem & 0x0000FF00) >> 8) +
                    (char) ((valueMem & 0x000000FF) >> 0) + "'";
        }
        return s1;
    }
}
