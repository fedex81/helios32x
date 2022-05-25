package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.prefetch.Sh2Prefetch.PrefetchContext;

import java.util.Optional;

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
        memory.cache[0].updateState(1);

        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);

        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, NOP);

        clearCache(MASTER);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);

        checkFetch(MASTER, cacheAddr, NOP);
        checkFetch(MASTER, noCacheAddr, NOP);
    }

    @Test
    public void testLongWrite() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        checkFetch(MASTER, cacheAddr, NOP);

        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr, Size.WORD);

        //long write within the prefetch window
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddr - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddr - 2, CLRMAC);
        checkFetch(MASTER, cacheAddr, SETT);

        //long write crossing the prefetch window
        int cacheAddr2 = cacheAddr + 0x16;
        int noCacheAddr2 = noCacheAddr + 0x16;

        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr2, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr2, Size.WORD);

        checkFetch(MASTER, cacheAddr2, NOP);

        int pstart = getPrefetch(MASTER).start;
        Assertions.assertTrue(pstart > 0);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddr2 - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddr - 2, CLRMAC);
        checkFetch(MASTER, cacheAddr, SETT);
    }

    @Test
    public void testRamCacheMasterSlave() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddr, CLRMAC);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, SETT);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        checkMemoryNoCache(SLAVE, noCacheAddr, SETT);

        //MASTER cache hit, SLAVE cache miss reload SETT
        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, SETT);
        checkFetch(SLAVE, cacheAddr, SETT);
        checkCacheContents(SLAVE, Optional.of(SETT), noCacheAddr, Size.WORD);
        checkFetch(SLAVE, noCacheAddr, SETT);

        clearCache(SLAVE);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, NOP);

        //MASTER hit, SLAVE miss reload NOP
        checkFetch(MASTER, cacheAddr, CLRMAC);
        checkFetch(MASTER, noCacheAddr, NOP);
        checkFetch(SLAVE, cacheAddr, NOP);
        checkCacheContents(SLAVE, Optional.of(NOP), noCacheAddr, Size.WORD);
        checkFetch(SLAVE, noCacheAddr, NOP);

        //master clear
        clearCache(MASTER);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.of(NOP), noCacheAddr, Size.WORD);

        //master reload
        checkFetch(MASTER, cacheAddr, NOP);
        checkFetch(MASTER, noCacheAddr, NOP);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr, Size.WORD);

        //both caches out of sync
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(noCacheAddr, CLRMAC);
        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddr, SETT);

        //MASTER hit, SLAVE hit
        checkFetch(MASTER, cacheAddr, NOP);
        checkFetch(MASTER, noCacheAddr, SETT);
        checkFetch(SLAVE, cacheAddr, NOP);
        checkFetch(SLAVE, noCacheAddr, SETT);
    }

    private void checkAllFetches(int addr, int val) {
        checkFetch(MASTER, addr, val);
        checkFetch(SLAVE, addr, val);
    }

    private void checkMemoryNoCache(CpuDeviceAccess cpu, int addr, int val) {
        assert addr >> Sh2Prefetch.PC_CACHE_AREA_SHIFT == 2;
        Md32xRuntimeData.setAccessTypeExt(cpu);
        int res = memory.read16(addr);
        Assertions.assertEquals(val, res, cpu + "," + th(addr));
    }

    //NOTE: this can trigger a cache fill
    private void checkFetch(CpuDeviceAccess cpu, int addr, int val) {
        Md32xRuntimeData.setAccessTypeExt(cpu);
        int opcode = memory.fetch(addr, cpu);
        Assertions.assertEquals(val, opcode, cpu + "," + th(addr));
    }

    private PrefetchContext getPrefetch(CpuDeviceAccess cpu) {
        return memory.getPrefetch().prefetchContexts[cpu.ordinal()];
    }

}