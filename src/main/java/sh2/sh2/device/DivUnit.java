package sh2.sh2.device;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
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

    private static final Logger LOG = LogHelper.getLogger(DivUnit.class.getSimpleName());

    private static final int DIV_OVERFLOW_BIT = 0;
    private static final int DIV_OVERFLOW_INT_EN_BIT = 1;
    private static final String formatDiv = "%s div%d, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
    private static final String formatOvf = "%s div%d overflow, dvd: %16X, dvsr: %08X, quotLong: %16X, quot32: %08x, rem: %08X";
    private static final String formatDivBy0 = "%s div%d overflow (div by 0), dvd: %16X, dvsr: %08X";
    private static final boolean verbose = false;

    private final CpuDeviceAccess cpu;
    private final ByteBuffer regs;
    private final IntControl intControl;

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
                assert size == Size.LONG;
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
            handleOverflow(0, true, String.format(formatDivBy0, cpu, 64, dvd, dvsr));
            return;
        }
        long quotL = dvd / dvsr;
        int quot = (int) quotL;
        int rem = (int) (dvd - quot * dvsr);
        writeBuffersLong(regs, DIV_DVDNTH, DIV_DVDNTUH, rem);
        if (verbose) LOG.info(String.format(formatDiv, cpu, 64, dvd, dvsr, quotL, quot, rem));
        if ((long) quot != quotL) {
            handleOverflow(quotL, false, String.format(formatOvf, cpu, 64, dvd, dvsr, quotL, quot, rem));
            return;
        }
        writeBuffersLong(regs, DIV_DVDNT, DIV_DVDNTL, DIV_DVDNTUL, quot);
    }

    //32/32 -> 32
    private void div32Dsp(int value, Size size) {
        long d = value;
        assert size == Size.LONG;
        writeBuffer(regs, DIV_DVDNTH.addr, (int) (d >> 32), size); //sign extend MSB into DVDNTH
        writeBuffer(regs, DIV_DVDNTL.addr, value, size);
        int dvd = readBufferLong(regs, DIV_DVDNT);
        int dvsr = readBufferLong(regs, DIV_DVSR);
        if (dvsr == 0) {
            handleOverflow(0, true, String.format(formatDivBy0, cpu, 32, dvd, dvsr));
            return;
        }
        int quot = dvd / dvsr;
        int rem = (int) (dvd - quot * dvsr);
        if (verbose) LOG.info(String.format(formatDiv, cpu, 32, dvd, dvsr, quot, quot, rem));
        writeBuffersLong(regs, DIV_DVDNTH, DIV_DVDNTUH, rem);
        writeBuffersLong(regs, DIV_DVDNT, DIV_DVDNTL, DIV_DVDNTUL, quot);
    }

    private void handleOverflow(long quot, boolean divBy0, String msg) {
        if (verbose) LOG.info(msg);
        setBit(regs, DIV_DVCR.addr, DIV_OVERFLOW_BIT, 1, Size.LONG);
        int dvcr = readBuffer(regs, DIV_DVCR.addr, Size.WORD);
        int val = quot >= 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        writeBuffersLong(regs, DIV_DVDNTL, DIV_DVDNTUL, val);
        Md32xRuntimeData.addCpuDelayExt(6);
        if ((dvcr & DIV_OVERFLOW_INT_EN_BIT) > 0) {
            intControl.setOnChipDeviceIntPending(DIV);
            LOG.info(msg);
            LOG.warn("{} DivUnit interrupt", cpu); //not used by any sw?
        }
    }

    @Override
    public void reset() {
        writeBufferLong(regs, DIV_DVCR, 0);
    }
}
