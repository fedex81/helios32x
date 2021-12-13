package sh2.sh2.device;

import omegadrive.Device;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.*;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.Sh2MMREG.SH2_REG_MASK;
import static sh2.dict.Sh2Dict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class DivUnit implements Device {

    private static final Logger LOG = LogManager.getLogger(DivUnit.class.getSimpleName());

    private static final int DIV_OVERFLOW_BIT = 0;
    private static final String format = "div64 overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";

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

    public void write(int reg, int value, Size size) {
        int regEven = reg & ~1;
        switch (regEven) {
            case DVDNTL:
                div64Dsp();
                break;
            case DVDNT:
                div32Dsp(value, size);
                break;
            case DVCR:
                int val = readBuffer(regs, DVCR & SH2_REG_MASK, Size.WORD);
                if ((val & 1) > 0) {
                    LOG.error("{} Interrupt request on overflow not supported", cpu);
                }
                break;
        }
    }

    public int read(int reg, Size size) {
        return readBuffer(regs, reg, size);
    }

    //64/32 -> 32 only
    //TODO 39 cycles, overflow handling?
    private void div64Dsp() {
        long dh = readBufferLong(DVDNTH);
        long dl = readBufferLong(DVDNTL);
        long dvd = ((dh << 32) & 0xffffffff_ffffffffL) | (dl & 0xffffffffL);
        int dvsr = readBufferLong(DVSR);
        if (dvsr == 0) {
            LOG.error("divisor is 0!");
            return;
        }
        long quotL = dvd / dvsr;
        int quot = (int) quotL;
        int rem = (int) (dvd - quot * dvsr);
        if (quot != (int) (quotL & 0xFFFF_FFFFL)) {
            String format = "div64 overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
            LOG.info(String.format(format, dvd, dvsr, quotL, quot, rem));
            setBit(regs, DVCR, DIV_OVERFLOW_BIT, 1, Size.WORD);
        }
        writeBufferLong(DVDNTH, rem);
        writeBufferLong(DVDNTUH, rem);
        writeBufferLong(DVDNTL, quot);
        writeBufferLong(DVDNTUL, quot);
//        BaseSystem.addCpuDelay(39);
    }

    //32/32 -> 32
    //TODO 39 cycles
    private void div32Dsp(int value, Size size) {
        long d = value;
        writeBuffer(regs, DVDNTH & SH2_REG_MASK, (int) (d >> 32), size); //sign extend MSB into DVDNTH
        writeBuffer(regs, DVDNTL & SH2_REG_MASK, value, size);
        int dvd = readBufferLong(DVDNT);
        int dvsr = readBufferLong(DVSR);
        if (dvsr == 0) {
            LOG.error("divisor is 0!");
            return;
        }
        int quot = dvd / dvsr;
        int rem = (int) (dvd - quot * dvsr);
        writeBufferLong(DVDNTH, rem);
        writeBufferLong(DVDNT, quot);
//        BaseSystem.addCpuDelay(39);
    }
}
