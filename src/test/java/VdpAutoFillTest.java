import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;
import sh2.Sh2Util;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class VdpAutoFillTest {

    static int VDP_REG_OFFSET = 0x100;
    static int AFLEN_OFFSET = 0x4000 + VDP_REG_OFFSET + Sh2Util.AFLR;
    static int AFSAR_OFFSET = 0x4000 + VDP_REG_OFFSET + Sh2Util.AFSAR;

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        S32XMMREG.instance = null;
        s32XMMREG = new S32XMMREG();
    }

    @Test
    public void testAutoFill01() {
        byte[] b = new byte[0x800];
        Arrays.fill(b, (byte) -1);
        ByteBuffer buffer = ByteBuffer.wrap(b);
        int startAddr = 0x22A;
        int len = 0xE3;
        int data = 0xAA;
        s32XMMREG.runAutoFillInternal(buffer, startAddr, data, len);
        int expected = 792851489;
        Assertions.assertEquals(expected, Arrays.hashCode(b));
    }

    @Test
    public void testAutoFill02() {
        byte[] b = new byte[0x800];
        Arrays.fill(b, (byte) -1);
        ByteBuffer buffer = ByteBuffer.wrap(b);
        int startAddr = 0x280;
        int len = 0xE0;
        int data = 0xAA;
        s32XMMREG.runAutoFillInternal(buffer, startAddr, data, len);
        int expected = 506704897;
        Assertions.assertEquals(expected, Arrays.hashCode(b));
    }

    /**
     * according to docs the len register is not updated
     */
    @Test
    public void testAutoFillReg() {
        byte[] b = new byte[0x400];
        Arrays.fill(b, (byte) -1);
        ByteBuffer buffer = ByteBuffer.wrap(b);
        int startAddr = 0x1E0;
        int len = 0x30;
        int data = 0xAA;
        s32XMMREG.write(AFLEN_OFFSET, len, Size.WORD);
        s32XMMREG.write(AFSAR_OFFSET, startAddr, Size.WORD);
        s32XMMREG.runAutoFillInternal(buffer, startAddr, data, len);
        int expSar = (startAddr & 0xFF00) + ((len + startAddr) & 0xFF);
        int actLen = s32XMMREG.read(AFLEN_OFFSET, Size.WORD);
        int actSar = s32XMMREG.read(AFSAR_OFFSET, Size.WORD);
        Assertions.assertEquals(len, actLen);
        Assertions.assertEquals(expSar, actSar);
    }
}
