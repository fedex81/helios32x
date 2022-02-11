package sh2;

import omegadrive.util.Size;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory {

    void write8i(int reg, byte val);

    void write16i(int reg, int val);

    void write32i(int reg, int val);

    int read8i(int reg);

    int read16i(int reg);

    int read32i(int i);

    void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu);

    void resetSh2();

    default void write(int register, int value, Size size) {
        switch (size) {
            case BYTE:
                write8i(register, (byte) value);
                break;
            case WORD:
                write16i(register, value);
                break;
            case LONG:
                write32i(register, value);
                break;
        }
    }

    default int read(int register, Size size) {
        switch (size) {
            case BYTE:
                return read8i(register);
            case WORD:
                return read16i(register);
            case LONG:
                return read32i(register);
        }
        return (int) size.getMask();
    }
}
