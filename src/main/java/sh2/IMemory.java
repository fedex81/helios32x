package sh2;

import omegadrive.util.Size;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory {

    void write(int register, int value, Size size);

    int read(int register, Size size);

    void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu);

    void resetSh2();

    default int fetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
        return read16(pc);
    }

    default int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu) {
        return read16(pc);
    }

    default void write8(int addr, byte val) {
        write(addr, val, Size.BYTE);
    }

    default void write16(int addr, int val) {
        write(addr, val, Size.WORD);
    }

    default void write32(int addr, int val) {
        write(addr, val, Size.LONG);
    }

    default int read8(int addr) {
        return read(addr, Size.BYTE);
    }

    default int read16(int addr) {
        return read(addr, Size.WORD);
    }

    default int read32(int addr) {
        return read(addr, Size.LONG);
    }

    default void invalidateCachePrefetch(S32xUtil.CpuDeviceAccess cpu) {
    }
}
