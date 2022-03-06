package s32x;

import omegadrive.memory.IMemoryProvider;
import omegadrive.memory.MemoryProvider;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.junit.jupiter.api.Assertions;
import s32x.util.SystemTestUtil;
import sh2.MarsLauncherHelper;
import sh2.MarsLauncherHelper.Sh2LaunchContext;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.S32xBus;

import java.nio.ByteBuffer;

import static sh2.dict.S32xDict.RegSpecS32x.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsRegTestUtil {

    public static final int VDP_REG_OFFSET = 0x100;
    public static final int FBCR_OFFSET = 0x4000 + VDP_REG_OFFSET + FBCR.addr;
    public static final int BITMAP_MODE_OFFSET = 0x4000 + VDP_REG_OFFSET + VDP_BITMAP_MODE.addr;
    public static final int INT_MASK = 0x4000 + SH2_INT_MASK.addr;
    public static final int AD_CTRL = INT_MASK;
    public static int AFLEN_OFFSET = 0x4000 + VDP_REG_OFFSET + AFLR.addr;
    public static int AFSAR_OFFSET = 0x4000 + VDP_REG_OFFSET + AFSAR.addr;

    static {
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    public static Sh2LaunchContext createTestInstance() {
        byte[] brom = new byte[0x1000];
        Md32xRuntimeData.releaseInstance();
        Md32xRuntimeData.newInstance();
        Sh2LaunchContext lc = MarsLauncherHelper.setupRom(new S32xBus(), ByteBuffer.wrap(brom));

        int[] irom = Util.toSignedIntArray(brom);
        IMemoryProvider mp = MemoryProvider.createGenesisInstance();
        mp.setRomData(irom);
        SystemTestUtil.setupNewMdSystem(lc.bus, mp);
        return lc;
    }

    public static void assertFrameBufferDisplay(S32XMMREG s32XMMREG, int num) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public static void assertPEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.BYTE);
        Assertions.assertEquals(enable ? 1 : 0, (res >> 5) & 1);
    }

    public static void assertFEN(S32XMMREG s32XMMREG, int value) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(value, (res >> 1) & 1);
    }

    public static void assertVBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, res >> 15);
    }

    public static void assertHBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, (res >> 14) & 1);
    }
}
