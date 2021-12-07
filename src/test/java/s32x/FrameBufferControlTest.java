package s32x;

import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;

import static sh2.dict.S32xDict.P32XV_240;
import static sh2.dict.S32xDict.P32XV_PAL;

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

    @Test
    public void testPalAnd240() {
        assertPal(false);
        assert240(false);

        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V28);
        assertPal(true);
        assert240(false);

        s32XMMREG.updateVideoMode(VideoMode.NTSCU_H40_V28);
        assertPal(false);
        assert240(false);

        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V30);
        assertPal(true);
        assert240(true);

        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V28);
        assertPal(true);
        assert240(false);

        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V30);
        assertPal(true);
        assert240(true);

        //NTSC forces 240 -> 224
        s32XMMREG.updateVideoMode(VideoMode.NTSCU_H40_V28);
        assertPal(false);
        assert240(false);
    }

    @Test
    public void testRegWritePal() {
        assertPal(false);
        assert240(false);

        //PAL 1->0
        int res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
        res &= ~P32XV_PAL; //set to PAL
        //PAL bit is read-only, no effect
        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, res, Size.WORD);
        assertPal(false);
        assert240(false);

        //PAL 0->1
        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V28);
        assertPal(true);
        assert240(false);

        res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
        res |= P32XV_PAL; //set to NTSC
        //PAL bit is read-only, no effect
        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, res, Size.WORD);
        assertPal(true);
        assert240(false);
    }

    @Test
    public void testRegWrite240() {
        assertPal(false);
        assert240(false);
        int res = 0;

        //NTSC can't switch to 240
        res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
        res |= P32XV_240; //set to 240
        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, res, Size.WORD);
        assertPal(false);
        assert240(false);

        s32XMMREG.updateVideoMode(VideoMode.PAL_H40_V28);
        assertPal(true);
        assert240(false);

        //TODO is this used? if yes we need to update the videoMode as well
        //PAL switch to 240, via register
//        res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
//        res |= P32XV_240; //set to 240
//        s32XMMREG.write(MarsRegTestUtil.BITMAP_MODE_OFFSET, res, Size.WORD);
//        assertPal(true);
//        assert240(true);
    }

    private void assert240(boolean is240) {
        int res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
        Assertions.assertTrue((res & S32xDict.P32XV_240) == (is240 ? S32xDict.P32XV_240 : 0));
    }

    private void assertPal(boolean isPal) {
        int res = s32XMMREG.read(MarsRegTestUtil.BITMAP_MODE_OFFSET, Size.WORD);
        Assertions.assertTrue((res & P32XV_PAL) == (isPal ? 0 : P32XV_PAL));
    }

    private void assertFrameBufferDisplay(int num) {
        MarsRegTestUtil.assertFrameBufferDisplay(s32XMMREG, num);
    }

    private void assertVBlank(boolean on) {
        MarsRegTestUtil.assertVBlank(s32XMMREG, on);
    }
}
