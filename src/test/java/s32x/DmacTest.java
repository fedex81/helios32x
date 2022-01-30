package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sh2.DmaFifo68k;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.Sh2Memory;
import sh2.sh2.device.DmaC;

import java.util.Random;
import java.util.stream.IntStream;

import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.dict.S32xDict.RegSpecS32x.M68K_DMAC_CTRL;
import static sh2.dict.S32xDict.RegSpecS32x.M68K_FIFO_REG;
import static sh2.dict.Sh2Dict.RegSpec.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * //TODO see MD/SOURCE/DEMO.ASM.FEDE, 0x008809c6
 */
@Disabled
public class DmacTest {

    private S32XMMREG s32XMMREG;
    private int[] data = new int[0x80];

    public static final int FIFO_OFFSET = 0x4000 + M68K_FIFO_REG.addr;
    public static final int DMA_CONTROL_OFFSET = 0x4000 + M68K_DMAC_CTRL.addr;

    private static Random r = new Random(System.currentTimeMillis());

    static {
        long ms = System.currentTimeMillis();
        System.out.println("Seed: " + ms);
        r = new Random(ms);
    }

    @BeforeEach
    public void beforeEach() {
        s32XMMREG = MarsRegTestUtil.createInstance();
        IntStream.range(0, data.length).forEach(i -> data[i] = i);
    }

    @Test
    public void testMultiFifoTransfer() {
        do {
            testFifoTransfer01();
            testFifoTransfer01();
            testFifoTransfer02();
            testFifoTransfer02();
        } while (true);
    }

    @Test
    public void testFifoTransfer01() {
        DmaC masterDmac = s32XMMREG.dmaFifoControl.getDmac()[0];
        int idx = 0, cnt = 0;
        int spin = 0;
        setup68k();
        setupSh2(masterDmac, data.length);
        masterDmac.write(DMA_CHCR0, 0x44E1, Size.WORD);
        masterDmac.write(DMA_DMAOR, 1, Size.WORD);

        int pushFifo = r.nextInt(10) + 2;
        int limit = data.length * pushFifo;
        do {
            if ((cnt % pushFifo) == 0) {
                boolean full = isFifoFull();
                if (full) {
                    spin++;
                } else {
                    m68kWriteToFifo(data[idx++]);
                }
            }
            dmaStep(masterDmac);
            cnt++;
        } while (!isDmaDoneM68k() && spin < limit);
        Assertions.assertEquals(data.length, idx);
    }

    @Test
    public void testFifoTransfer02() {
        DmaC masterDmac = s32XMMREG.dmaFifoControl.getDmac()[0];
        int idx = 0, cnt = 0;
        int spin = 0;
        setup68k();
        setupSh2(masterDmac, data.length);
        int pushFifo = r.nextInt(10) + 2;
        System.out.println("#### " + pushFifo);
        pushFifo = 3;
        int limit = data.length * pushFifo;
        do {
            m68kWriteToFifo(data[idx++]);
        } while (!isFifoFull());
        masterDmac.write(DMA_CHCR0, 0x44E1, Size.WORD);
        masterDmac.write(DMA_DMAOR, 1, Size.WORD);
        do {
            if (idx < data.length && (cnt % pushFifo) == 0) {
                boolean full = isFifoFull();
                if (full) {
                    spin++;
                } else {
                    m68kWriteToFifo(data[idx++]);
                }
            }
            dmaStep(masterDmac);
            cnt++;
        } while (!isDmaDoneM68k() && spin < limit);
        Assertions.assertEquals(data.length, idx);
    }

    private void dmaStep(DmaC dmac) {
        Md32xRuntimeData.setAccessTypeExt(MASTER);
        dmac.step(1);
    }

    private void setup68k() {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        s32XMMREG.write(DMA_CONTROL_OFFSET, 4, Size.WORD); // 68S <- 1
    }

    private void setupSh2(DmaC dmac, int len) {
        dmac.write(DMA_CHCR0, 0, Size.WORD);
        dmac.write(DMA_CHCR0, 0x44E0, Size.WORD);
        dmac.write(DMA_TCR0, len, Size.LONG);
        dmac.write(DMA_SAR0, Sh2Memory.CACHE_THROUGH_OFFSET + FIFO_OFFSET, Size.LONG);
        dmac.write(DMA_DAR0, Sh2Memory.START_SDRAM, Size.LONG);
//        dmac.write(DMA_CHCR0, 0x44E1 ,Size.WORD);
//        dmac.write(DMA_DMAOR, 1 ,Size.WORD);
    }

    private void m68kWriteToFifo(int data) {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        s32XMMREG.write(FIFO_OFFSET, data, Size.WORD);
    }

    private boolean isDmaDoneM68k() {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        int val = s32XMMREG.read(DMA_CONTROL_OFFSET, Size.WORD);
        return (val >> DmaFifo68k.M68K_68S_BIT_POS & 1) == 0;
    }

    private boolean isFifoFull() {
        Md32xRuntimeData.setAccessTypeExt(M68K);
        int val = s32XMMREG.read(DMA_CONTROL_OFFSET, Size.WORD);
        //System.out.println(val);
        return (val >> DmaFifo68k.M68K_FIFO_FULL_BIT & 1) > 0;
    }
}
