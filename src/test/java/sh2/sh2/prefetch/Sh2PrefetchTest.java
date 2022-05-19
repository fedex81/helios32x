package sh2.sh2.prefetch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2.FetchResult;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.Sh2Memory.CACHE_THROUGH_OFFSET;
import static sh2.Sh2Memory.START_SDRAM_CACHE;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 **/
public class Sh2PrefetchTest extends Sh2CacheTest {

    int cacheAddr = START_SDRAM_CACHE | 0x2;
    int noCacheAddr = cacheAddr | CACHE_THROUGH_OFFSET;

    @Test
    public void testRamCacheOff() {
        Md32xRuntimeData.newInstance();
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);

        checkFetch(MASTER, noCacheAddr, NOP);
        checkFetch(MASTER, cacheAddr, NOP);
    }

    @Test
    public void testRamCacheOffWrite() {
        testRamCacheOff();
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);

        checkAllFetches(noCacheAddr, CLRMAC);
        checkAllFetches(cacheAddr, CLRMAC);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(cacheAddr, NOP);

        checkAllFetches(noCacheAddr, NOP);
        checkAllFetches(cacheAddr, NOP);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(noCacheAddr, CLRMAC);

        checkAllFetches(noCacheAddr, CLRMAC);
        checkAllFetches(cacheAddr, CLRMAC);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, NOP);

        checkAllFetches(noCacheAddr, NOP);
        checkAllFetches(cacheAddr, NOP);
    }

    @Test
    public void testRamCacheOnWrite_01() {
        testRamCacheOff();
        enableCache(MASTER, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);

        //cache is write-through
        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, CLRMAC);

        //no cache write, cache is stale
        memory.write16(noCacheAddr, NOP);

        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, NOP);
    }

    @Test
    public void testRamCacheOnWrite_02() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);

        checkAllFetches(noCacheAddr, CLRMAC);
        checkFetch(MASTER, cacheAddr, CLRMAC);
        //cache miss loads from SDRAM
        checkFetch(SLAVE, cacheAddr, CLRMAC);

        //writes to MASTER cache and SDRAM, SLAVE cache is not touched
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, NOP);

        checkAllFetches(noCacheAddr, NOP);
        checkFetch(MASTER, cacheAddr, NOP);
        //cache hit
        checkFetch(SLAVE, cacheAddr, CLRMAC);

        //update SLAVE cache
        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(cacheAddr, NOP);

        //cache hit
        checkFetch(SLAVE, cacheAddr, NOP);
    }

    //TODO fix
    @Disabled
    @Test
    public void testRamCacheToggle() {
        testRamCacheOff();
        enableCache(MASTER, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);

        //cache is write-through
        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, CLRMAC);

        //disable cache, cache is not cleared
        enableCache(MASTER, false);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, NOP);

        checkFetch(MASTER, noCacheAddr, NOP);
        checkFetch(MASTER, cacheAddr, NOP);

        //enable cache, we should still be holding the old value (ie. before disabling the cache)
        //TODO on cache enable clear all blocks??? this would force a reload from cache
        memory.cache[0].updateState(1);

        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, NOP);
    }

    @Test
    public void testRamCacheMasterSlave() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);
        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, SETT);

        //MASTER, SLAVE cache miss
        checkFetch(MASTER, cacheAddr, SETT);
        checkFetch(MASTER, noCacheAddr, SETT);
        checkFetch(SLAVE, cacheAddr, SETT);
        checkFetch(SLAVE, noCacheAddr, SETT);


        //MASTER load in cache
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.read16(cacheAddr);

        clearCache(SLAVE);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, CLRMAC);

        //MASTER hit, SLAVE miss
        checkFetch(MASTER, cacheAddr, SETT);
        checkFetch(MASTER, noCacheAddr, CLRMAC);
        checkFetch(SLAVE, cacheAddr, SETT);
        checkFetch(SLAVE, noCacheAddr, CLRMAC);

        //SLAVE load in cache
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(noCacheAddr, NOP);

        //MASTER hit, SLAVE hit
        checkFetch(MASTER, cacheAddr, SETT);
        checkFetch(MASTER, noCacheAddr, NOP);
        checkFetch(SLAVE, cacheAddr, SETT);
        checkFetch(SLAVE, noCacheAddr, NOP);

    }

    private void checkAllFetches(int addr, int val) {
        checkFetch(MASTER, addr, val);
        checkFetch(SLAVE, addr, val);
    }

    private void checkFetch(CpuDeviceAccess cpu, int addr, int val) {
        FetchResult ft = new FetchResult();
        ft.pc = addr;
        memory.fetch(ft, cpu);
        Assertions.assertEquals(val, ft.opcode, cpu + "," + th(addr));
    }

}