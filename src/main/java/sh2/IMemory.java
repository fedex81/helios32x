package sh2;

import omegadrive.util.Size;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory {

    public static class PrefetchContext {
        public static final int DEFAULT_PREFETCH_LOOKAHEAD = 0x16;

        public final int prefetchLookahead;
        public final int[] prefetchWords;

        public int pc, start, end, prefetchPc, pcMasked;
        public int memAccessDelay;
        public ByteBuffer buf;

        public PrefetchContext() {
            this(DEFAULT_PREFETCH_LOOKAHEAD);
        }

        public PrefetchContext(int lookahead) {
            prefetchLookahead = lookahead;
            prefetchWords = new int[lookahead << 1];
        }
    }

    void write8i(int reg, byte val);

    void write16i(int reg, int val);

    void write32i(int reg, int val);

    int read8i(int reg);

    int read16i(int reg);

    int read32i(int i);

    void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu);

    void resetSh2();

    default int fetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
        return read16i(pc);
    }

    default int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu) {
        return read16i(pc);
    }

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
