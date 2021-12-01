package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import sh2.IntC;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsRegTestUtil {

    public static final int VDP_REG_OFFSET = 0x100;
    public static final int FBCR_OFFSET = 0x4000 + VDP_REG_OFFSET + S32xDict.FBCR;
    public static final int BITMAP_MODE_OFFSET = 0x4000 + VDP_REG_OFFSET + S32xDict.VDP_BITMAP_MODE;
    public static final int INT_MASK = 0x4000 + S32xDict.INT_MASK;
    public static final int AD_CTRL = INT_MASK;
    public static int AFLEN_OFFSET = 0x4000 + VDP_REG_OFFSET + S32xDict.AFLR;
    public static int AFSAR_OFFSET = 0x4000 + VDP_REG_OFFSET + S32xDict.AFSAR;

    static {
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    public static S32XMMREG createInstance() {
        S32XMMREG s = new S32XMMREG();
        s.setInterruptControl(new IntC());
        return s;
    }

    public static void assertFrameBufferDisplay(S32XMMREG s32XMMREG, int num) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public static void assertPEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.BYTE);
        Assertions.assertEquals(enable ? 1 : 0, (res >> 5) & 1);
    }

    public static void assertFEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(enable ? 0 : 1, (res >> 1) & 1);
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
