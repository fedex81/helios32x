import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;
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

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        S32XMMREG.instance = null;
        s32XMMREG = new S32XMMREG();
    }

    @Test
    public void testFrameBufferSelect_BlankMode() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(BITMAP_MODE_OFFSET, 0, Size.WORD); //blank

        s32XMMREG.setVBlankOn(false);
        assertVBlank(false);
        assertFrameBufferDisplay(0);

        //change FB during BLANK display -> change immediately
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        //no further change at vblank
        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during vblank -> change immediately
        res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    @Test
    public void testFrameBufferSelect() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(BITMAP_MODE_OFFSET, 1, Size.WORD); //packed pixel
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);

        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);

        //change FB during vblank -> change immediately
        res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during display -> no change until next vblank
        s32XMMREG.setVBlankOn(false);
        res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    public void assertFrameBufferDisplay(int num) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public void assertVBlank(boolean on) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, res >> 15);
    }
}
