package sh2.sh2.prefetch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s32x.MarsRegTestUtil;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Memory;
import sh2.sh2.Sh2.FetchResult;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.Sh2Memory.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 **/
public class Sh2PrefetchTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;
    private Sh2Memory memory;
    private byte[] rom;

    public static final int NOP = 9;
    public static final int CLRMAC = 0x28;
    public static final int JMP_0 = 0x402b;

    int cacheAddr = START_SDRAM_CACHE | 0x3c;
    int noCacheAddr = cacheAddr | CACHE_THROUGH_OFFSET;

    @BeforeEach
    public void before() {
        rom = new byte[0x1000];
        lc = MarsRegTestUtil.createTestInstance(rom);
        lc.s32XMMREG.aden = 1;
        memory = lc.memory;
    }

    private void initRam(int len) {
        for (int i = 0; i < len; i += 2) {
            memory.write16(START_SDRAM | i, NOP);
        }
        memory.write16(START_SDRAM | (len - 4), JMP_0); //JMP 0
    }

    @Test
    public void testRamCacheOff() {
        Md32xRuntimeData.newInstance();
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        initRam(0x100);
        FetchResult ft = new FetchResult();
        ft.pc = noCacheAddr;
        memory.fetch(ft, MASTER);
        Assertions.assertEquals(NOP, ft.opcode);

        ft.pc = cacheAddr;
        memory.fetch(ft, MASTER);
        Assertions.assertEquals(NOP, ft.opcode);
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
        memory.cache[0].updateState(1);

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
        memory.cache[0].updateState(1);
        memory.cache[1].updateState(1);

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