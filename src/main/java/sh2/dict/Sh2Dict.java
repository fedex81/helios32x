package sh2.dict;

import com.google.common.collect.ImmutableMap;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.Md32x;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType;

import java.util.Map;

import static sh2.S32xUtil.th;
import static sh2.Sh2MMREG.SH2_REG_MASK;
import static sh2.Sh2MMREG.SH2_REG_SIZE;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Dict {

    private static final Logger LOG = LogManager.getLogger(Sh2Dict.class.getSimpleName());

    public static Sh2DeviceType[] sh2RegDeviceMapping = new Sh2DeviceType[SH2_REG_SIZE];
    public static RegSpec[] sh2RegMapping = new RegSpec[SH2_REG_SIZE];

    public enum RegSpec {
        //serial comm interface
        SCI_SMR(0xFE00, "SCI_SMR", Size.BYTE), //Serial mode register
        SCI_BRR(0xFE01, "SCI_BRR", Size.BYTE),  //Bit rate register
        SCI_SCR(0xFE02, "SCI_SCR", Size.BYTE), //Serial control register
        SCI_TDR(0xFE03, "SCI_TDR", Size.BYTE), //Transmit data register
        SCI_SSR(0xFE04, "SCI_SSR", Size.BYTE), //Serial status register
        SCI_RDR(0xFE05, "SCI_RDR", Size.BYTE), //Receive data register

        //free running timer
        FRT_TIER(0xFE10, "FRT_TIER", Size.BYTE), //Timer interrupt enable register
        FRT_FTCSR(0xFE11, "FRT_FTCSR", Size.BYTE), //Timer output compare control register (TOCR)
        FRT_FRCH(0xFE12, "FRT_FRCH", Size.BYTE), //Free-running counter HIGH (FRC)
        FRT_FRCL(0xFE13, "FRT_FRCL", Size.BYTE), //Free-running counter LOW (FRC)
        FRT_OCRAB_H(0xFE14, "FRT_OCRAB_H", Size.BYTE), //Output compare register A/B HIGH
        FRT_OCRAB_L(0xFE15, "FRT_OCRAB_L", Size.BYTE), //Output compare register A/B LOW
        FRT_TCR(0xFE16, "FRT_TCR", Size.BYTE), //Timer control register (TCR)
        FRT_TOCR(0xFE17, "FRT_TOCR", Size.BYTE), //Timer output compare control register (TOCR)
        FRT_ICR_H(0xFE18, "FRT_ICR_H", Size.BYTE), //Input capture register H
        FRT_ICR_L(0xFE19, "FRT_ICR_L", Size.BYTE), //Input capture register L

        //watchdog timer
        WDT_WTCSR(0xFE80, "WDT_WTCSR", Size.WORD), //Watchdog timer control/status register or Watchdog timer counter

        //interrupt controller
        INTC_IPRA(0xFEE2, "INTC_IPRA", Size.WORD), //Interrupt priority level setting register A
        INTC_IPRB(0xFE60, "INTC_IPRB", Size.WORD), //Interrupt priority level setting register B
        INTC_VCRA(0xFE62, "INTC_VCRA", Size.WORD), //Vector number setting register A
        INTC_VCRB(0xFE64, "INTC_VCRB", Size.WORD), //Vector number setting register B
        INTC_VCRC(0xFE66, "INTC_VCRC", Size.WORD), //Vector number setting register C
        INTC_VCRD(0xFE68, "INTC_VCRD", Size.WORD), //Vector number setting register D
        INTC_VCRWDT(0xFEE4, "INTC_VCRWDT", Size.WORD), //Vector number setting register WDT
        INTC_VCRDIV(0xFF0C, "INTC_VCRDIV", Size.LONG), //Vector number setting register DIV
        INTC_VCRDMA0(0xFFA0, "INTC_VCRDMA0", Size.LONG), //Vector number setting register DMAC0
        INTC_VCRDMA1(0xFFA8, "INTC_VCRDMA1", Size.LONG), //Vector number setting register DMAC1
        INTC_ICR(0xFEE0, "INTC_ICR", Size.WORD), //Interrupt control register

        //bus state controller
        BSC_BCR1(0xFFE0, "BSC_BCR1", Size.LONG), //Bus Control Register 1
        BSC_BCR2(0xFFE4, "BSC_BCR2", Size.LONG),//Bus Control Register 2
        BSC_WCR(0xFFE8, "BSC_WCR", Size.LONG), //Wait Control Register
        BSC_MCR(0xFFEC, "BSC_MCR", Size.LONG), //Individual memory control register
        BSC_RTCSR(0xFFF0, "BSC_RTCSR", Size.LONG), //Refresh Timer Control/Status Register
        BSC_RTCNT(0xFFF4, "BSC_RTCNT", Size.LONG), //Refresh Timer Counter
        BSC_RTCOR(0xFFF8, "BSC_RTCOR", Size.LONG), //Refresh Time Constant Register

        //div unit
        DIV_DVSR(0xFF00, "DIV_DVSR", Size.LONG),
        DIV_DVDNT(0xFF04, "DIV_DVDNT", Size.LONG),
        DIV_DVCR(0xFF08, "DIV_DVCR", Size.LONG),
        DIV_DVDNTH(0xFF10, "DIV_DVDNTH", Size.LONG),
        DIV_DVDNTL(0xFF14, "DIV_DVDNTL", Size.LONG),
        /* Quotient long-term register */
        //see https://git.m4xw.net/Switch/RetroArch/yabause/-/blob/5e59d839a3e094184379326f93e34d6d7c49cf1d/yabause/src/sh2core.h
        DIV_DVDNTUH(0xFF18, "DIV_DVDNTUH", Size.LONG),
        DIV_DVDNTUL(0xFF1C, "DIV_DVDNTUL", Size.LONG),

        //user-break controller
        UBC_BARAH(0xFF40, "UBC_BARAH", Size.WORD),
        UBC_BARAL(0xFF42, "UBC_BARAL", Size.WORD),
        UBC_BBRA(0xFF48, "UBC_BBRA", Size.WORD),
        UBC_BARBH(0xFF60, "UBC_BARBH", Size.WORD),
        UBC_BBRB(0xFF68, "UBC_BBRB", Size.WORD),

        //dma controller
        DMA_DRCR0(0xFE71, "DMA_DRCR0", Size.BYTE),
        DMA_DRCR1(0xFE72, "DMA_DRCR1", Size.BYTE),
        DMA_SAR0(0xFF80, "DMA_SAR0", Size.LONG),
        DMA_SAR1(0xFF90, "DMA_SAR1", Size.LONG),
        DMA_DAR0(0xFF84, "DMA_DAR0", Size.LONG),
        DMA_DAR1(0xFF94, "DMA_DAR1", Size.LONG),
        DMA_TCR0(0xFF88, "DMA_TCR0", Size.LONG),
        DMA_TCR1(0xFF98, "DMA_TCR1", Size.LONG),
        DMA_CHCR0(0xFF8C, "DMA_CHCR0", Size.LONG),
        DMA_CHCR1(0xFF9C, "DMA_CHCR1", Size.LONG),
        DMA_DMAOR(0xFFB0, "DMA_DMAOR", Size.LONG),
        DMA_VRCDMA0(0xFFA0, "DMA_VRCDMA0", Size.LONG),
        DMA_VRCDMA1(0xFFA8, "DMA_VRCDMA1", Size.LONG),

        //misc registers
        NONE_SBYCR(0xFE91, "NONE_SBYCR", Size.BYTE), //PowerDownModes:Standby Control Register
        NONE_CCR(0xFE92, "NONE_CCR", Size.BYTE), //Cache:Cache control register
        ;

        public final int fullAddress, addr;
        public final String name;
        public final Size size;

        private RegSpec(int addr, String name, Size size) {
            this.fullAddress = addr;
            this.addr = addr & SH2_REG_MASK;
            this.name = name;
            this.size = size;
            init();
        }

        private void init() {
            int addrLen = Math.max(1, size.ordinal() << 1);
            String device = name.split("_")[0];
            for (int i = addr; i < addr + addrLen; i++) {
                sh2RegMapping[i] = this;
                sh2RegDeviceMapping[i] = Sh2DeviceType.valueOf(device);
            }
        }
    }

    public static final int CAS_L1_OFFSET_16 = 0x8426;
    public static final int CAS_L2_OFFSET_16 = 0x8446;
    public static final int CAS_L3_OFFSET_16 = 0x8466;
    public static final int CAS_L1_OFFSET_32 = 0x8848;
    public static final int CAS_L2_OFFSET_32 = 0x8888;
    public static final int CAS_L3_OFFSET_32 = 0x88C8;

    public static final Map<Integer, Integer> dramModeRegsSpec = ImmutableMap.of(
            CAS_L1_OFFSET_16, 0,
            CAS_L2_OFFSET_16, 0,
            CAS_L3_OFFSET_16, 0,
            CAS_L1_OFFSET_32, 0,
            CAS_L2_OFFSET_32, 0,
            CAS_L3_OFFSET_32, 0
    );


    public static void checkName(int reg) {
        if (sh2RegMapping[reg & SH2_REG_MASK] == null) {
            LOG.warn("{} SH2 mmreg unknown reg: {}", Md32x.getAccessType(), th(reg));
        }
    }

    public static void logAccess(String type, int reg, int value, Size size) {
        String s = Md32x.getAccessType() + " SH2 reg " + type + " " +
                size + ", (" + sh2RegMapping[reg & SH2_REG_MASK] + ") " + th(reg) + ": " + th(value);
        LOG.info(s);
    }
}
