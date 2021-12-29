package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.S32XMMREG;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static s32x.MarsRegTestUtil.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class VdpAutoFillTest {

    private S32XMMREG s32XMMREG;

    @BeforeEach
    public void before() {
        s32XMMREG = createInstance();
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
        int expected = -1555711615;
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
        int expected = -65896095;
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
        int expSar = (startAddr & 0xFF00) + ((len + startAddr) & 0xFF) + 1;
        int actLen = s32XMMREG.read(AFLEN_OFFSET, Size.WORD);
        int actSar = s32XMMREG.read(AFSAR_OFFSET, Size.WORD);
        Assertions.assertEquals(len, actLen);
        Assertions.assertEquals(expSar, actSar);
    }
}
