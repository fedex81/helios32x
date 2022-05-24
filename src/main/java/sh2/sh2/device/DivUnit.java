package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.Md32xRuntimeData;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.DIV;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class DivUnit implements Sh2Device {

    private static final Logger LOG = LogManager.getLogger(DivUnit.class.getSimpleName());

    private static final int DIV_OVERFLOW_BIT = 0;
    private static final int DIV_OVERFLOW_INT_EN_BIT = 1;
    private static final String formatDiv = "div%d, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
    private static final String formatOvf = "div%d overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
    private static final String formatDivBy0 = "div%d overflow (div by 0), dvd: %16X, dvsr: %08X";
    private static final boolean verbose = false;

    private CpuDeviceAccess cpu;
    private ByteBuffer regs;
    private IntControl intControl;

    public DivUnit(CpuDeviceAccess cpu, IntControl intControl, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.intControl = intControl;
    }

    @Override
    public void write(RegSpec reg, int pos, int value, Size size) {
        writeBuffer(regs, pos, value, size);
        if (verbose) LOG.info("{} Write {} value: {} {}", cpu, reg.name, th(value), size);
        switch (reg) {
            case DIV_DVDNTL:
                div64Dsp();
                break;
            case DIV_DVDNT:
                div32Dsp(value, size);
                break;
        }
    }

    @Override
    public int read(RegSpec regSpec, int reg, Size size) {
        if (verbose) LOG.info("{} Read {} value: {} {}", cpu, regSpec.name, th(readBuffer(regs, reg, size)), size);
        return readBuffer(regs, reg, size);
    }

    //64/32 -> 32 only
    private void div64Dsp() {
        long dh = readBufferLong(regs, DIV_DVDNTH);
        long dl = readBufferLong(regs, DIV_DVDNTL);
        long dvd = ((dh << 32) & 0xffffffff_ffffffffL) | (dl & 0xffffffffL);
        int dvsr = readBufferLong(regs, DIV_DVSR);
        if (dvsr == 0) {
            handleOverflow(0, true, String.format(formatDivBy0, 64, dvd, dvsr));
            return;
        }
        long quotL = dvd / dvsr;
        int quot = (int) quotL;
        int rem = (int) (dvd - quot * dvsr);
        writeBuffersLong(regs, DIV_DVDNTH, DIV_DVDNTUH, rem);
        if (verbose) LOG.info(String.format(formatDiv, 64, dvd, dvsr, quotL, quot, rem));
        if ((long) quot != quotL) {
            handleOverflow(quotL, false, String.format(formatOvf, 64, dvd, dvsr, quotL, quot, rem));
            return;
        }
        writeBuffersLong(regs, DIV_DVDNT, DIV_DVDNTL, DIV_DVDNTUL, quot);
    }

    //32/32 -> 32
    private void div32Dsp(int value, Size size) {
        long d = value;
        writeBuffer(regs, DIV_DVDNTH.addr, (int) (d >> 32), size); //sign extend MSB into DVDNTH
        writeBuffer(regs, DIV_DVDNTL.addr, value, size);
        int dvd = readBufferLong(regs, DIV_DVDNT);
        int dvsr = readBufferLong(regs, DIV_DVSR);
        if (dvsr == 0) {
            handleOverflow(0, true, String.format(formatDivBy0, 32, dvd, dvsr));
            return;
        }
        int quot = dvd / dvsr;
        int rem = (int) (dvd - quot * dvsr);
        if (verbose) LOG.info(String.format(formatDiv, 32, dvd, dvsr, quot, quot, rem));
        writeBuffersLong(regs, DIV_DVDNTH, DIV_DVDNTUH, rem);
        writeBuffersLong(regs, DIV_DVDNT, DIV_DVDNTL, quot);
        writeBufferLong(regs, DIV_DVDNTUL, quot);
    }

    private void handleOverflow(long quot, boolean divBy0, String msg) {
//        if (verbose)
        LOG.warn(msg); //check overflow handling
        setBit(regs, DIV_DVCR.addr, DIV_OVERFLOW_BIT, 1, Size.LONG);
        int dvcr = readBuffer(regs, DIV_DVCR.addr, Size.WORD);
        int val = quot >= 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        writeBuffersLong(regs, DIV_DVDNTL, DIV_DVDNTUL, val);
        Md32xRuntimeData.addCpuDelayExt(6);
        if ((dvcr & DIV_OVERFLOW_INT_EN_BIT) > 0) {
            intControl.setOnChipDeviceIntPending(DIV);
            LOG.info(msg);
            LOG.warn("DivUnit interrupt"); //not used by any sw?
        }
    }

    @Override
    public void reset() {
        writeBufferLong(regs, DIV_DVCR, 0);
    }
}
