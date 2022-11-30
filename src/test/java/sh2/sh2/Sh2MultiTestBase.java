package sh2.sh2;

import org.junit.jupiter.api.BeforeEach;
import s32x.MarsRegTestUtil;
import sh2.MarsLauncherHelper;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil;
import sh2.Sh2Memory;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static sh2.dict.S32xDict.SH2_START_SDRAM;
import static sh2.sh2.prefetch.Sh2CacheTest.NOP;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2MultiTestBase {

    protected MarsLauncherHelper.Sh2LaunchContext lc;
    protected Sh2Memory memory;
    protected ByteBuffer rom;
    protected Sh2 sh2;
    protected Sh2Context masterCtx;
    protected static int RAM_SIZE = 0x100;
    protected static int ROM_SIZE = 0x1000;

    public static final Sh2.Sh2Config configDrcEn = new Sh2.Sh2Config(true, true, true, true, true);
    public static final Sh2.Sh2Config configCacheEn = new Sh2.Sh2Config(true, true, true, false, false);
    public static final Sh2.Sh2Config[] configList;
    protected static Sh2.Sh2Config config = configCacheEn;

    static {
        int parNumber = 5;
        int combinations = 1 << parNumber;
        configList = new Sh2.Sh2Config[combinations];
        assert combinations < 0x100;
        int pn = parNumber;
        for (int i = 0; i < combinations; i++) {
            byte ib = (byte) i;
            configList[i] = new Sh2.Sh2Config(S32xUtil.getBitFromByte(ib, pn - 1) > 0,
                    S32xUtil.getBitFromByte(ib, pn - 2) > 0, S32xUtil.getBitFromByte(ib, pn - 3) > 0,
                    S32xUtil.getBitFromByte(ib, pn - 4) > 0, S32xUtil.getBitFromByte(ib, pn - 5) > 0);
        }
    }

    @BeforeEach
    public void before() {
        Sh2.Sh2Config.reset(config);
        lc = MarsRegTestUtil.createTestInstance(ROM_SIZE);
        rom = lc.memory.getMemoryDataCtx().rom;
        lc.s32XMMREG.aden = 1;
        memory = (Sh2Memory) lc.memory;
        Md32xRuntimeData.releaseInstance();
        Md32xRuntimeData.newInstance();
        masterCtx = lc.masterCtx;
        sh2 = lc.sh2;
        resetMemory();
    }

    protected void resetMemory() {
        Arrays.fill(rom.array(), (byte) 0);
        initRam(RAM_SIZE);
    }

    protected void initRam(int len) {
        for (int i = 0; i < len; i += 2) {
            memory.write16(SH2_START_SDRAM | i, NOP);
        }
    }

    protected void resetCacheConfig(Sh2.Sh2Config c) {
        config = c;
        before();
        Sh2Helper.clear();
    }
}