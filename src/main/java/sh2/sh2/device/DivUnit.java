package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.device.Sh2DeviceHelper.Sh2Device;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.Sh2MMREG.SH2_REG_MASK;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class DivUnit implements Sh2Device {

    private static final Logger LOG = LogManager.getLogger(DivUnit.class.getSimpleName());

    private static final int DIV_OVERFLOW_BIT = 0;
    private static final int DIV_OVERFLOW_INT_EN_BIT = 1;
    private static final int MAX_POS = 0x8000_0000;
    private static final int MAX_NEG = 0x7FFF_FFFF;
    private static final String format64 = "div%d overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
    private static final String formatDivBy0 = "div%d overflow (div by 0), dvd: %16X, dvsr: %08X";
    private static final boolean verbose = false;

    private CpuDeviceAccess cpu;
    private ByteBuffer regs;

    public DivUnit(CpuDeviceAccess cpu, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
    }

    private int readBufferLong(int reg) {
        return readBuffer(regs, reg & SH2_REG_MASK, Size.LONG);
    }

    private void writeBufferLong(int reg, int value) {
        writeBuffer(regs, reg & SH2_REG_MASK, value, Size.LONG);
    }

    public void write(RegSpec reg, int value, Size size) {
        switch (reg) {
            case DIV_DVDNTL:
                div64Dsp();
                break;
            case DIV_DVDNT:
                div32Dsp(value, size);
                break;
            case DIV_DVCR:
                int val = readBuffer(regs, reg.addr, Size.WORD);
                if ((val & (1 << DIV_OVERFLOW_INT_EN_BIT)) > 0) {
                    LOG.error("{} Interrupt request on overflow not supported", cpu);
                }
                if (verbose) LOG.info("{} {} value: {} {}", cpu, reg.name, th(val), size);
                break;
        }
    }

    public int read(int reg, Size size) {
        return readBuffer(regs, reg, size);
    }

    //64/32 -> 32 only
    //TODO 39 cycles, overflow handling?
    private void div64Dsp() {
        long dh = readBufferLong(DIV_DVDNTH.addr);
        long dl = readBufferLong(DIV_DVDNTL.addr);
        long dvd = ((dh << 32) & 0xffffffff_ffffffffL) | (dl & 0xffffffffL);
        int dvsr = readBufferLong(DIV_DVSR.addr);
        if (dvsr == 0) {
            handleOverflow(0, true, String.format(formatDivBy0, 64, dvd, dvsr));
            return;
        }
        long quotL = dvd / dvsr;
        int quot = (int) quotL;
        int rem = (int) (dvd - quot * dvsr);
        writeBufferLong(DIV_DVDNTH.addr, rem);
        writeBufferLong(DIV_DVDNTUH.addr, rem);
        if (quot != (int) (quotL & 0xFFFF_FFFFL)) {
            handleOverflow(quot, false, String.format(format64, 64, dvd, dvsr, quotL, quot, rem));
            return;
        }
        writeBufferLong(DIV_DVDNTL.addr, quot);
        writeBufferLong(DIV_DVDNTUL.addr, quot);
//        BaseSystem.addCpuDelay(39);
    }

    //32/32 -> 32
    //TODO 39 cycles
    private void div32Dsp(int value, Size size) {
        long d = value;
        writeBuffer(regs, DIV_DVDNTH.addr, (int) (d >> 32), size); //sign extend MSB into DVDNTH
        writeBuffer(regs, DIV_DVDNTL.addr, value, size);
        int dvd = readBufferLong(DIV_DVDNT.addr);
        int dvsr = readBufferLong(DIV_DVSR.addr);
        if (dvsr == 0) {
            handleOverflow(0, true, String.format(formatDivBy0, 32, dvd, dvsr));
            return;
        }
        int quot = dvd / dvsr;
        int rem = (int) (dvd - quot * dvsr);
        writeBufferLong(DIV_DVDNTH.addr, rem);
        writeBufferLong(DIV_DVDNT.addr, quot);
//        BaseSystem.addCpuDelay(39);
    }

    private void handleOverflow(int quot, boolean divBy0, String msg) {
        if (verbose) LOG.info(msg);
        setBit(regs, DIV_DVCR.addr, DIV_OVERFLOW_BIT, 1, Size.LONG);
        //TODO what happens to DVDNTL when divBy0 ?
        int val = quot > 0 ? MAX_NEG : MAX_POS;
        writeBufferLong(DIV_DVDNTL.addr, val);
        writeBufferLong(DIV_DVDNTUL.addr, val);
    }

    @Override
    public void reset() {
        writeBufferLong(DIV_DVCR.addr, 0);
    }
}
