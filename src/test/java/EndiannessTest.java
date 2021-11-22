import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sh2.Sh2Util;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Both 68k and Sh2s are big endian
 * <p>
 * Copyright 2021
 */
public class EndiannessTest {

    static int numBytes = 4;

    private ByteBuffer reset(int numEl) {
        return ByteBuffer.allocate(numEl);
    }

    @Test
    public void testLong() {
        ByteBuffer b = reset(numBytes);
        byte valB, actB;
        int valW, valL, actW, actL;

        //positive, pos 0
        b = reset(numBytes);
        valL = 0x11223344;
        Sh2Util.writeBuffer(b, 0, valL, Size.LONG);
        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        Assertions.assertEquals(valL, actL);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = valL >> 16;
        Assertions.assertEquals(valW, actW);

        actW = Sh2Util.readBuffer(b, 2, Size.WORD);
        valW = valL & 0xFFFF;
        Assertions.assertEquals(valW, actW);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals(valL >> 24, actB);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals((valL >> 16) & 0xFF, actB);

        actB = (byte) Sh2Util.readBuffer(b, 2, Size.BYTE);
        Assertions.assertEquals((valL >> 8) & 0xFF, actB);

        actB = (byte) Sh2Util.readBuffer(b, 3, Size.BYTE);
        Assertions.assertEquals((valL >> 0) & 0xFF, actB);

        //negative, pos 0
        b = reset(numBytes);
        valL = -0x55667788;
        Sh2Util.writeBuffer(b, 0, valL, Size.LONG);
        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        Assertions.assertEquals(valL, actL);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = (valL >> 16) & 0xFFFF;
        Assertions.assertEquals(valW, actW);

        actW = Sh2Util.readBuffer(b, 2, Size.WORD);
        valW = valL & 0xFFFF;
        Assertions.assertEquals(valW, actW);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        byte expB = (byte) ((valL >> 24) & 0xFF);
        Assertions.assertEquals(expB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        expB = (byte) ((valL >> 16) & 0xFF);
        Assertions.assertEquals(expB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 2, Size.BYTE);
        expB = (byte) ((valL >> 8) & 0xFF);
        Assertions.assertEquals(expB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 3, Size.BYTE);
        expB = (byte) ((valL >> 0) & 0xFF);
        Assertions.assertEquals(expB, actB);
    }

    @Test
    public void testByte() {
        int numByte = 4;
        ByteBuffer b = ByteBuffer.allocate(numByte);
        byte valB, actB;
        int valW, valL, actW, actL;

        //positive, pos 0
        b = ByteBuffer.allocate(numByte);
        valB = 0x11;
        Sh2Util.writeBuffer(b, 0, valB, Size.BYTE);
        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals(valB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals(valB >> 8, actB);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = valB << 8;
        Assertions.assertEquals(valW, actW);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = valB << 24;
        Assertions.assertEquals(valL, actL);

        //negative, pos 0
        b = ByteBuffer.allocate(numByte);
        valB = -0x11;
        Sh2Util.writeBuffer(b, 0, valB, Size.BYTE);
        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals(valB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals((valB << 8) & 0xFF, actB);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = (valB << 8) & 0xFFFF;
        Assertions.assertEquals(valW, actW);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = valB << 24;
        Assertions.assertEquals(valL, actL);

        //positive, pos 1
        b = ByteBuffer.allocate(numByte);
        valB = 0x22;
        Sh2Util.writeBuffer(b, 1, valB, Size.BYTE);
        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals(valB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals((valB << 8) & 0xFF, actB);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = valB;
        Assertions.assertEquals(valW, actW);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = valB << 16;
        Assertions.assertEquals(valL, actL);

        //negative, pos 1
        b = ByteBuffer.allocate(numByte);
        valB = -0x22;
        Sh2Util.writeBuffer(b, 1, valB, Size.BYTE);
        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals(valB, actB);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals((valB << 8) & 0xFF, actB);

        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        valW = valB & 0xFF;
        Assertions.assertEquals(valW, actW);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = (valB & 0xFF) << 16;
        Assertions.assertEquals(valL, actL);
    }

    @Test
    public void testWord() {
        int numByte = 4;
        ByteBuffer b = ByteBuffer.allocate(numByte);
        byte valB, actB;
        int valW, valL, actW, actL;

        //positive, pos 0
        b = ByteBuffer.allocate(numByte);
        valW = 0x1122;
        Sh2Util.writeBuffer(b, 0, valW, Size.WORD);
        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        Assertions.assertEquals(valW, actW);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals((valW & 0xFFFF) >> 8, actB);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals((valW & 0xFF), actB);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = valW << 16;
        Assertions.assertEquals(valL, actL);

        //negative, pos 0
        b = ByteBuffer.allocate(numByte);
        valW = -0x3344;
        Sh2Util.writeBuffer(b, 0, valW, Size.WORD);
        actW = Sh2Util.readBuffer(b, 0, Size.WORD);
        Assertions.assertEquals(valW & 0xFFFF, actW);

        actB = (byte) Sh2Util.readBuffer(b, 0, Size.BYTE);
        Assertions.assertEquals((valW & 0xFFFF) >> 8, actB & 0xFF);

        actB = (byte) Sh2Util.readBuffer(b, 1, Size.BYTE);
        Assertions.assertEquals((valW & 0xFF), actB & 0xFF);

        actL = Sh2Util.readBuffer(b, 0, Size.LONG);
        valL = valW << 16;
        Assertions.assertEquals(valL, actL);
    }
}
