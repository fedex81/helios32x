package s32x.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.Sh2Memory;

import java.nio.ByteBuffer;

import static sh2.Sh2Memory.START_ROM;
import static sh2.Sh2Memory.START_ROM_CACHE;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class MemoryTest {

    Sh2Memory memory;

    @Test
    @Disabled("not needed for 32x??")
    public void testRomWrap() {
        Md32xRuntimeData.newInstance();
        ByteBuffer b = ByteBuffer.allocate(0xA0);
        for (int i = 0; i < b.capacity(); i++) {
            b.put(i, (byte) (i + 0xF));
        }
        memory = new Sh2Memory(new S32XMMREG(), b);
        Assertions.assertEquals(0xA0, memory.romSize);
        Assertions.assertEquals(0xFF, memory.romMask);
        int res = memory.read8i(START_ROM + 0xA0);
        int res1 = memory.read8i(START_ROM + 0);
        Assertions.assertEquals(res, res1);

        res = memory.read8i(START_ROM_CACHE + 0xA1);
        res1 = memory.read8i(START_ROM_CACHE + 1);
        Assertions.assertEquals(res, res1);
    }
}
