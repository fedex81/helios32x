package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil;

import static s32x.MarsRegTestUtil.*;
import static sh2.S32XMMREG.CART_INSERTED;
import static sh2.S32XMMREG.CART_NOT_INSERTED;
import static sh2.S32xUtil.CpuDeviceAccess.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xSharedRegsTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;

    @BeforeEach
    public void before() {
        lc = createTestInstance();
    }

    @Test
    public void testFm() {
        int expFm, fm;
        checkFm(lc, 0);

        testFm(M68K, MD_ADAPTER_CTRL);
        testFm(MASTER, SH2_INT_MASK);
        testFm(SLAVE, SH2_INT_MASK);
    }


    private void testFm(S32xUtil.CpuDeviceAccess sh2Access, int reg) {
        int expFm, fm;

        expFm = fm = 1;
        writeBus(lc, sh2Access, reg, fm << 7, Size.BYTE);
        checkFm(lc, expFm);

        expFm = fm = 0;
        writeBus(lc, sh2Access, reg, fm << 7, Size.BYTE);
        checkFm(lc, expFm);

        expFm = fm = 1;
        writeBus(lc, sh2Access, reg, fm << 15, Size.WORD);
        checkFm(lc, expFm);

        expFm = fm = 0;
        writeBus(lc, sh2Access, reg, fm << 15, Size.WORD);
        checkFm(lc, expFm);
    }

    @Test
    public void testCart() {
        int cartSize = 0x100;
        //defaults to 0
        checkCart(lc, CART_INSERTED);

        //cart inserted
        lc.s32XMMREG.setCart(cartSize);
        int exp = CART_INSERTED << 8;

        checkCart(lc, exp);

        //cart removed, size = 0
        lc.s32XMMREG.setCart(0);
        checkCart(lc, CART_NOT_INSERTED << 8);
    }

    @Test
    public void testAden01_BYTE() {
        int aden, expAden;
        //defaults to 0
        checkAden(lc, 0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        writeBus(lc, M68K, MD_ADAPTER_CTRL + 1, aden, Size.BYTE);
        checkAden(lc, expAden);

        //m68k clears Aden, not supported
        aden = 0;
        expAden = 1;
        writeBus(lc, M68K, MD_ADAPTER_CTRL + 1, aden, Size.BYTE);
        writeBus(lc, M68K, MD_ADAPTER_CTRL, aden, Size.WORD);
        checkAden(lc, expAden);
    }

    @Test
    public void testAden02_WORD() {
        int aden, expAden;
        //defaults to 0
        checkAden(lc, 0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        writeBus(lc, M68K, MD_ADAPTER_CTRL, aden, Size.WORD);
        checkAden(lc, expAden);
    }

    //TODO fix
    @Test
    public void testSh2SetAden() {
        Assumptions.assumeTrue(false);
        //defaults to 0
        checkAden(lc, 0);

        //sh2 sets Aden, not supported, read only
        int aden = 1;
        int expAden = 0;
        writeBus(lc, MASTER, SH2_INT_MASK, aden << 1, Size.BYTE);
        writeBus(lc, SLAVE, SH2_INT_MASK, aden << 1, Size.BYTE);
        checkAden(lc, expAden);
    }
}
