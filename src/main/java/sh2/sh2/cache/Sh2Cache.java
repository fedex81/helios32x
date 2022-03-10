package sh2.sh2.cache;

import omegadrive.util.Size;
import sh2.IMemory;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * Translated from yabause:
 * https://github.com/Yabause/yabause/blob/master/yabause/src/sh2cache.h
 */
public interface Sh2Cache {

    public static final int AREA_MASK = 0xE0000000;
    public static final int TAG_MASK = (0x1FFFFC00);
    public static final int ENTRY_MASK = (0x000003F0);
    public static final int ENTRY_SHIFT = (4);
    public static final int LINE_MASK = (0x0000000F);

    public static final int CACHE_USE = ((0x00) << 29);
    public static final int CACHE_THROUGH = ((0x01) << 29);
    public static final int CACHE_PURGE = ((0x02) << 29);
    public static final int CACHE_ADDRES_ARRAY = ((0x03) << 29);
    public static final int CACHE_DATA_ARRAY = ((0x06) << 29);
    public static final int CACHE_IO = ((0x07) << 29);

    static class Sh2CacheLine {
        int tag; //u32
        int v;
        int[] data = new int[16]; //u8
    }

    static class Sh2CacheEntry {
        int enable; //u32
        int[] lru = new int[64]; //u32
        Sh2CacheLine[][] way = new Sh2CacheLine[4][64];
    }

    void cache_clear(Sh2CacheEntry ca);

    void cache_enable(Sh2CacheEntry ca);

    void cache_disable(Sh2CacheEntry ca);

    int cache_memory_read(int addr, Size size);

    void cache_memory_write(int addr, int val, Size size);


    default int readMemoryUncached(IMemory memory, int address, Size size) {
        return memory.read(address | CACHE_THROUGH, size);
    }

    default void writeMemoryUncached(IMemory memory, int address, int value, Size size) {
        memory.write(address | CACHE_THROUGH, value, size);
    }

    //values are from console measurements and have extra delays included
    //delay 0 if the measured cycles are 7 or less, otherwise subtract 7
    public static int ADJUST_CYCLES(int n) {
        return (n <= 7 ? 0 : (n - 7));
    }

    public static Sh2Cache createNoCacheInstance(final IMemory memory) {
        return new Sh2Cache() {
            @Override
            public void cache_clear(Sh2CacheEntry ca) {
            }

            @Override
            public void cache_enable(Sh2CacheEntry ca) {
            }

            @Override
            public void cache_disable(Sh2CacheEntry ca) {
            }

            @Override
            public void cache_memory_write(int addr, int val, Size size) {
                writeMemoryUncached(memory, addr, val, size);
            }

            @Override
            public int cache_memory_read(int addr, Size size) {
                return readMemoryUncached(memory, addr, size);
            }
        };
    }
}
