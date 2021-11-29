package sh2;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.Sh2Util.Sh2Access;

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

    private ByteBuffer regs = ByteBuffer.allocateDirect(0x100);
    private ByteBuffer data_array = ByteBuffer.allocateDirect(DATA_ARRAY_SIZE); // cache (can be used as RAM)

    private Sh2Access sh2Access;
    private static final boolean verbose = false;

    public Sh2MMREG(Sh2Access sh2Access) {
        this.sh2Access = sh2Access;
        reset();
    }

    public void writeCache(int address, int value, Size size) {
        Sh2Util.writeBuffer(data_array, address & 0xFFF, value, size);
    }

    public int readCache(int address, Size size) {
        return Sh2Util.readBuffer(data_array, address & 0xFFF, size);
    }

    public void write(int reg, int value, Size size) {
        logAccess("write", reg, value, size);
        checkName(reg);
        Sh2Util.writeBuffer(regs, reg & 0xFF, value, size);
        if (reg == DVDNTL) {
            divDsp();
        }
    }

    public int read(int reg, Size size) {
        int res = Sh2Util.readBuffer(regs, reg & 0xFF, size);
        logAccess("read", reg, res, size);
        checkName(reg);
        return res;
    }

    private void checkName(int reg) {
        if (sh2RegNames[reg] == null) {
            System.out.println(sh2Access + " SH2 mmreg unknown reg: " + Integer.toHexString(reg));
        }
    }

    private void logAccess(String type, int reg, int value, Size size) {
        if (verbose) {
            System.out.println(sh2Access + " SH2 reg " + type + " " +
                    size + ", (" + sh2RegNames[reg] + ") " + Integer.toHexString(reg) + ": " +
                    Integer.toHexString(value));
        }
    }

    //64/32 -> 32 only
    //TODO 32/32 -> 32
    private void divDsp() {
        long dh = regs.getInt(DVDNTH & 0xFF);
        long dl = regs.getInt(DVDNTL & 0xFF);
        long dvd = ((dh << 32) & 0xffffffff_ffffffffL) | (dl & 0xffffffffL);
        int dvsr = regs.getInt(DVSR & 0xFF);
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
    }

    public void reset() {
        //from picodrive
        IntStream.range(0, regs.capacity()).forEach(i -> regs.put(i, (byte) 0));
        Sh2Util.writeBuffer(regs, BRR & 0xFF, 0xFF, Size.BYTE);
        Sh2Util.writeBuffer(regs, TDR & 0xFF, 0xFF, Size.BYTE);
        Sh2Util.writeBuffer(regs, SSR & 0xFF, 0x84, Size.BYTE);
        Sh2Util.writeBuffer(regs, TIER & 0xFF, 0x11, Size.BYTE);
        Sh2Util.writeBuffer(regs, TOCR & 0xFF, 0x17, Size.BYTE);
    }
}
