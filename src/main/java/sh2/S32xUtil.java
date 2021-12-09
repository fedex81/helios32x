package sh2;

import omegadrive.util.Size;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xUtil {

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

    public enum CpuDeviceAccess {
        MASTER, SLAVE, M68K;

        public static final CpuDeviceAccess[] vals = CpuDeviceAccess.values();
    }
}