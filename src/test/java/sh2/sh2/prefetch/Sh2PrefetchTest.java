package sh2.sh2.prefetch;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2.Sh2Config;
import sh2.sh2.Sh2Helper;
import sh2.sh2.drc.Sh2Block;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.sh2.cache.Sh2Cache.CACHE_BYTES_PER_LINE;
import static sh2.sh2.prefetch.Sh2PrefetchSimple.prefetchContexts;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 **/
public class Sh2PrefetchTest extends Sh2CacheTest {

    private int cacheAddrDef;
    private int noCacheAddrDef;

    @BeforeEach
    public void beforeEach() {
        super.before();
        cacheAddrDef = SH2_START_SDRAM_CACHE | 0x2;
        noCacheAddrDef = cacheAddrDef | SH2_CACHE_THROUGH_OFFSET;
    }

    @ParameterizedTest
    @MethodSource("fileProvider")
    public void testPrefetch(Sh2Config c) {
        System.out.println("Testing: " + c);
        resetCacheConfig(c);

        testRamCacheOffInternal();
        testRamCacheOffWriteInternal();
        testRamCacheOnWrite_01Internal();
        testRamCacheOnWrite_02Internal();
        testLongWriteInternal();
        testCacheReplaceWithPrefetchInternal();
        testCacheEffectsOnPrefetchInternal();
        //TODO check this, bug?
        if (!c.drcEn || !c.prefetchEn || !c.prefetchEn) {
            testRamCacheToggleInternal();
            testRamCacheMasterSlaveInternal();
        }
    }

    @Disabled
    @Override
    public void testCache(Sh2Config c) {
    }

    @Override
    protected void resetMemory() {
        super.resetMemory();
        for (int i = 0; i < RAM_SIZE; i += 2) {
            Sh2Helper.get(SH2_START_SDRAM_CACHE | i, MASTER).invalidateBlock();
            Sh2Helper.get(SH2_START_SDRAM | i, MASTER).invalidateBlock();
        }
    }

    protected void testRamCacheOffInternal() {
        resetMemory();
        enableCache(MASTER, false);
        enableCache(SLAVE, false);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkFetch(MASTER, noCacheAddrDef, NOP);
        checkFetch(MASTER, cacheAddrDef, NOP);
    }

    protected void testRamCacheOffWriteInternal() {
        resetMemory();
        testRamCacheOffInternal();
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

    protected void testRamCacheOnWrite_01Internal() {
        resetMemory();
        testRamCacheOffInternal();
        enableCache(MASTER, true);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write16(cacheAddrDef, CLRMAC);

        //cache is write-through
        checkFetch(MASTER, cacheAddrDef, CLRMAC);
        checkFetch(MASTER, noCacheAddrDef, CLRMAC);

        if (Sh2Config.get().cacheEn) {
            //no cache write, cache is stale
            memory.write16(noCacheAddrDef, NOP);

            checkFetch(MASTER, cacheAddrDef, CLRMAC);
            checkFetch(MASTER, noCacheAddrDef, NOP);
        }
    }

    protected void testRamCacheOnWrite_02Internal() {
        resetMemory();
        testRamCacheOffInternal();
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
        if (Sh2Config.get().cacheEn) {
            //cache hit
            checkFetch(SLAVE, cacheAddrDef, CLRMAC);

            //update SLAVE cache
            Md32xRuntimeData.setAccessTypeExt(SLAVE);
            memory.write16(cacheAddrDef, NOP);

            //cache hit
            checkFetch(SLAVE, cacheAddrDef, NOP);
        }
    }

    protected void testRamCacheToggleInternal() {
        resetMemory();
        testRamCacheOffInternal();
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

        if (Sh2Config.get().cacheEn) {
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
    }

    protected void testLongWriteInternal() {
        resetMemory();
        testRamCacheOffInternal();
        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);
        memory.invalidateAllPrefetch(MASTER);
        memory.invalidateAllPrefetch(SLAVE);

        //on a word boundary but not on a long boundary
        Assertions.assertTrue((cacheAddrDef & 1) == 0 && (cacheAddrDef & 3) != 0);
        Assertions.assertTrue((cacheAddrDef & 1) == 0 && (cacheAddrDef & 3) != 0);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddrDef, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddrDef, Size.WORD);

        checkFetch(MASTER, cacheAddrDef, NOP);

        if (!Sh2Config.get().cacheEn) {
            return;
        }
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddrDef, Size.WORD);

        //long write within the prefetch window
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddrDef - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddrDef - 2, CLRMAC);
        checkFetch(MASTER, cacheAddrDef, SETT);

        //long write crossing the prefetch window
        int cacheAddr2 = cacheAddrDef + 16;
        int noCacheAddr2 = noCacheAddrDef + 16;

        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr2, Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr2, Size.WORD);

        checkFetch(MASTER, cacheAddr2, NOP);

        //TODO check this, bug?
        if (Sh2Config.get().cacheEn) {
            return;
        }
        Collection<Sh2Block> l = getPrefetchBlocksAt(MASTER, cacheAddr2);
        Assertions.assertEquals(1, l.size());
        int pstart = l.stream().findFirst().get().start;
        Assertions.assertTrue(pstart > 0);

        Md32xRuntimeData.setAccessTypeExt(MASTER);
        memory.write32(cacheAddr2 - 2, (CLRMAC << 16) | SETT);

        checkFetch(MASTER, cacheAddrDef - 2, CLRMAC);
        checkFetch(MASTER, cacheAddrDef, SETT);
    }

    protected void testRamCacheMasterSlaveInternal() {
        resetMemory();
        testRamCacheOffInternal();
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

        if (!Sh2Config.get().cacheEn) {
            return;
        }
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

    protected void testCacheReplaceWithPrefetchInternal() {
        resetMemory();
        super.testCacheReplaceInternal();
        Collection<Sh2Block> blocks = getPrefetchBlocksAt(MASTER, cacheReplace_cacheAddr[0]);
        blocks.stream().allMatch(b -> Sh2Block.INVALID_BLOCK == b);
    }

    protected void testCacheEffectsOnPrefetchInternal() {
        resetMemory();
        testRamCacheOffInternal();
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

        if (!Sh2Config.get().prefetchEn) {
            return;
        }

        Collection<Sh2Block> l = getPrefetchBlocksAt(MASTER, cacheAddr);
        Assertions.assertEquals(1, l.size());
        Sh2Block block = l.stream().findFirst().get();

        //TODO check, bug?
        if (Sh2Config.get().prefetchEn && Sh2Config.get().drcEn) {
            return;
        }

        //JMP + delaySlot (NOP)
        int blockEndAddress = SH2_START_SDRAM_CACHE | block.end;
        int[] exp = {NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP};
        int baseCacheAddr = cacheAddr & 0xFFFF_FFF0;
        int outOfCacheAddr = baseCacheAddr + CACHE_BYTES_PER_LINE;
        Assertions.assertEquals(prefetchEndAddress + 4, blockEndAddress);
        Assertions.assertTrue(blockEndAddress > outOfCacheAddr);

        //fetch continued after to load data after the cache line limit
        Assertions.assertTrue(block.prefetchWords[8] > 0);

        if (!Sh2Config.get().cacheEn) {
            return;
        }
        //fetch should trigger cache refill on cacheAddr but avoid the cache on successive prefetches
        checkCacheLineFilled(MASTER, cacheAddr, exp);

        //check other data is not in cache
        checkCacheContents(MASTER, Optional.empty(), baseCacheAddr - 2, Size.WORD);
        //this has been prefetched but it is not in cache
        checkCacheContents(MASTER, Optional.empty(), outOfCacheAddr, Size.WORD);
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

    public static Collection<Sh2Block> getPrefetchBlocksAt(CpuDeviceAccess cpu, int address) {
        Set<Sh2Block> l = new HashSet<>();
        if (Sh2Config.get().drcEn) {
            for (int i = address - 16; i < address; i += 2) {
                Sh2Block b = Sh2Helper.get(address, cpu).block;
                if (b != Sh2Block.INVALID_BLOCK) {
                    int end = b.prefetchPc + (b.end - b.start);
                    boolean include = b.prefetchPc <= address && end > address;
                    if (include) {
                        l.add(b);
                    }
                }
            }
        } else {
            if (prefetchContexts[cpu.ordinal()].prefetchPc == address) {
                Sh2Block block = new Sh2Block(address, cpu);
                block.start = address;
                block.end = prefetchContexts[cpu.ordinal()].end;
                block.prefetchWords = prefetchContexts[cpu.ordinal()].prefetchWords;
                l.add(block);
            }
        }
        return l;
    }
}