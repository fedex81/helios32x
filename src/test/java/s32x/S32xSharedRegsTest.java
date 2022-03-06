package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
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

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        s32XMMREG = createTestInstance().s32XMMREG;
    }

    @Test
    public void testFm() {
        int expFm, fm;
        checkFm(0);

        testFm(M68K);
        testFm(MASTER);
        testFm(SLAVE);
    }


    private void testFm(S32xUtil.CpuDeviceAccess sh2Access) {
        int expFm, fm;

        expFm = fm = 1;
        write(sh2Access, AD_CTRL, fm << 7, Size.BYTE);
        checkFm(expFm);

        expFm = fm = 0;
        write(sh2Access, AD_CTRL, fm << 7, Size.BYTE);
        checkFm(expFm);

        expFm = fm = 1;
        write(sh2Access, AD_CTRL, fm << 15, Size.WORD);
        checkFm(expFm);

        expFm = fm = 0;
        write(sh2Access, AD_CTRL, fm << 15, Size.WORD);
        checkFm(expFm);
    }

    @Test
    public void testCart() {
        int cartSize = 0x100;
        //defaults to 0
        checkCart(CART_INSERTED);

        //cart inserted
        s32XMMREG.setCart(cartSize);
        int exp = CART_INSERTED << 8;

        checkCart(exp);

        //cart removed, size = 0
        s32XMMREG.setCart(0);
        checkCart(CART_NOT_INSERTED << 8);
    }

    @Test
    public void testAden01_BYTE() {
        int aden, expAden;
        //defaults to 0
        checkAden(0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        write(M68K, AD_CTRL + 1, aden, Size.BYTE);
        checkAden(expAden);

        //m68k clears Aden, not supported
        aden = 0;
        expAden = 1;
        write(M68K, AD_CTRL + 1, aden, Size.BYTE);
        write(M68K, AD_CTRL, aden, Size.WORD);
        checkAden(expAden);
    }

    @Test
    public void testAden02_WORD() {
        int aden, expAden;
        //defaults to 0
        checkAden(0);

        //m68k sets Aden -> OK
        expAden = aden = 1;
        write(M68K, AD_CTRL, aden, Size.WORD);
        checkAden(expAden);
    }

    //TODO fix
    @Test
    public void testSh2SetAden() {
        Assumptions.assumeTrue(false);
        //defaults to 0
        checkAden(0);

        //sh2 sets Aden, not supported, read only
        int aden = 1;
        int expAden = 0;
        write(MASTER, INT_MASK, aden << 1, Size.BYTE);
        write(SLAVE, INT_MASK, aden << 1, Size.BYTE);
        checkAden(expAden);
    }

    private void checkFm(int exp) {
        Assertions.assertEquals(exp, (read(M68K, AD_CTRL, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(M68K, AD_CTRL, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (read(MASTER, INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(MASTER, INT_MASK, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (read(SLAVE, INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (read(SLAVE, INT_MASK, Size.BYTE) >> 7) & 1);
    }

    private void checkAden(int expAden) {
        Assertions.assertEquals(expAden, read(M68K, AD_CTRL, Size.WORD) & 1);
        Assertions.assertEquals(expAden, read(M68K, AD_CTRL + 1, Size.BYTE) & 1);
        Assertions.assertEquals(expAden, (read(MASTER, INT_MASK, Size.WORD) >> 9) & 1);
        Assertions.assertEquals(expAden, (read(MASTER, INT_MASK, Size.BYTE) >> 1) & 1);
        Assertions.assertEquals(expAden, (read(SLAVE, INT_MASK, Size.WORD) >> 9) & 1);
        Assertions.assertEquals(expAden, (read(SLAVE, INT_MASK, Size.BYTE) >> 1) & 1);
    }

    private void checkCart(int exp) {
        Assertions.assertEquals(exp, read(MASTER, INT_MASK, Size.WORD));
        Assertions.assertEquals(exp, read(SLAVE, INT_MASK, Size.WORD));
        Assertions.assertEquals(exp >> 8, read(MASTER, INT_MASK, Size.BYTE));
        Assertions.assertEquals(exp >> 8, read(SLAVE, INT_MASK, Size.BYTE));
    }

    private int read(S32xUtil.CpuDeviceAccess sh2Access, int reg, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        return s32XMMREG.read(reg, size);
    }

    private void write(S32xUtil.CpuDeviceAccess sh2Access, int reg, int value, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        s32XMMREG.write(reg, value, size);
    }
}
