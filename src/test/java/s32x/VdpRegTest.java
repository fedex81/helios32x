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
public class VdpRegTest {

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        s32XMMREG = MarsRegTestUtil.createInstance();
    }

    //palette enable bit#13
    @Test
    public void testPEN() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, 0, Size.WORD);
        MarsRegTestUtil.assertPEN(s32XMMREG, false);
        s32XMMREG.setVBlankOn(false);

        s32XMMREG.setHBlankOn(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setHBlankOn(false);
        MarsRegTestUtil.assertPEN(s32XMMREG, false);

        s32XMMREG.setVBlankOn(true);
        s32XMMREG.setHBlankOn(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlankOn(true);
        s32XMMREG.setHBlankOn(false);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlankOn(false);
        s32XMMREG.setHBlankOn(true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);

        s32XMMREG.setVBlankOn(false);
        s32XMMREG.setHBlankOn(false);
        MarsRegTestUtil.assertPEN(s32XMMREG, false);
    }

    @Test
    public void testFBCR_ReadOnly() {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.MASTER;
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, 0, Size.WORD);

        s32XMMREG.setVBlankOn(true);
        s32XMMREG.setHBlankOn(true);
        s32XMMREG.write(MarsRegTestUtil.FBCR_OFFSET, 0, Size.WORD);
        MarsRegTestUtil.assertVBlank(s32XMMREG, true);
        MarsRegTestUtil.assertHBlank(s32XMMREG, true);
        MarsRegTestUtil.assertPEN(s32XMMREG, true);
    }
}
