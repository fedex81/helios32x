package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class VdpRegTest {

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        s32XMMREG = MarsRegTestUtil.createTestInstance().s32XMMREG;
    }

    //palette enable bit#13
    @Test
    public void testPEN() {
        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 0, Size.WORD);
        //startup, vblankOn, pen= true
        MarsRegTestUtil.assertPEN(s32XMMREG, true);
        s32XMMREG.setVBlank(true);

        s32XMMREG.setHBlank(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setHBlank(false); //vblank on
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlank(true);
        s32XMMREG.setHBlank(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlank(true);
        s32XMMREG.setHBlank(false);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlank(false);
        s32XMMREG.setHBlank(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlank(false);
        s32XMMREG.setHBlank(false);
        MarsRegTestUtil.assertPEN(s32XMMREG, false);
    }

    /**
     * Frame Buffer Authorization, bit#1
     * 0: Access approved
     * 1: Access denied
     * <p>
     * When having performed FILL, be sure to access the Frame Buffer after
     * confirming that FEN is equal to 0.
     * <p>
     * ie. when the vdp is accessing the FB, FEN = 1
     * dram refresh, FEN=1
     */
    @Test
    public void testFEN() {
        int res = s32XMMREG.read(MarsRegTestUtil.SH2_FBCR_OFFSET, Size.WORD);
        MarsRegTestUtil.assertFEN(s32XMMREG, 0);

        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 2, Size.WORD);
        MarsRegTestUtil.assertFEN(s32XMMREG, 1);

        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 0, Size.WORD);
        MarsRegTestUtil.assertFEN(s32XMMREG, 0);
    }

    @Test
    public void testFEN2() {
        int res = s32XMMREG.read(MarsRegTestUtil.SH2_FBCR_OFFSET, Size.WORD);
        MarsRegTestUtil.assertFEN(s32XMMREG, 0);

        //set FEN=1, access denied
        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 2, Size.WORD);
        MarsRegTestUtil.assertFEN(s32XMMREG, 1);

        //toggle hblank
        s32XMMREG.setHBlank(true);
        s32XMMREG.setHBlank(false);

        //fen should go back to 0
        MarsRegTestUtil.assertFEN(s32XMMREG, 0);
    }

    @Test
    public void testFBCR_ReadOnly() {
        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 0, Size.WORD);

        s32XMMREG.setVBlank(true);
        s32XMMREG.setHBlank(true);
        s32XMMREG.write(MarsRegTestUtil.SH2_FBCR_OFFSET, 0, Size.WORD);
        MarsRegTestUtil.assertVBlank(s32XMMREG, true);
        MarsRegTestUtil.assertHBlank(s32XMMREG, true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);
    }
}
