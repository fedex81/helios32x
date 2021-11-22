package sh2;

import omegadrive.util.Size;

import java.nio.ByteBuffer;

import static sh2.Sh2Emu.Sh2Access.M68K;
import static sh2.Sh2Emu.Sh2Access.MASTER;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Util {

    public static final int BASE_SH2_MMREG = 0xffff_0000;

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
        s32xRegNames[M68K.ordinal()][PWM_MONO] = "PWM_MONO";
        s32xRegNames[M68K.ordinal()][VRES_INT_CLEAR] = "VRES_INT_CLEAR";
        s32xRegNames[M68K.ordinal()][VINT_CLEAR] = "VINT_CLEAR";
        s32xRegNames[M68K.ordinal()][HINT_CLEAR] = "HINT_CLEAR";
        s32xRegNames[M68K.ordinal()][CMD_INT_CLEAR] = "CMD_INT_CLEAR";
        s32xRegNames[M68K.ordinal()][PWM_INT_CLEAR] = "PWM_INT_CLEAR";
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

    public static String[] sh2RegNames = new String[0xFFFF];

    public static final int CAS_L1_OFFSET_16 = 0x8426;
    public static final int CAS_L2_OFFSET_16 = 0x8446;
    public static final int CAS_L3_OFFSET_16 = 0x8466;
    public static final int CAS_L1_OFFSET_32 = 0x8848;
    public static final int CAS_L2_OFFSET_32 = 0x8888;
    public static final int CAS_L3_OFFSET_32 = 0x88C8;

    public static final int TIER_OFFSET = 0xFE10;
    public static final int FTCSR_OFFSET = 0xFE11;
    public static final int FRCH_OFFSET = 0xFE12;
    public static final int FRCL_OFFSET = 0xFE13;
    public static final int OCRAB_H_OFFSET = 0xFE14;
    public static final int OCRAB_L_OFFSET = 0xFE15;
    public static final int TCR_OFFSET = 0xFE16;
    public static final int TOCR_OFFSET = 0xFE17;
    public static final int SBYCR_OFFSET = 0xFE91;
    public static final int CCR_OFFSET = 0xFE92;
    public static final int BCR1_OFFSET = 0xFFE0;
    public static final int BCR2_OFFSET = 0xFFE4;
    public static final int WCR_OFFSET = 0xFFE8;
    public static final int MCR_OFFSET = 0xFFEC;
    public static final int DVSR = 0xFF00;
    public static final int DVDNTH = 0xFF10;
    public static final int DVDNTL = 0xFF14;
    /* Quotient long-term register */
    //see https://git.m4xw.net/Switch/RetroArch/yabause/-/blob/5e59d839a3e094184379326f93e34d6d7c49cf1d/yabause/src/sh2core.h
    public static final int DVDNTUH = 0xFF18;
    public static final int DVDNTUL = 0xFF1C;


    public static final int RTCSR_OFFSET = 0xFFF0;
    public static final int RTCNT_OFFSET = 0xFFF4;
    public static final int RTCOR_OFFSET = 0xFFF8;

    static {
        sh2RegNames[CAS_L1_OFFSET_16] = "CASL1_16";
        sh2RegNames[CAS_L2_OFFSET_16] = "CASL2_16";
        sh2RegNames[CAS_L3_OFFSET_16] = "CASL3_16";
        sh2RegNames[SBYCR_OFFSET] = "SBYCR"; //Standby Control Register
        sh2RegNames[CCR_OFFSET] = "CCR"; //Cache control register
        sh2RegNames[BCR1_OFFSET] = "BCR1"; //Bus Control Register 1
        sh2RegNames[BCR2_OFFSET] = "BCR2"; //Bus Control Register 2
        sh2RegNames[WCR_OFFSET] = "WCR"; //Wait Control Register
        sh2RegNames[MCR_OFFSET] = "MCR";
        sh2RegNames[RTCSR_OFFSET] = "RTCSR"; //RTCSR	Refresh Timer Control/Status Register
        sh2RegNames[RTCNT_OFFSET] = "RTCNT"; //RTCNT	Refresh Timer Counter
        sh2RegNames[RTCOR_OFFSET] = "RTCOR"; //RTCOR	Refresh Time Constant Register
        sh2RegNames[TIER_OFFSET] = "TIER"; //Timer interrupt enable register
        sh2RegNames[TOCR_OFFSET] = "TOCR";//Timer output compare control register (TOCR)
        sh2RegNames[OCRAB_H_OFFSET] = "OCRAB_H"; //Output compare register A/B HIGH
        sh2RegNames[OCRAB_L_OFFSET] = "OCRAB_L"; //Output compare register A/B LOW
        sh2RegNames[TCR_OFFSET] = "TCR";//Timer control register (TCR)
        sh2RegNames[FTCSR_OFFSET] = "FTCSR"; //Free-running timer control/status register (FTCSR)
        sh2RegNames[FRCL_OFFSET] = "FRCL";//Free-running counter LOW (FRC)
        sh2RegNames[FRCH_OFFSET] = "FRCH";//Free-running counter HIGH (FRC)
        sh2RegNames[DVSR] = "DVSR";
        sh2RegNames[DVDNTH] = "DVDNTH";
        sh2RegNames[DVDNTL] = "DVDNTL";
        sh2RegNames[DVDNTUH] = "DVDNTUH";
        sh2RegNames[DVDNTUL] = "DVDNTUL";
    }

    public static void writeBuffer(ByteBuffer b, int pos, int value, Size size) {
        switch (size) {
            case BYTE:
                b.put(pos, (byte) value);
                break;
            case WORD:
                b.putShort(pos, (short) value);
                break;
            case LONG:
                b.putInt(pos, value);
                break;
            default:
                System.err.println("Unsupported size: " + size);
                break;
        }
    }

    public static int readBuffer(ByteBuffer b, int pos, Size size) {
        switch (size) {
            case BYTE:
                return b.get(pos) & 0xFF;
            case WORD:
                return b.getShort(pos) & 0xFFFF;
            case LONG:
                return b.getInt(pos);
            default:
                System.err.println("Unsupported size: " + size);
                return 0xFF;
        }
    }
}