package sh2.sh2;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s32x.MarsRegTestUtil;
import sh2.MarsLauncherHelper;
import sh2.sh2.device.FreeRunningTimer;

import static sh2.dict.Sh2Dict.RegSpec.FRT_OCRAB_H;
import static sh2.dict.Sh2Dict.RegSpec.FRT_TOCR;
import static sh2.sh2.device.FreeRunningTimer.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class FrtTest {
    private FreeRunningTimer frt;

    private static final int OCRS_OCRA = 0;
    private static final int OCRS_OCRB = 1;

    @BeforeEach
    public void before() {
        MarsLauncherHelper.Sh2LaunchContext lc = MarsRegTestUtil.createTestInstance();
        lc.s32XMMREG.aden = 1;
        frt = lc.mDevCtx.frt;
    }

    @Test
    public void testOcrab() {
        int res = frt.read(FRT_TOCR, Size.BYTE);
        Assertions.assertEquals(0, res & TOCR_OCRS_MASK); //ocra selected

        //set ocra
        int vala = 0xAABB;
        setOcrabValue(vala);

        //select OCRB
        selectOcr(false);

        //ocrab should be default
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertEquals(OCRAB_DEFAULT, res);

        //set ocrb
        int valb = 0xCCDD;
        setOcrabValue(valb);

        //select ocra
        selectOcr(true);

        //check ocra
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertEquals(vala, res);
    }

    @Test
    public void testOcrabReset() {
        int res;
        testOcrab();
        //both ocra and ocrb are != 0
        selectOcr(true);
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertNotEquals(0, res);
        selectOcr(false);
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertNotEquals(0, res);

        //reset
        frt.reset();

        //both ocra and ocrb are 0
        selectOcr(true);
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertEquals(OCRAB_DEFAULT, res);
        selectOcr(false);
        res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertEquals(OCRAB_DEFAULT, res);
    }

    private void setOcrabValue(int val) {
        frt.write(FRT_OCRAB_H, val, Size.WORD);
        int res = frt.read(FRT_OCRAB_H, Size.WORD);
        Assertions.assertEquals(val, res);
    }

    private void selectOcr(boolean ocra) {
        int val = (ocra ? 0 : 1) << TOCR_OCRS_BIT;
        frt.write(FRT_TOCR, val, Size.BYTE);
        int res = frt.read(FRT_TOCR, Size.BYTE);
        Assertions.assertEquals(val, res & TOCR_OCRS_MASK);
    }
}