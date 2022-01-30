package s32x;

import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import sh2.*;
import sh2.sh2.device.DmaC;
import sh2.sh2.device.IntControl;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.RegSpecS32x.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsRegTestUtil {

    public static final int VDP_REG_OFFSET = 0x100;
    public static final int FBCR_OFFSET = 0x4000 + VDP_REG_OFFSET + FBCR.addr;
    public static final int BITMAP_MODE_OFFSET = 0x4000 + VDP_REG_OFFSET + VDP_BITMAP_MODE.addr;
    public static final int INT_MASK = 0x4000 + SH2_INT_MASK.addr;
    public static final int AD_CTRL = INT_MASK;
    public static int AFLEN_OFFSET = 0x4000 + VDP_REG_OFFSET + AFLR.addr;
    public static int AFSAR_OFFSET = 0x4000 + VDP_REG_OFFSET + AFSAR.addr;

    static {
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    public static S32XMMREG createInstance() {
        S32XMMREG s = new S32XMMREG();
        Sh2Memory memory = new Sh2Memory(s, ByteBuffer.allocate(0xFFF));
        DmaFifo68k dmaFifoControl = new DmaFifo68k(s);
        s.setDmaControl(dmaFifoControl);
        Sh2MMREG sm = new Sh2MMREG(MASTER);
        Sh2MMREG ss = new Sh2MMREG(SLAVE);
        s.setInterruptControl(createIntC(MASTER, sm.getRegs()), createIntC(SLAVE, ss.getRegs()));
        DmaC d1 = createDmaC(MASTER, memory, s.interruptControls[0], dmaFifoControl, sm);
        DmaC d2 = createDmaC(SLAVE, memory, s.interruptControls[1], dmaFifoControl, ss);
        dmaFifoControl.setDmac(d1, d2);
        Md32xRuntimeData.releaseInstance();
        Md32xRuntimeData.newInstance();
        return s;
    }

    public static DmaC createDmaC(S32xUtil.CpuDeviceAccess cpuDeviceAccess, Sh2Memory memory, IntControl intc, DmaFifo68k dmaFifo, Sh2MMREG s) {
        return new DmaC(cpuDeviceAccess, intc, memory, dmaFifo, s.getRegs());
    }

    public static IntControl createIntC(S32xUtil.CpuDeviceAccess cpuDeviceAccess) {
        return createIntC(cpuDeviceAccess, null);
    }

    public static IntControl createIntC(S32xUtil.CpuDeviceAccess cpuDeviceAccess, ByteBuffer regs) {
        return new IntControl(cpuDeviceAccess, regs);
    }

    public static void assertFrameBufferDisplay(S32XMMREG s32XMMREG, int num) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public static void assertPEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.BYTE);
        Assertions.assertEquals(enable ? 1 : 0, (res >> 5) & 1);
    }

    public static void assertFEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(enable ? 0 : 1, (res >> 1) & 1);
    }

    public static void assertVBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, res >> 15);
    }

    public static void assertHBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, (res >> 14) & 1);
    }
}
