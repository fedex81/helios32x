package sh2.sh2.cache;

import omegadrive.util.Size;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * Translated from yabause:
 * https://github.com/Yabause/yabause/blob/master/yabause/src/sh2cache.h
 */
public interface Sh2Cache {

    int CACHE_LINES = 64;
    int CACHE_BYTES_PER_LINE = 16;
    int CACHE_BYTES_PER_LINE_MASK = CACHE_BYTES_PER_LINE - 1;
    int CACHE_WAYS = 4;

    int AREA_MASK = 0xE0000000;
    int TAG_MASK = 0x1FFFFC00;
    int ENTRY_MASK = 0x000003F0;
    int ENTRY_SHIFT = 4;
    int LINE_MASK = CACHE_BYTES_PER_LINE - 1;

    int SH2_ADDRESS_SPACE_PARTITION_BITS = 3;
    int CACHE_ADDRESS_BITS = 32 - SH2_ADDRESS_SPACE_PARTITION_BITS;

    int CACHE_USE_H3 = 0; //topmost 3 bits
    int CACHE_THROUGH_H3 = 1;
    int CACHE_PURGE_H3 = 2;
    int CACHE_ADDRESS_ARRAY_H3 = 3;
    int CACHE_DATA_ARRAY_H3 = 6;
    int CACHE_IO_H3 = 7;

    int CACHE_USE = CACHE_USE_H3 << CACHE_ADDRESS_BITS;
    int CACHE_THROUGH = CACHE_THROUGH_H3 << CACHE_ADDRESS_BITS;
    int CACHE_PURGE = CACHE_PURGE_H3 << CACHE_ADDRESS_BITS;
    int CACHE_ADDRESS_ARRAY = CACHE_ADDRESS_ARRAY_H3 << CACHE_ADDRESS_BITS;
    int CACHE_DATA_ARRAY = CACHE_DATA_ARRAY_H3 << CACHE_ADDRESS_BITS;
    int CACHE_IO = CACHE_IO_H3 << CACHE_ADDRESS_BITS;
    int CACHE_PURGE_MASK = CACHE_PURGE - 1;

    int CACHE_PURGE_DELAY = 2;
    int DATA_ARRAY_SIZE = 0x1000;
    int DATA_ARRAY_MASK = DATA_ARRAY_SIZE - 1;

    static class Sh2CacheLine {
        int tag; //u32
        int v;
        int[] data = new int[CACHE_BYTES_PER_LINE]; //u8
    }

    static class Sh2CacheEntry {
        int enable; //u32
        int[] lru = new int[CACHE_LINES]; //u32
        Sh2CacheLine[][] way = new Sh2CacheLine[CACHE_WAYS][CACHE_LINES];
    }

    static class CacheInvalidateContext {
        public S32xUtil.CpuDeviceAccess cpu;
        public Sh2CacheLine line;
        public int cacheReadAddr, prevCacheAddr;
        public boolean force;
    }

    void cacheClear();

    @Deprecated
    int readDirect(int addr, Size size);

    int cacheMemoryRead(int addr, Size size);

    void cacheMemoryWrite(int addr, int val, Size size);

    ByteBuffer getDataArray();

    CacheContext updateState(int ccrValue);

    CacheContext getCacheContext();

    default int readMemoryUncached(IMemory memory, int address, Size size) {
        return memory.read(address | CACHE_THROUGH, size);
    }

    /**
     * TODO better way??
     */
    default int readMemoryUncachedNoDelay(IMemory memory, int address, Size size) {
        int delay = Md32xRuntimeData.resetCpuDelayExt();
        int res = memory.read(address | CACHE_THROUGH, size);
        Md32xRuntimeData.resetCpuDelayExt();
        Md32xRuntimeData.addCpuDelayExt(delay);
        return res;
    }


    default void writeMemoryUncached(IMemory memory, int address, int value, Size size) {
        memory.write(address | CACHE_THROUGH, value, size);
    }

    public static Sh2Cache createNoCacheInstance(S32xUtil.CpuDeviceAccess cpu, final IMemory memory) {
        return new Sh2CacheImpl(cpu, memory) {
            @Override
            public CacheContext updateState(int value) {
                CacheContext ctx = super.updateState(value);
                ca.enable = ctx.cacheEn = 0; //always disabled
                return ctx;
            }
        };
    }

    public static class CacheContext {
        public int ccr;
        public int way;
        public int cachePurge;
        public int twoWay;
        public int dataReplaceDis;
        public int instReplaceDis;
        public int cacheEn;

        @Override
        public String toString() {
            return "CacheContext{" +
                    "way=" + way +
                    ", cachePurge=" + cachePurge +
                    ", twoWay=" + twoWay +
                    ", dataReplaceDis=" + dataReplaceDis +
                    ", instReplaceDis=" + instReplaceDis +
                    ", cacheEn=" + cacheEn +
                    '}';
        }
    }
}
