import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;
import sh2.Sh2Emu;
import sh2.Sh2Util;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class FrameBufferControlTest {

    static int VDP_REG_OFFSET = 0x100;
    static int FBCR_OFFSET = 0x4000 + VDP_REG_OFFSET + Sh2Util.FBCR;
    static int BITMAP_MODE_OFFSET = 0x4000 + VDP_REG_OFFSET + Sh2Util.VDP_BITMAP_MODE;

    static {
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    @Test
    public void testFrameBufferSelect_BlankMode() {
        S32XMMREG.sh2Access = Sh2Emu.Sh2Access.MASTER;
        S32XMMREG.write(BITMAP_MODE_OFFSET, 0, Size.WORD); //blank

        S32XMMREG.setVBlankOn(false);
        assertVBlank(false);
        assertFrameBufferDisplay(0);

        //change FB during BLANK display -> change immediately
        int res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        S32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        //no further change at vblank
        S32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during vblank -> change immediately
        res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        S32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    @Test
    public void testFrameBufferSelect() {
        S32XMMREG.sh2Access = Sh2Emu.Sh2Access.MASTER;
        S32XMMREG.write(BITMAP_MODE_OFFSET, 1, Size.WORD); //packed pixel
        int res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);

        S32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);

        //change FB during vblank -> change immediately
        res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        S32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during display -> no change until next vblank
        S32XMMREG.setVBlankOn(false);
        res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        S32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        S32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    public static void assertFrameBufferDisplay(int num) {
        int res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public static void assertVBlank(boolean on) {
        int res = S32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, res >> 15);
    }
}
