package sh2;

import omegadrive.Device;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xUtil {

    private static final Logger LOG = LogManager.getLogger(S32xUtil.class.getSimpleName());

    public enum DebugMode {NONE, INST_ONLY, NEW_INST_ONLY, STATE}

    public static interface StepDevice extends Device {
        public default void step() {
        } //DO NOTHING
    }

    public static void writeBuffers(ByteBuffer b1, ByteBuffer b2, int pos, int value, Size size) {
        writeBuffer(b1, pos, value, size);
        writeBuffer(b2, pos, value, size);
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

    public static String toHexString(ByteBuffer b, int pos, Size size) {
        return Integer.toHexString(readBuffer(b, pos, size));
    }

    public enum CpuDeviceAccess {
        MASTER, SLAVE, M68K;

        public static final CpuDeviceAccess[] cdaValues = CpuDeviceAccess.values();
    }
}