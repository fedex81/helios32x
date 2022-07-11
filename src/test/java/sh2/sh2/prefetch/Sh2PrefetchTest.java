package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2;

import java.util.List;
import java.util.Optional;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.sh2.cache.Sh2Cache.CACHE_BYTES_PER_LINE;
import static sh2.sh2.prefetch.Sh2Prefetcher.Sh2Block;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * TODO wwf raw, t-mek
 * TODO metal head: cache off, prefetch on -> ok
 * TODO golf 1st beta, Fusion ok
 **/
public class Sh2PrefetchTest extends Sh2CacheTest {

    private int cacheAddrDef = SH2_START_SDRAM_CACHE | 0x2;
    private int noCacheAddrDef = cacheAddrDef | SH2_CACHE_THROUGH_OFFSET;

    @BeforeEach
    public void beforeEach() {
        super.before();
        Assertions.assertTrue(Sh2Prefetch.SH2_ENABLE_PREFETCH);
    }

    @Test
    public void testRamCacheOff() {
        enableCache(MASTER, false);
        enableCache(SLAVE, false);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkFetch(MASTER, noCacheAddrDef, NOP);
        checkFetch(MASTER, cacheAddrDef, NOP);
    }

    @Test
    public void testRamCacheOffWrite() {
        testRamCacheOff();
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, CLRMAC);

        checkAllFetches(noCacheAddrDef, CLRMAC);
        checkAllFetches(cacheAddrDef, CLRMAC);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(cacheAddrDef, NOP);

        checkAllFetches(noCacheAddrDef, NOP);
        checkAllFetches(cacheAddrDef, NOP);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(noCacheAddrDef, CLRMAC);

        checkAllFetches(noCacheAddrDef, CLRMAC);
        checkAllFetches(cacheAddrDef, CLRMAC);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddrDef, NOP);

        checkAllFetches(noCacheAddrDef, NOP);
        checkAllFetches(cacheAddrDef, NOP);
    }

    @Test
    public void testRamCacheOnWrite_01() {
        testRamCacheOff();
        enableCache(MASTER, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, CLRMAC);

        //cache is write-through
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, CLRMAC);

        //no cache write, cache is stale
        memory.write16(noCacheAddrDef, NOP);

        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, NOP);
    }

    @Test
    public void testRamCacheOnWrite_02() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, CLRMAC);

        checkAllFetches(noCacheAddrDef, CLRMAC);
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        //cache miss loads from SDRAM
        checkFetch(SLAVE, cacheAddrDef, CLRMAC);

        //writes to MASTER cache and SDRAM, SLAVE cache is not touched
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, NOP);

        checkAllFetches(noCacheAddrDef, NOP);
        checkFetch(MASTER, cacheAddrDef, NOP);
        //cache hit
        checkFetch(SLAVE, cacheAddrDef, CLRMAC);

        //update SLAVE cache
        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(cacheAddrDef, NOP);

        //cache hit
        checkFetch(SLAVE, cacheAddrDef, NOP);
    }

    @Test
    public void testRamCacheToggle() {
        testRamCacheOff();
        enableCache(MASTER, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, CLRMAC);

        //cache is write-through
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, CLRMAC);

        //disable cache, cache is not cleared
        enableCache(MASTER, false);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, NOP);

        checkFetch(MASTER, noCacheAddrDef, NOP);
        checkFetch(MASTER, cacheAddrDef, NOP);

        //enable cache, we should still be holding the old value (ie. before disabling the cache)
        enableCache(MASTER, true);

        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddrDef, Size.WORD);

        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, NOP);

        clearCache(MASTER);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);

        checkFetch(MASTER, cacheAddrDef, NOP);
        checkFetch(MASTER, noCacheAddrDef, NOP);
    }

    @Test
    public void testLongWrite() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);
        memory.invalidateAllPrefetch(MASTER);
        memory.invalidateAllPrefetch(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        checkFetch(MASTER, cacheAddrDef, NOP);

        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddrDef, Size.WORD);

        //long write within the prefetch window
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddrDef - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddrDef - 2, CLRMAC);
        checkFetch(MASTER, cacheAddrDef, SETT);

        //long write crossing the prefetch window
        int cacheAddr2 = cacheAddrDef + 0x16;
        int noCacheAddr2 = noCacheAddrDef + 0x16;

        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr2, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr2, Size.WORD);

        checkFetch(MASTER, cacheAddr2, JMP_0);

        List<Sh2Block> l = getPrefetchBlocksAt(MASTER, cacheAddr2);
        Assertions.assertTrue(l.size() == 1);
        int pstart = l.get(0).start;
        Assertions.assertTrue(pstart > 0);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddr2 - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddrDef - 2, CLRMAC);
        checkFetch(MASTER, cacheAddrDef, SETT);
    }

    @Test
    public void testRamCacheMasterSlave() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        //M not in cache
        memory.write16(cacheAddrDef, CLRMAC);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        //M add to cache
        memory.read(cacheAddrDef, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        //S not in cache
        memory.write16(noCacheAddrDef, SETT);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        checkMemoryNoCache(SLAVE, noCacheAddrDef, SETT);

        //MASTER cache hit, SLAVE cache miss reload SETT
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, SETT);
        checkFetch(SLAVE, cacheAddrDef, SETT);
        checkCacheContents(SLAVE, Optional.of(SETT), noCacheAddrDef, Size.WORD);
        checkFetch(SLAVE, noCacheAddrDef, SETT);

        clearCache(SLAVE);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddrDef, NOP);

        //MASTER hit, SLAVE miss reload NOP
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, NOP);
        checkFetch(SLAVE, cacheAddrDef, NOP);
        checkCacheContents(SLAVE, Optional.of(NOP), noCacheAddrDef, Size.WORD);
        checkFetch(SLAVE, noCacheAddrDef, NOP);

        //master clear
        clearCache(MASTER);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.of(NOP), noCacheAddrDef, Size.WORD);

        //master reload
        checkFetch(MASTER, cacheAddrDef, NOP);
        checkFetch(MASTER, noCacheAddrDef, NOP);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddrDef, Size.WORD);

        //both caches out of sync
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(noCacheAddrDef, CLRMAC);
        Md32xRuntimeData.setAccessTypeExt(SLAVE);
        memory.write16(noCacheAddrDef, SETT);

        //MASTER hit, SLAVE hit
        checkFetch(MASTER, cacheAddrDef, NOP);
        checkFetch(MASTER, noCacheAddrDef, SETT);
        checkFetch(SLAVE, cacheAddrDef, NOP);
        checkFetch(SLAVE, noCacheAddrDef, SETT);
    }

    @Test
    public void testCacheReplaceWithPrefetch() {
        super.testCacheReplace();
        List<Sh2Block> blocks = getPrefetchBlocksAt(MASTER, cacheReplace_cacheAddr[0]);
        blocks.stream().allMatch(b -> Sh2Block.INVALID_BLOCK == b);
    }

    @Test
    public void testCacheEffectsOnPrefetch() {
        testRamCacheOff();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);

        //this region should be all NOPs
        int cacheAddr = SH2_START_SDRAM_CACHE + 0xC0;
        int noCacheAddr = SH2_START_SDRAM | cacheAddr;

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr, Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(MASTER);

        int prefetchEndAddress = cacheAddr + CACHE_BYTES_PER_LINE + 4;
        memory.write16(prefetchEndAddress, JMP_0);

        //fetch triggers a cache refill on cacheAddr
        doCacheFetch(MASTER, cacheAddr);

        List<Sh2Block> l = getPrefetchBlocksAt(MASTER, cacheAddr);
        Assertions.assertEquals(1, l.size());
        Sh2Block block = l.get(0);

        int blockEndAddress = SH2_START_SDRAM_CACHE | block.end;
        int[] exp = {NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP};
        int baseCacheAddr = cacheAddr & 0xFFFF_FFF0;
        int outOfCacheAddr = baseCacheAddr + CACHE_BYTES_PER_LINE;
        Assertions.assertTrue(prefetchEndAddress + 2 == blockEndAddress);
        Assertions.assertTrue(blockEndAddress > outOfCacheAddr);

        //fetch continued after to load data after the cache line limit
        Assertions.assertTrue(block.prefetchWords[8] > 0);

        //fetch should trigger cache refill on cacheAddr but avoid the cache on successive prefetches
        checkCacheLineFilled(MASTER, cacheAddr, exp);

        //check other data is not in cache
        checkCacheContents(MASTER, Optional.empty(), baseCacheAddr - 2, Size.WORD);
        //TODO readDirect breaks Chaotix, Vf and possibly more
        //TODO see Sh2Prefect::doPrefetch
        //this has been prefetched but it is not in cache
//        checkCacheContents(MASTER, Optional.empty(), outOfCacheAddr, Size.WORD);
    }

    //check that [addr & 0xF0, (addr & 0xF0) + 14] has been filled in a cache line
    private void checkCacheLineFilled(CpuDeviceAccess cpu, int addr, int... words) {
        Assertions.assertTrue(words.length == 8);
        int baseCacheAddr = addr & 0xFFFF_FFF0;
        for (int i = baseCacheAddr; i < baseCacheAddr + 16; i += 2) {
            int w = (i & 0xF) >> 1;
            checkCacheContents(cpu, Optional.of(words[w]), i, Size.WORD);
        }
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
        Sh2.FetchResult ft = doCacheFetch(cpu, addr);
        int opcode = ft.opcode;
        Assertions.assertEquals(val, opcode, cpu + "," + th(addr));
    }

    private List<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int address) {
        return memory.getPrefetchBlocksAt(cpu, address);
    }
}