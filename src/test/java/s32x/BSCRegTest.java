package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.MarsLauncherHelper;
import sh2.S32xUtil;
import sh2.Sh2MMREG;
import sh2.dict.Sh2Dict;

import static s32x.MarsRegTestUtil.createTestInstance;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.Sh2Dict.BSC_LONG_WRITE_MASK;
import static sh2.dict.Sh2Dict.RegSpec.BSC_BCR1;
import static sh2.dict.Sh2Dict.RegSpec.BSC_BCR2;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class BSCRegTest {

    private MarsLauncherHelper.Sh2LaunchContext lc;

    @BeforeEach
    public void before() {
        lc = createTestInstance();
    }

    @Test
    public void testBsc() {
        testBscInternal(MASTER);
        testBscInternal(SLAVE);
    }

    private void testBscInternal(S32xUtil.CpuDeviceAccess cpu) {
        System.out.println(cpu);
        Sh2MMREG s = cpu == MASTER ? lc.mDevCtx.sh2MMREG : lc.sDevCtx.sh2MMREG;
        int bcr1Mask = cpu.ordinal() << 15;
        int res;
        res = readLong(s, BSC_BCR1);
        Assertions.assertEquals(bcr1Mask | 0x3f0, res);
        res = readLong(s, BSC_BCR2);
        Assertions.assertEquals(0xfc, res);

        res = readWord(s, BSC_BCR1);
        Assertions.assertEquals(bcr1Mask | 0x3f0, res);
        res = readWord(s, BSC_BCR2);
        Assertions.assertEquals(0xfc, res);

        res = readWord(s, BSC_BCR1);
        s.write(BSC_BCR1.addr, 1, Size.LONG); //ignored
        Assertions.assertEquals(res, readWord(s, BSC_BCR1));

        s.write(BSC_BCR1.addr, 1, Size.BYTE); //ignored
        Assertions.assertEquals(res, readWord(s, BSC_BCR1));

        s.write(BSC_BCR1.addr, 1, Size.WORD); //ignored
        Assertions.assertEquals(res, readWord(s, BSC_BCR1));

        s.write(BSC_BCR1.addr, BSC_LONG_WRITE_MASK | 1, Size.LONG);
        res = readWord(s, BSC_BCR1);
        Assertions.assertEquals(bcr1Mask | 1, res);

        res = readLong(s, BSC_BCR1);
        Assertions.assertEquals(bcr1Mask | 1, res);
    }

    private int readLong(Sh2MMREG s, Sh2Dict.RegSpec r) {
        return s.read(r.addr, Size.LONG);
    }

    private int readWord(Sh2MMREG s, Sh2Dict.RegSpec r) {
        return s.read(r.addr + 2, Size.WORD);
    }
}
