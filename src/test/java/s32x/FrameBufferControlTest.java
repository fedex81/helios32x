package s32x;

import omegadrive.util.Size;
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

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        s32XMMREG = MarsRegTestUtil.createInstance();
    }

    @Test
    public void testFrameBufferSelect_BlankMode() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, 0, Size.WORD); //blank

        s32XMMREG.setVBlankOn(false);
        assertVBlank(false);
        assertFrameBufferDisplay(0);

        //change FB during BLANK display -> change immediately
        int res = s32XMMREG.read(MarsRegTestUtil.FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        //no further change at vblank
        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during vblank -> change immediately
        res = s32XMMREG.read(MarsRegTestUtil.FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    @Test
    public void testFrameBufferSelect() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, 1, Size.WORD); //packed pixel
        int res = s32XMMREG.read(MarsRegTestUtil.FBCR_OFFSET, Size.WORD);

        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);

        //change FB during vblank -> change immediately
        res = s32XMMREG.read(MarsRegTestUtil.FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(true);
        assertFrameBufferDisplay(1);

        //change FB during display -> no change until next vblank
        s32XMMREG.setVBlankOn(false);
        res = s32XMMREG.read(MarsRegTestUtil.FBCR_OFFSET, Size.WORD);
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, res ^ 1, Size.WORD);
        assertVBlank(false);
        assertFrameBufferDisplay(1);

        s32XMMREG.setVBlankOn(true);
        assertVBlank(true);
        assertFrameBufferDisplay(0);
    }

    private void assertFrameBufferDisplay(int num) {
        MarsRegTestUtil.assertFrameBufferDisplay(s32XMMREG, num);
    }

    private void assertVBlank(boolean on) {
        MarsRegTestUtil.assertVBlank(s32XMMREG, on);
    }
}
