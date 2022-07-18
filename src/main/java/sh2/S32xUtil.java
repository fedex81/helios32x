package sh2;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.dict.S32xDict;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.S32xRegType.VDP;
import static sh2.dict.Sh2Dict.RegSpec;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xUtil {

    private static final Logger LOG = LogHelper.getLogger(S32xUtil.class.getSimpleName());

    public static final int[] EMPTY_INT_ARRAY = {};

    public static interface StepDevice extends Device {
        public default void step(int cycles) {
        } //DO NOTHING
    }

    public static interface Sh2Device extends StepDevice {
        void write(RegSpec regSpec, int pos, int value, Size size);

        int read(RegSpec regSpec, int reg, Size size);

        public default void write(RegSpec regSpec, int value, Size size) {
            write(regSpec, regSpec.addr, value, size);
        }

        public default int read(RegSpec regSpec, Size size) {
            return read(regSpec, regSpec.addr, size);
        }
    }

    public static void writeBuffers(ByteBuffer b1, ByteBuffer b2, int pos, int value, Size size) {
        writeBuffer(b1, pos, value, size);
        writeBuffer(b2, pos, value, size);
    }

    public static int readBufferLong(ByteBuffer b, RegSpec r) {
        return readBuffer(b, r.addr & RegSpec.REG_MASK, Size.LONG);
    }

    public static void writeBufferLong(ByteBuffer b, RegSpec r, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    public static void writeBuffersLong(ByteBuffer b, RegSpec r, RegSpec r1, RegSpec r2, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r1.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r2.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    public static void writeBuffersLong(ByteBuffer b, RegSpec r, RegSpec r1, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r1.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    public static boolean writeBufferHasChanged(ByteBuffer b, int pos, int value, Size size) {
        if (size == Size.LONG) {
            LOG.error("Unable to handle LONG writes, reg: {}, value: {}", Integer.toHexString(pos),
                    Integer.toHexString(value));
            throw new RuntimeException("Unable to handle LONG writes, reg: " + Integer.toHexString(pos));
        }
        int baseReg = pos & ~1;
        int val = readBuffer(b, baseReg, Size.WORD);
        writeBuffer(b, pos, value, size);
        int newVal = readBuffer(b, baseReg, Size.WORD);
        return newVal != val;
    }

    public static void writeRegBuffer(RegSpec r, ByteBuffer b, int value, Size size) {
        writeBuffer(b, r.addr, value & r.writeMask, size);
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

    public static void setBit(ByteBuffer b1, ByteBuffer b2, int pos, int bitPos, int bitValue, Size size) {
        setBit(b1, pos, bitPos, bitValue, size);
        setBit(b2, pos, bitPos, bitValue, size);
    }

    /**
     * @return true - has changed, false - otherwise
     */
    public static boolean setBit(ByteBuffer b, int pos, int bitPos, int bitValue, Size size) {
        int val = readBuffer(b, pos, size);
        //clear bit and then set it
        int newVal = (val & ~(1 << bitPos)) | (bitValue << bitPos);
        if (val != newVal) {
            writeBuffer(b, pos, newVal, size);
            return true;
        }
        return false;
    }

    /**
     * @return the new value
     */
    public static int setBitVal(ByteBuffer b, int pos, int bitPos, int bitValue, Size size) {
        int val = readBuffer(b, pos, size);
        //clear bit and then set it
        int newVal = (val & ~(1 << bitPos)) | (bitValue << bitPos);
        if (val != newVal) {
            writeBuffer(b, pos, newVal, size);
            return newVal;
        }
        return val;
    }

    public static int readWordFromBuffer(S32XMMREG.RegContext ctx, S32xDict.RegSpecS32x reg) {
        return readBufferReg(ctx, reg, reg.addr, Size.WORD);
    }

    public static int readBufferReg(S32XMMREG.RegContext ctx, S32xDict.RegSpecS32x reg, int address, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == VDP) {
            return readBuffer(ctx.vdpRegs, address, size);
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
            case REG_M68K:
                return readBuffer(ctx.sysRegsMd, address, size);
            case REG_SH2:
                return readBuffer(ctx.sysRegsSh2, address, size);
        }
        LOG.error("Unable to read buffer: {}, addr: {} {}", reg.name, th(address), size);
        return (int) size.getMask();
    }

    public static void setBitReg(S32XMMREG.RegContext rc, S32xDict.RegSpecS32x reg, int address, int pos, int value, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == VDP) {
            S32xUtil.setBit(rc.vdpRegs, address, pos, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
                S32xUtil.setBit(rc.sysRegsMd, rc.sysRegsSh2, address, pos, value, size);
                return;
            case REG_M68K:
                S32xUtil.setBit(rc.sysRegsMd, address, pos, value, size);
                return;
            case REG_SH2:
                S32xUtil.setBit(rc.sysRegsSh2, address, pos, value, size);
                return;
        }
        LOG.error("Unable to setBit: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    public static void writeBufferReg(S32XMMREG.RegContext rc, S32xDict.RegSpecS32x reg, int address, int value, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == VDP) {
            writeBuffer(rc.vdpRegs, address, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
                writeBuffer(rc.sysRegsMd, address, value, size);
                writeBuffer(rc.sysRegsSh2, address, value, size);
                return;
            case REG_M68K:
                writeBuffer(rc.sysRegsMd, address, value, size);
                return;
            case REG_SH2:
                writeBuffer(rc.sysRegsSh2, address, value, size);
                return;
        }
        LOG.error("Unable to write buffer: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    public static String toHexString(ByteBuffer b, int pos, Size size) {
        return Integer.toHexString(readBuffer(b, pos, size));
    }

    public enum CpuDeviceAccess {
        MASTER, SLAVE, M68K;

        public static final CpuDeviceAccess[] cdaValues = CpuDeviceAccess.values();
    }
}