package sh2.sh2.device;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import s32x.MarsRegTestUtil;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.sh2.device.DmaHelper.DmaChannelSetup;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.dict.Sh2Dict.RegSpec.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 */
public class DmaCTest {

    private static final Logger LOG = LogHelper.getLogger(DmaCTest.class.getSimpleName());

    private S32XMMREG s32XMMREG;
    private DmaC masterDmac;

    //Metal Head, RBI baseball, Sangokushi 0x603d6dc
    @Test
    public void testDreqSarNonCached() {
        testDreqNonCachedInternal(0x4012, 0x2601d800);
        testDreqNonCachedInternal(0x6002a50, 0x24000200);
        testDreqNonCachedInternal(0x603d6dc, 0x603ffbc);
    }

    //Zaxxon, Knuckles, RBI Baseball, FIFA 96, Mars Check v2
    @Test
    public void testDreqDarNonCached() {
        testDreqNonCachedInternal(0x2000_4012, 0x6001220);
    }

    private void testDreqNonCachedInternal(int sar) {
        testDreqNonCachedInternal(sar, 0x600_0000);
    }

    private void testDreqNonCachedInternal(int sar, int dar) {
        s32XMMREG = MarsRegTestUtil.createTestInstance().s32XMMREG;
        masterDmac = s32XMMREG.dmaFifoControl.getDmac()[MASTER.ordinal()];
        int dmaLen = 1;
        int channel = 0;
        DmaChannelSetup c = masterDmac.getDmaChannelSetup()[channel];
        setupAndStartChannel(c, dmaLen, sar, dar);
        c.chcr_dmaEn = c.dmaor_dme = true;
        c.chcr_tranEndOk = false;
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        int len = Integer.MAX_VALUE;

        do {
            masterDmac.dmaReqTrigger(channel, true);
            masterDmac.step(1);
            len = masterDmac.read(DMA_TCR0, Size.LONG);
        } while (len > 0);
    }

    private void setupAndStartChannel(DmaChannelSetup c, int dmaLen, int sar, int dar) {
        masterDmac.write(DMA_TCR0, dmaLen, Size.LONG);
        masterDmac.write(DMA_SAR0, sar, Size.LONG);
        masterDmac.write(DMA_DAR0, dar, Size.LONG);
        c.chcr_dmaEn = c.dmaor_dme = true;
        c.chcr_tranEndOk = false;
        c.trnSize = Size.WORD;
    }
}