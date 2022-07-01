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
import sh2.sh2.Sh2;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;

import java.util.Optional;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.SH2_START_SDRAM;
import static sh2.dict.S32xDict.SH2_START_SDRAM_CACHE;
import static sh2.sh2.Sh2Disassembler.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 **/
public class Sh2CacheTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;
    protected Sh2Memory memory;
    private byte[] rom;

    public static final int ILLEGAL = 0;
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
        initRam(0x100);
    }

    protected void initRam(int len) {
        for (int i = 0; i < len; i += 2) {
            memory.write16(SH2_START_SDRAM | i, NOP);
        }
        memory.write16(SH2_START_SDRAM | 4, JMP_0); //JMP 0
        memory.write16(SH2_START_SDRAM | 0x18, JMP_0); //JMP 0
    }

    @Test
    public void testCacheOff() {
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);
        int noCacheAddr = SH2_START_SDRAM | 0x8;
        int cacheAddr = SH2_START_SDRAM_CACHE | 0x8;
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
        int noCacheAddr = SH2_START_SDRAM | 0x8;
        int cacheAddr = SH2_START_SDRAM_CACHE | 0x8;
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

    protected int[] cacheReplace_cacheAddr = new int[5];

    @Test
    public void testCacheReplace() {
        int[] cacheAddr = cacheReplace_cacheAddr;
        int[] noCacheAddr = new int[5];

        //i == 0 -> this region should be all NOPs
        for (int i = 0; i < cacheAddr.length; i++) {
            cacheAddr[i] = SH2_START_SDRAM_CACHE | (i << 12) | 0xC0;
            noCacheAddr[i] = SH2_START_SDRAM | cacheAddr[i];
        }

        //NOTE: add a jump instruction to stop the DRC
        memory.write16(cacheAddr[0] + 14, JMP);
        memory.write16(cacheAddr[1], ADD0);
        memory.write16(cacheAddr[1] + 14, JMP);
        memory.write16(cacheAddr[2], CLRMAC);
        memory.write16(cacheAddr[2] + 14, JMP);
        memory.write16(cacheAddr[3], SETT);
        memory.write16(cacheAddr[3] + 14, JMP);
        memory.write16(cacheAddr[4], MACL);
        memory.write16(cacheAddr[4] + 14, JMP);

        enableCache(MASTER, true);
        enableCache(SLAVE, true);
        clearCache(MASTER);
        clearCache(SLAVE);

        checkCacheContents(MASTER, Optional.empty(), noCacheAddr[0], Size.WORD);
        checkCacheContents(SLAVE, Optional.empty(), noCacheAddr[0], Size.WORD);

        Md32xRuntimeData.setAccessTypeExt(MASTER);

        //fetch triggers a cache refill on cacheAddr
        doCacheFetch(MASTER, cacheAddr[0]);

        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr[0], Size.WORD);

        //cache miss, fill the ways, then replace the entry
        memory.read(cacheAddr[1], Size.WORD);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr[0], Size.WORD);
        checkCacheContents(MASTER, Optional.of(ADD0), noCacheAddr[1], Size.WORD);

        memory.read(cacheAddr[2], Size.WORD);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr[0], Size.WORD);
        checkCacheContents(MASTER, Optional.of(ADD0), noCacheAddr[1], Size.WORD);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr[2], Size.WORD);

        memory.read(cacheAddr[3], Size.WORD);
        checkCacheContents(MASTER, Optional.of(NOP), noCacheAddr[0], Size.WORD);
        checkCacheContents(MASTER, Optional.of(ADD0), noCacheAddr[1], Size.WORD);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr[2], Size.WORD);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr[3], Size.WORD);

        memory.read(cacheAddr[4], Size.WORD);
        //[0] has been replaced in cache
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr[0], Size.WORD);
        checkCacheContents(MASTER, Optional.of(ADD0), noCacheAddr[1], Size.WORD);
        checkCacheContents(MASTER, Optional.of(CLRMAC), noCacheAddr[2], Size.WORD);
        checkCacheContents(MASTER, Optional.of(SETT), noCacheAddr[3], Size.WORD);
        checkCacheContents(MASTER, Optional.of(MACL), cacheAddr[4], Size.WORD);
    }

    @Test
    public void testCacheWriteNoHitByte() {
        testCacheWriteNoHitInternal(Size.BYTE);
    }

    @Test
    public void testCacheWriteNoHitWord() {
        testCacheWriteNoHitInternal(Size.WORD);
    }

    @Test
    public void testCacheWriteNoHitLong() {
        testCacheWriteNoHitInternal(Size.LONG);
    }


    private void testCacheWriteNoHitInternal(Size size) {
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);
        int noCacheAddr = SH2_START_SDRAM | 0x8;
        int cacheAddr = SH2_START_SDRAM_CACHE | 0x8;
        int res = 0;

        int val = (int) ((CLRMAC << 16 | CLRMAC) & size.getMask());

        clearCache(MASTER);
        enableCache(MASTER, true);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, size);

        //write cache miss, writes to memory
        memory.write(cacheAddr, val, size);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, size);

        checkVal(MASTER, noCacheAddr, val, size);

        //cache still empty
        memory.read(noCacheAddr, size);
        checkCacheContents(MASTER, Optional.empty(), noCacheAddr, size);

        //cache gets populated
        memory.read(cacheAddr, size);
        checkCacheContents(MASTER, Optional.of(val), noCacheAddr, size);
    }

    protected void enableCache(CpuDeviceAccess cpu, boolean enabled) {
        memory.cache[cpu.ordinal()].updateState(enabled ? 1 : 0);
    }

    protected void clearCache(CpuDeviceAccess cpu) {
        memory.cache[cpu.ordinal()].cacheClear();
    }

    protected void checkCacheContents(CpuDeviceAccess cpu, Optional<Integer> expVal, int addr, Size size) {
        Sh2Cache cache = memory.cache[cpu.ordinal()];
        Assertions.assertTrue(cache instanceof Sh2CacheImpl);
        Sh2CacheImpl i = (Sh2CacheImpl) cache;
        Optional<Integer> optVal = Sh2CacheImpl.getCachedValueIfAny(i, addr & 0xFFF_FFFF, size);
        if (expVal.isPresent()) {
            Assertions.assertTrue(optVal.isPresent(), "Should NOT be empty: " + optVal);
            Assertions.assertEquals(expVal.get(), optVal.get());
        } else {
            Assertions.assertTrue(optVal.isEmpty(), "Should be empty: " + optVal);
        }
    }

    private void checkVal(CpuDeviceAccess cpu, int addr, int expVal, Size size) {
        Md32xRuntimeData.setAccessTypeExt(cpu);
        int val = memory.read(addr, size);
        Assertions.assertEquals(expVal, val, cpu + "," + th(addr));
    }

    protected Sh2.FetchResult doCacheFetch(CpuDeviceAccess cpu, int cacheAddr) {
        Sh2.FetchResult ft = new Sh2.FetchResult();
        ft.pc = cacheAddr;
        memory.fetch(ft, cpu);
        return ft;
    }
}