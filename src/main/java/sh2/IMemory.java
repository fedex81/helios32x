package sh2;

import omegadrive.util.Size;
import sh2.sh2.Sh2;
import sh2.sh2.cache.Sh2Cache.CacheInvalidateContext;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.util.Collections;
import java.util.List;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory extends Sh2Prefetcher {

    void write(int register, int value, Size size);

    int read(int register, Size size);

    void resetSh2();

    default void fetch(Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
        ft.opcode = read16(ft.pc);
    }

    default int fetchDelaySlot(int pc, Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
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

    default void invalidateCachePrefetch(CacheInvalidateContext ctx) {
        //do nothing
    }

    default void invalidateAllPrefetch(S32xUtil.CpuDeviceAccess cpuDeviceAccess) {
        //do nothing
    }

    default List<Sh2Block> getPrefetchBlocksAt(S32xUtil.CpuDeviceAccess cpu, int address) {
        return Collections.emptyList();
    }
}
