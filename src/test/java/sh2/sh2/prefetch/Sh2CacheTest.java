package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s32x.MarsRegTestUtil;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;

import java.util.Optional;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.Sh2Memory.START_SDRAM;
import static sh2.Sh2Memory.START_SDRAM_CACHE;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 **/
public class Sh2CacheTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;
    protected Sh2Memory memory;
    private byte[] rom;

    public static final int NOP = 9;
    public static final int SETT = 0x18;
    public static final int CLRMAC = 0x28;
    public static final int JMP_0 = 0x402b;

    @BeforeEach
    public void before() {
        rom = new byte[0x1000];
        lc = MarsRegTestUtil.createTestInstance(rom);
        lc.s32XMMREG.aden = 1;
        memory = lc.memory;
        Md32xRuntimeData.releaseInstance();
        Md32xRuntimeData.newInstance();
    }

    protected void initRam(int len) {
        for (int i = 0; i < len; i += 2) {
            memory.write16(START_SDRAM | i, NOP);
        }
        memory.write16(START_SDRAM | 4, JMP_0); //JMP 0
    }

    @Test
    public void testCacheOff() {
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);
        int noCacheAddr = START_SDRAM | 0x8;
        int cacheAddr = START_SDRAM_CACHE | 0x8;
        clearCache(MASTER);
        enableCache(MASTER, false);

        memory.read16(noCacheAddr);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        memory.read16(cacheAddr);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        memory.write16(noCacheAddr, CLRMAC);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkVal(MASTER, noCacheAddr, CLRMAC, Size.WORD);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkVal(MASTER, cacheAddr, CLRMAC, Size.WORD);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        memory.write16(cacheAddr, SETT);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkVal(MASTER, noCacheAddr, SETT, Size.WORD);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkVal(MASTER, cacheAddr, SETT, Size.WORD);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
    }

    @Test
    public void testCacheOn() {
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);
        int noCacheAddr = START_SDRAM | 0x8;
        int cacheAddr = START_SDRAM_CACHE | 0x8;
        int res = 0;
        clearCache(MASTER);
        enableCache(MASTER, true);

        //read noCache, cache not accessed or modified
        memory.read16(noCacheAddr);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        //write noCache, cache empty, cache not modified
        memory.write16(noCacheAddr, CLRMAC);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        checkVal(MASTER, noCacheAddr, CLRMAC, Size.WORD);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        //read cache, entry added to cache
        res = memory.read16(cacheAddr);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);

        //write no cache, cache not modified
        memory.write16(noCacheAddr, SETT);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);

        //cache clear, reload from SDRAM
        clearCache(MASTER);
        res = memory.read16(cacheAddr);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr, Size.WORD);

        //cache disable, write to SDRAM
        enableCache(MASTER, false);
        memory.write16(cacheAddr, NOP);
        //enable cache, still holding the previous value
        enableCache(MASTER, true);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr, Size.WORD);

        //cache out of sync
        res = memory.read16(cacheAddr);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr, Size.WORD);
        res = memory.read16(noCacheAddr);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr, Size.WORD);

        //needs a purge or a write
        memory.write16(cacheAddr, NOP);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr, Size.WORD);
    }

    protected void enableCache(CpuDeviceAccess cpu, boolean enabled) {
        memory.cache[cpu.ordinal()].updateState(enabled ? 1 : 0);
    }

    protected void clearCache(CpuDeviceAccess cpu) {
        memory.cache[cpu.ordinal()].cacheClear();
    }

    private void checkCacheContents(CpuDeviceAccess cpu, Optional<Integer> expVal, int addr, Size size) {
        Sh2Cache cache = memory.cache[cpu.ordinal()];
        Assertions.assertTrue(cache instanceof Sh2CacheImpl);
        Sh2CacheImpl i = (Sh2CacheImpl) cache;
        Optional<Integer> optVal = Sh2CacheImpl.getCachedValueIfAny(i, addr & 0xFFF_FFFF, size);
        if (expVal.isPresent()) {
            Assertions.assertTrue(optVal.isPresent());
            Assertions.assertEquals(expVal.get(), optVal.get());
        } else {
            Assertions.assertTrue(optVal.isEmpty());
        }
    }

    private void checkVal(CpuDeviceAccess cpu, int addr, int expVal, Size size) {
        Md32xRuntimeData.setAccessTypeExt(cpu);
        int val = memory.read(addr, size);
        Assertions.assertEquals(expVal, val, cpu + "," + th(addr));
    }
}