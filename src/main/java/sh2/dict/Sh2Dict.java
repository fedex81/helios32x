package sh2.dict;

import omegadrive.system.BaseSystem;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Dict {

    private static final Logger LOG = LogManager.getLogger(Sh2Dict.class.getSimpleName());

    private static int REG_NAME_SIZE = 0x10000;
    public static String[] sh2RegNames = new String[REG_NAME_SIZE];

    public static final int CAS_L1_OFFSET_16 = 0x8426;
    public static final int CAS_L2_OFFSET_16 = 0x8446;
    public static final int CAS_L3_OFFSET_16 = 0x8466;
    public static final int CAS_L1_OFFSET_32 = 0x8848;
    public static final int CAS_L2_OFFSET_32 = 0x8888;
    public static final int CAS_L3_OFFSET_32 = 0x88C8;

    //serial comm intf
    public static final int SCI_SMR = 0xFE00;
    public static final int SCI_BRR = 0xFE01;
    public static final int SCI_SCR = 0xFE02;
    public static final int SCI_TDR = 0xFE03;
    public static final int SCI_SSR = 0xFE04;
    public static final int SCI_RDR = 0xFE05;

    public static final int TIER = 0xFE10;
    public static final int FTCSR = 0xFE11;
    public static final int FRCH = 0xFE12;
    public static final int FRCL = 0xFE13;
    public static final int OCRAB_H = 0xFE14;
    public static final int OCRAB_L = 0xFE15;
    public static final int TCR = 0xFE16;
    public static final int TOCR = 0xFE17;

    public static final int ICR = 0xFEE0;
    public static final int IPRA = 0xFEE2;
    public static final int VCRWDT = 0xFEE4;
    public static final int IPRB = 0xFE60;
    public static final int VCRA = 0xFE62;
    public static final int VCRB = 0xFE64;


    public static final int SBYCR = 0xFE91;
    public static final int CCR = 0xFE92;
    public static final int BCR1 = 0xFFE0;
    public static final int BCR2 = 0xFFE4;
    public static final int WCR = 0xFFE8;
    public static final int MCR = 0xFFEC;

    //div unit
    public static final int DVSR = 0xFF00;
    public static final int DVDNT = 0xFF04;
    public static final int DVCR = 0xFF08;
    public static final int DVDNTH = 0xFF10;
    public static final int DVDNTL = 0xFF14;
    /* Quotient long-term register */
    //see https://git.m4xw.net/Switch/RetroArch/yabause/-/blob/5e59d839a3e094184379326f93e34d6d7c49cf1d/yabause/src/sh2core.h
    public static final int DVDNTUH = 0xFF18;
    public static final int DVDNTUL = 0xFF1C;

    public static final int BARAH = 0xFF40;
    public static final int BARAL = 0xFF42;
    public static final int BBRA = 0xFF48;

    public static final int BARBH = 0xFF60;
    public static final int BBRB = 0xFF68;

    //dma controller
    public static final int DMA_DRCR0 = 0xFE71;
    public static final int DMA_DRCR1 = 0xFE72;
    public static final int DMA_SAR0 = 0xFF80;
    public static final int DMA_SAR1 = 0xFF90;
    public static final int DMA_DAR0 = 0xFF84;
    public static final int DMA_DAR1 = 0xFF94;
    public static final int DMA_TCR0 = 0xFF88;
    public static final int DMA_TCR1 = 0xFF98;
    public static final int DMA_CHCR0 = 0xFF8C;
    public static final int DMA_CHCR1 = 0xFF9C;
    public static final int DMAOR = 0xFFB0;
    public static final int VRCDMA0 = 0xFFA0;
    public static final int VRCDMA1 = 0xFFA8;

    public static final int RTCSR = 0xFFF0;
    public static final int RTCNT = 0xFFF4;
    public static final int RTCOR = 0xFFF8;

    static {
        sh2RegNames[CAS_L1_OFFSET_16] = "CASL1_16";
        sh2RegNames[CAS_L2_OFFSET_16] = "CASL2_16";
        sh2RegNames[CAS_L3_OFFSET_16] = "CASL3_16";
        sh2RegNames[SBYCR] = "SBYCR"; //Standby Control Register
        sh2RegNames[CCR] = "CCR"; //Cache control register
        sh2RegNames[BCR1] = "BCR1"; //Bus Control Register 1
        sh2RegNames[BCR2] = "BCR2"; //Bus Control Register 2
        sh2RegNames[WCR] = "WCR"; //Wait Control Register
        sh2RegNames[MCR] = "MCR";
        sh2RegNames[RTCSR] = "RTCSR"; //RTCSR	Refresh Timer Control/Status Register
        sh2RegNames[RTCNT] = "RTCNT"; //RTCNT	Refresh Timer Counter
        sh2RegNames[RTCOR] = "RTCOR"; //RTCOR	Refresh Time Constant Register
        sh2RegNames[TIER] = "TIER"; //Timer interrupt enable register
        sh2RegNames[TOCR] = "TOCR";//Timer output compare control register (TOCR)
        sh2RegNames[OCRAB_H] = "OCRAB_H"; //Output compare register A/B HIGH
        sh2RegNames[OCRAB_L] = "OCRAB_L"; //Output compare register A/B LOW
        sh2RegNames[TCR] = "TCR";//Timer control register (TCR)
        sh2RegNames[FTCSR] = "FTCSR"; //Free-running timer control/status register (FTCSR)
        sh2RegNames[FRCL] = "FRCL";//Free-running counter LOW (FRC)
        sh2RegNames[FRCH] = "FRCH";//Free-running counter HIGH (FRC)
        sh2RegNames[SCI_SMR] = "SCI_SMR";//Serial mode register
        sh2RegNames[SCI_SCR] = "SCI_SCR";//Serial control register
        sh2RegNames[SCI_TDR] = "SCI_TDR";//Transmit data register
        sh2RegNames[SCI_SSR] = "SCI_SSR";//Serial status register
        sh2RegNames[SCI_RDR] = "SCI_RDR";//Receive data register
        sh2RegNames[SCI_BRR] = "SCI_BRR";//Bit rate register

        sh2RegNames[IPRA] = "IPRA";//Interrupt priority level setting register A
        sh2RegNames[IPRB] = "IPRB";//Interrupt priority level setting register B
        sh2RegNames[VCRA] = "VCRA";//Vector number setting register A
        sh2RegNames[VCRB] = "VCRB";//Vector number setting register B

        sh2RegNames[DVSR] = "DVSR";
        sh2RegNames[DVCR] = "DVCR"; //Division control register
        sh2RegNames[DVDNT] = "DVDNT"; //Dividend register L for 32-bit division
        sh2RegNames[DVDNTH] = "DVDNTH";
        sh2RegNames[DVDNTL] = "DVDNTL";
        sh2RegNames[DVDNTUH] = "DVDNTUH";
        sh2RegNames[DVDNTUL] = "DVDNTUL";

        sh2RegNames[VRCDMA0] = "VRCDMA0";
        sh2RegNames[VRCDMA1] = "VRCDMA1";
        sh2RegNames[DMA_DRCR0] = "DMA_DRCR0";
        sh2RegNames[DMA_DRCR1] = "DMA_DRCR1";
        sh2RegNames[DMA_CHCR0] = "DMA_CHCR0";
        sh2RegNames[DMA_CHCR1] = "DMA_CHCR1";
        sh2RegNames[DMA_DAR0] = "DMA_DAR0";
        sh2RegNames[DMA_DAR1] = "DMA_DAR1";
        sh2RegNames[DMA_SAR0] = "DMA_SAR0";
        sh2RegNames[DMA_SAR1] = "DMA_SAR1";
        sh2RegNames[DMA_TCR0] = "DMA_TCR0";
        sh2RegNames[DMA_TCR1] = "DMA_TCR1";
        sh2RegNames[DMAOR] = "DMAOR";

        sh2RegNames[VCRWDT] = "VCRWDT"; //Vector number setting register WDT
        sh2RegNames[BARAH] = "BARAH";   //Break address register AH
        sh2RegNames[BARAL] = "BARAL";   //Break address register AL
        sh2RegNames[BARBH] = "BARBH";   //Break address register BH
        sh2RegNames[BBRA] = "BBRA";   //Break bus cycle register A
        sh2RegNames[BBRB] = "BBRB";   //Break bus cycle register B
    }

    public static void checkName(int reg) {
        if (sh2RegNames[reg] == null) {
            LOG.info("{} SH2 mmreg unknown reg: {}", BaseSystem.getAccessType(), Integer.toHexString(reg));
        }
    }

    public static void logAccess(String type, int reg, int value, Size size) {
        String s = BaseSystem.getAccessType() + " SH2 reg " + type + " " +
                size + ", (" + sh2RegNames[reg] + ") " + Integer.toHexString(reg) + ": " +
                Integer.toHexString(value);
        LOG.info(s);
    }
}
