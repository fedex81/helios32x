package sh2.dict;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.MarsVdp;
import sh2.Sh2Util;

import java.nio.ByteBuffer;

import static sh2.Sh2Util.CpuDeviceAccess.M68K;
import static sh2.Sh2Util.CpuDeviceAccess.MASTER;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xDict {

    private static final Logger LOG = LogManager.getLogger(S32xDict.class.getSimpleName());

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

    public static final int P32XV_VBLK = (1 << 15);
    public static final int P32XV_PAL = (1 << 15);
    public static final int P32XV_HBLK = (1 << 14);
    public static final int P32XV_PEN = (1 << 13);
    public static final int P32XV_240 = (1 << 6);
    public static final int P32XV_nFEN = (1 << 1);
    public static final int P32XV_FS = (1 << 0);

    public static String[][] s32xRegNames = new String[3][0x200];

    public static final int INT_MASK = 0;
    public static final int ADAPTER_CTRL = 0;
    public static final int INT_CTRL_REG = 2; //Interrupt Control Register
    public static final int STBY_CHANGE_REG = 2; //StandBy Changer Register
    public static final int BANK_SET_REG = 4; //Bank Set Register
    public static final int HCOUNT_REG = 4; //H Count Register
    public static final int DREQ_CTRL = 6;//DREQ Control Reg.
    public static final int DMAC_CTRL = 6;//Transfers Data to SH2 DMAC
    public static final int DREQ_SRC_ADDR_H = 8;//DREQ Source Address Reg.
    public static final int DREQ_SRC_ADDR_L = 0xA;//DREQ Source Address Reg.
    public static final int DREQ_DEST_ADDR_H = 0xC;//DREQ Destination Address High
    public static final int DREQ_DEST_ADDR_L = 0xE;//DREQ Destination Address Low
    public static final int DREQ_LEN = 0x10;//DREQ Length Reg.
    public static final int FIFO_REG = 0x12;//FIFO Reg.

    public static final int VRES_INT_CLEAR = 0x14;//VRES Interrupt Clear Register
    public static final int VINT_CLEAR = 0x16;
    public static final int HINT_CLEAR = 0x18;
    public static final int CMD_INT_CLEAR = 0x1A;
    public static final int SEGA_TV = 0x1A;
    public static final int PWM_INT_CLEAR = 0x1C;
    public static final int COMM0 = 0x20;
    public static final int COMM1 = 0x22;
    public static final int COMM2 = 0x24;
    public static final int COMM3 = 0x26;
    public static final int COMM4 = 0x28;
    public static final int COMM5 = 0x2A;
    public static final int COMM6 = 0x2C;
    public static final int COMM7 = 0x2E;

    public static final int PWM_CTRL = 0x30;//PWM Control
    public static final int PWM_FS = 0x32;//PWM fs Reg.
    public static final int PWM_LCH_PW = 0x34;//PWM Left channel Pulse Width Reg
    public static final int PWM_RCH_PW = 0x36;//PWM Right channel Pulse Width Reg
    public static final int PWM_MONO = 0x38;//PWM Mono Reg.

    public static final int VDP_BITMAP_MODE = 0;
    public static final int SSCR = 0x2; //Screen Shift Control Register
    public static final int AFLR = 0x4; //Auto Fill Length Register
    public static final int AFSAR = 0x6; //Auto Fill Start Address Register
    public static final int AFDR = 0x8; //Auto Fill Data Register
    public static final int FBCR = 0xA; //Frame Buffer Control Register

    static {
        //sh2
        s32xRegNames[MASTER.ordinal()][INT_MASK] = "INT_MASK";
        s32xRegNames[MASTER.ordinal()][STBY_CHANGE_REG] = "STBY_CHANGE_REG";
        s32xRegNames[MASTER.ordinal()][HCOUNT_REG] = "HCOUNT_REG";
        s32xRegNames[MASTER.ordinal()][DREQ_CTRL] = "DREQ_CTRL";
        s32xRegNames[MASTER.ordinal()][DREQ_SRC_ADDR_H] = "DREQ_SRC_ADDR_HIGH";
        s32xRegNames[MASTER.ordinal()][DREQ_SRC_ADDR_L] = "DREQ_SRC_ADDR_LOW";
        s32xRegNames[MASTER.ordinal()][DREQ_DEST_ADDR_H] = "DREQ_DEST_ADDR_HIGH";
        s32xRegNames[MASTER.ordinal()][DREQ_DEST_ADDR_L] = "DREQ_DEST_ADDR_LOW";
        s32xRegNames[MASTER.ordinal()][DREQ_LEN] = "DREQ_LEN";
        s32xRegNames[MASTER.ordinal()][FIFO_REG] = "FIFO_REG";
        s32xRegNames[MASTER.ordinal()][PWM_CTRL] = "PWM_CTRL";
        s32xRegNames[MASTER.ordinal()][PWM_FS] = "PWM_FS";
        s32xRegNames[MASTER.ordinal()][PWM_LCH_PW] = "PWM_LCH_PW";
        s32xRegNames[MASTER.ordinal()][PWM_RCH_PW] = "PWM_RCH_PW";
        s32xRegNames[MASTER.ordinal()][PWM_MONO] = "PWM_MONO";
        s32xRegNames[MASTER.ordinal()][VRES_INT_CLEAR] = "VRES_INT_CLEAR";
        s32xRegNames[MASTER.ordinal()][VINT_CLEAR] = "VINT_CLEAR";
        s32xRegNames[MASTER.ordinal()][HINT_CLEAR] = "HINT_CLEAR";
        s32xRegNames[MASTER.ordinal()][CMD_INT_CLEAR] = "CMD_INT_CLEAR";
        s32xRegNames[MASTER.ordinal()][PWM_INT_CLEAR] = "PWM_INT_CLEAR";
        //TODO supposed to be byte/word but bios reads/writes longs from comm0
        s32xRegNames[MASTER.ordinal()][COMM0] = "COMM0";
        s32xRegNames[MASTER.ordinal()][COMM1] = "COMM1";
        s32xRegNames[MASTER.ordinal()][COMM2] = "COMM2";
        s32xRegNames[MASTER.ordinal()][COMM3] = "COMM3";
        s32xRegNames[MASTER.ordinal()][COMM4] = "COMM4";
        s32xRegNames[MASTER.ordinal()][COMM5] = "COMM5";
        s32xRegNames[MASTER.ordinal()][COMM6] = "COMM6";
        s32xRegNames[MASTER.ordinal()][COMM7] = "COMM7";
        //vdp regs
        s32xRegNames[MASTER.ordinal()][VDP_BITMAP_MODE + 0x100] = "VDP_BITMAP_MODE";
        s32xRegNames[MASTER.ordinal()][FBCR + 0x100] = "FBCR"; //Frame Buffer Control Register
        s32xRegNames[MASTER.ordinal()][AFLR + 0x100] = "AFLR";//Auto Fill Length Register
        s32xRegNames[MASTER.ordinal()][SSCR + 0x100] = "SSCR";//Screen Shift Control Register
        s32xRegNames[MASTER.ordinal()][AFSAR + 0x100] = "AFSAR";//Auto Fill Start Address Register
        s32xRegNames[MASTER.ordinal()][AFDR + 0x100] = "AFDR"; //Auto Fill Data Register

        //68k
        s32xRegNames[M68K.ordinal()][ADAPTER_CTRL] = "ADAPTER_CTRL";
        s32xRegNames[M68K.ordinal()][INT_CTRL_REG] = "INT_CTRL_REG";
        s32xRegNames[M68K.ordinal()][BANK_SET_REG] = "BANK_SET_REG";
        s32xRegNames[M68K.ordinal()][DMAC_CTRL] = "DMAC_CTRL";
        s32xRegNames[M68K.ordinal()][DREQ_SRC_ADDR_H] = "DREQ_SRC_ADDR_HIGH";
        s32xRegNames[M68K.ordinal()][DREQ_SRC_ADDR_L] = "DREQ_SRC_ADDR_LOW";
        s32xRegNames[M68K.ordinal()][DREQ_DEST_ADDR_H] = "DREQ_DEST_ADDR_HIGH";
        s32xRegNames[M68K.ordinal()][DREQ_DEST_ADDR_L] = "DREQ_DEST_ADDR_LOW";
        s32xRegNames[M68K.ordinal()][DREQ_LEN] = "DREQ_LEN";
        s32xRegNames[M68K.ordinal()][FIFO_REG] = "FIFO_REG";
        s32xRegNames[M68K.ordinal()][PWM_CTRL] = "PWM_CTRL";
        s32xRegNames[M68K.ordinal()][PWM_FS] = "PWM_FS";
        s32xRegNames[M68K.ordinal()][PWM_LCH_PW] = "PWM_LCH_PW";
        s32xRegNames[M68K.ordinal()][PWM_RCH_PW] = "PWM_RCH_PW";
        s32xRegNames[M68K.ordinal()][PWM_MONO] = "PWM_MONO";
        s32xRegNames[M68K.ordinal()][SEGA_TV] = "SEGA_TV";
        //TODO supposed to be byte/word but bios reads/writes longs from comm0
        s32xRegNames[M68K.ordinal()][COMM0] = "COMM0";
        s32xRegNames[M68K.ordinal()][COMM1] = "COMM1";
        s32xRegNames[M68K.ordinal()][COMM2] = "COMM2";
        s32xRegNames[M68K.ordinal()][COMM3] = "COMM3";
        s32xRegNames[M68K.ordinal()][COMM4] = "COMM4";
        s32xRegNames[M68K.ordinal()][COMM5] = "COMM5";
        s32xRegNames[M68K.ordinal()][COMM6] = "COMM6";
        s32xRegNames[M68K.ordinal()][COMM7] = "COMM7";
        //vdp regs
        s32xRegNames[M68K.ordinal()][VDP_BITMAP_MODE + 0x100] = "VDP_BITMAP_MODE";
        s32xRegNames[M68K.ordinal()][FBCR + 0x100] = "FBCR"; //Frame Buffer Control Register
        s32xRegNames[M68K.ordinal()][AFLR + 0x100] = "AFLR";//Auto Fill Length Register
        s32xRegNames[M68K.ordinal()][SSCR + 0x100] = "SSCR";//Screen Shift Control Register
        s32xRegNames[M68K.ordinal()][AFSAR + 0x100] = "AFSAR";//Auto Fill Start Address Register
        s32xRegNames[M68K.ordinal()][AFDR + 0x100] = "AFDR"; //Auto Fill Data Register
    }

    public static class S32xDictLogContext {
        public Sh2Util.CpuDeviceAccess sh2Access;
        public ByteBuffer regArea;
        public boolean isSys;
        public int fbD, fbW;
        public boolean read;
    }

    public static void checkName(Sh2Util.CpuDeviceAccess sh2Access, int address, Size size) {
        int reg = (address & 0xFFF) & ~1;
        int rns = sh2Access == M68K ? M68K.ordinal() : MASTER.ordinal();
        if (s32xRegNames[rns][reg] == null) {
            System.out.println(sh2Access + " 32X mmreg unknown reg: " +
                    Integer.toHexString(reg) + ", address: " + Integer.toHexString(address));
        }
    }

    public static void logAccess(S32xDictLogContext logCtx, int address, int value, Size size, int reg) {
        reg &= ~1;
        String type = logCtx.read ? "read" : "write";
        int rns = logCtx.sh2Access == M68K ? M68K.ordinal() : MASTER.ordinal();
        String str = (logCtx.sh2Access + " 32X reg " + type + " " +
                size + ", (" + s32xRegNames[rns][logCtx.isSys ? reg : reg + 0x100] + ") " + Integer.toHexString(address) +
                (!logCtx.read ? ": " + Integer.toHexString(value) : ""));
        LOG.info(str);
    }

    public static void detectRegAccess(S32xDictLogContext logCtx, int address, int value, Size size) {
        String sformat = "%s %s %s, %s(%X), %4X %s %s";
        final String evenOdd = (address & 1) == 0 ? "E" : "O";
        String type = logCtx.read ? "R" : "W";
        int reg = (address & 0xFF) & ~1;
        String s = null;
        int currentWord = Sh2Util.readBuffer(logCtx.regArea, reg, Size.WORD);
        value = logCtx.read ? currentWord : value;
        int rns = logCtx.sh2Access == M68K ? M68K.ordinal() : MASTER.ordinal();
        if (!logCtx.isSys) {
            final int regNamePos = reg + 0x100;
            switch (reg) {
                case VDP_BITMAP_MODE:
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            MarsVdp.BITMAP_MODE.vals[value & 3].name(), value & 3, value, size.name(), evenOdd);
                    break;
                case FBCR:
                    String s1 = "D" + logCtx.fbD + "W" + logCtx.fbW +
                            "|H" + ((value >> 14) & 1) + "V" + ((value >> 15) & 1);
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            s1, value & 3, value, size.name(), evenOdd);
                    break;
                default:
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][regNamePos],
                            "", value, value, size.name(), evenOdd);
                    break;
            }
        } else {
            switch (reg) {
                case INT_MASK:
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][reg],
                            "[RESET: " + ((value & 3) >> 1) + ", ADEN: " + (value & 1) + "]", value & 3,
                            value, size.name(), evenOdd);
                    break;
                case BANK_SET_REG:
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][reg],
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
                    int valueMem = Sh2Util.readBuffer(logCtx.regArea, reg, Size.LONG);
                    String s1 = decodeComm(valueMem);
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][reg],
                            s1, value, valueMem, size.name(), evenOdd);
                    break;
                default:
                    s = String.format(sformat, logCtx.sh2Access.toString(), type, s32xRegNames[rns][reg], "",
                            value, value, size.name(), evenOdd);
                    break;
            }
        }
        if (s != null) {
            LOG.info(s);
        }
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
