package sh2;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static sh2.dict.Sh2Dict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2MMREG {

    private static final Logger LOG = LogManager.getLogger(Sh2MMREG.class.getSimpleName());

    public static final int DATA_ARRAY_SIZE = 0x1000;
    public static final int DATA_ARRAY_MASK = DATA_ARRAY_SIZE - 1;
    public static final int SH2_REG_SIZE = 0x1000;
    public static final int SH2_REG_MASK = SH2_REG_SIZE - 1;

    private ByteBuffer regs = ByteBuffer.allocateDirect(SH2_REG_SIZE);
    private ByteBuffer data_array = ByteBuffer.allocateDirect(DATA_ARRAY_SIZE); // cache (can be used as RAM)

    private CpuDeviceAccess sh2Access;
    private static final boolean verbose = false;

    public Sh2MMREG(CpuDeviceAccess sh2Access) {
        this.sh2Access = sh2Access;
        reset();
    }

    public void writeCache(int address, int value, Size size) {
        S32xUtil.writeBuffer(data_array, address & DATA_ARRAY_MASK, value, size);
    }

    public int readCache(int address, Size size) {
        return S32xUtil.readBuffer(data_array, address & DATA_ARRAY_MASK, size);
    }

    public void write(int reg, int value, Size size) {
        if (verbose) {
            logAccess("write", reg, value, size);
        }
        checkName(reg);
        S32xUtil.writeBuffer(regs, reg & SH2_REG_MASK, value, size);
        if (reg == DVDNTL) {
            div64Dsp();
        } else if (reg == DVDNT) {
            div32Dsp(value, size);
        }
    }

    public int read(int reg, Size size) {
        int res = S32xUtil.readBuffer(regs, reg & SH2_REG_MASK, size);
        if (verbose) {
            logAccess("read", reg, res, size);
        }
        checkName(reg);
        return res;
    }

    //64/32 -> 32 only
    //TODO 39 cycles, overflow handling?
    private void div64Dsp() {
        long dh = regs.getInt(DVDNTH & 0xFF);
        long dl = regs.getInt(DVDNTL & 0xFF);
        long dvd = ((dh << 32) & 0xffffffff_ffffffffL) | (dl & 0xffffffffL);
        int dvsr = regs.getInt(DVSR & 0xFF);
        if (dvsr == 0) {
            LOG.error("divisor is 0!");
            return;
        }
        long quotL = dvd / dvsr;
        int quot = (int) quotL;
        int rem = (int) (dvd - quot * dvsr);
        if (quot != quotL) {
            String format = "div overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
            System.out.println(String.format(format, dvd, dvsr, quotL, quot, rem));
        }
        regs.putInt(DVDNTH & 0xFF, rem);
        regs.putInt(DVDNTUH & 0xFF, rem);
        regs.putInt(DVDNTL & 0xFF, quot);
        regs.putInt(DVDNTUL & 0xFF, quot);
//        BaseSystem.addCpuDelay(39);
    }

    //32/32 -> 32
    //TODO 39 cycles, overflow handling?
    private void div32Dsp(int value, Size size) {
        long d = value;
        S32xUtil.writeBuffer(regs, DVDNTH & 0xFF, (int) (d >> 32), size); //sign extend MSB into DVDNTH
        S32xUtil.writeBuffer(regs, DVDNTL & 0xFF, value, size);
        int dvd = regs.getInt(DVDNT & 0xFF);
        int dvsr = regs.getInt(DVSR & 0xFF);
        if (dvsr == 0) {
            LOG.error("divisor is 0!");
            return;
        }
        int quot = dvd / dvsr;
        int rem = (int) (dvd - quot * dvsr);
        regs.putInt(DVDNTH & 0xFF, rem);
        regs.putInt(DVDNT & 0xFF, quot);
//        BaseSystem.addCpuDelay(39);
    }

    public void reset() {
        //from picodrive
        IntStream.range(0, regs.capacity()).forEach(i -> regs.put(i, (byte) 0));
        S32xUtil.writeBuffer(regs, BRR & 0xFF, 0xFF, Size.BYTE);
        S32xUtil.writeBuffer(regs, TDR & 0xFF, 0xFF, Size.BYTE);
        S32xUtil.writeBuffer(regs, SSR & 0xFF, 0x84, Size.BYTE);
        S32xUtil.writeBuffer(regs, TIER & 0xFF, 0x11, Size.BYTE);
        S32xUtil.writeBuffer(regs, TOCR & 0xFF, 0x17, Size.BYTE);
    }
}
