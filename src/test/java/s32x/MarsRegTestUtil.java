package s32x;

import omegadrive.cart.MdCartInfoProvider;
import omegadrive.memory.IMemoryProvider;
import omegadrive.memory.MemoryProvider;
import omegadrive.util.RomHolder;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.junit.jupiter.api.Assertions;
import s32x.util.SystemTestUtil;
import sh2.*;
import sh2.MarsLauncherHelper.Sh2LaunchContext;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.dict.S32xDict;

import java.nio.ByteBuffer;
import java.util.Random;

import static sh2.S32xBus.START_32X_SYSREG;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.dict.S32xDict.START_32X_SYSREG_CACHE;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsRegTestUtil {

    public static final int VDP_REG_OFFSET = 0x100;
    public static final int SH2_FBCR_OFFSET = START_32X_SYSREG_CACHE + VDP_REG_OFFSET + FBCR.addr;
    public static final int SH2_BITMAP_MODE_OFFSET = START_32X_SYSREG_CACHE + VDP_REG_OFFSET + VDP_BITMAP_MODE.addr;
    public static final int SH2_INT_MASK = START_32X_SYSREG_CACHE + S32xDict.RegSpecS32x.SH2_INT_MASK.addr;
    public static final int MD_ADAPTER_CTRL = START_32X_SYSREG + M68K_ADAPTER_CTRL.fullAddress;
    public static int SH2_AFLEN_OFFSET = START_32X_SYSREG_CACHE + VDP_REG_OFFSET + AFLR.addr;
    public static int SH2_AFSAR_OFFSET = START_32X_SYSREG_CACHE + VDP_REG_OFFSET + AFSAR.addr;
    private static final int MD_DMAC_CTRL = START_32X_SYSREG + M68K_DMAC_CTRL.fullAddress;
    private static final int SH2_DREQ_CTRL = START_32X_SYSREG_CACHE + S32xDict.RegSpecS32x.SH2_DREQ_CTRL.fullAddress;

    static {
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    public static byte[] fillAsMdRom(byte[] rom, boolean random) {
        if (random) {
            long seed = System.currentTimeMillis();
            System.out.println("Seed: " + seed);
            Random rnd = new Random(seed);
            for (int i = 0; i < rom.length; i++) {
                rom[i] = (byte) (1 + rnd.nextInt(0xFE)); //avoid 0 and 0xFF
            }
        }
        System.arraycopy("SEGA MEGADRIVE  ".getBytes(), 0, rom, MdCartInfoProvider.ROM_HEADER_START, 16);
        System.arraycopy("  ".getBytes(), 0, rom, MdCartInfoProvider.SVP_SV_TOKEN_ADDRESS, 2);
        return rom;
    }

    public static Sh2LaunchContext createTestInstance() {
        return createTestInstance(new byte[0x1000]);
    }

    public static Sh2LaunchContext createTestInstance(byte[] brom) {
        Md32xRuntimeData.releaseInstance();
        Md32xRuntimeData.newInstance();
        RomHolder romHolder = new RomHolder(Util.toUnsignedIntArray(brom));
        Sh2LaunchContext lc = MarsLauncherHelper.setupRom(new S32xBus(), romHolder, createTestBiosHolder());

        int[] irom = Util.toSignedIntArray(brom);
        IMemoryProvider mp = MemoryProvider.createGenesisInstance();
        mp.setRomData(irom);
        SystemTestUtil.setupNewMdSystem(lc.bus, mp);
        return lc;
    }

    private static BiosHolder createTestBiosHolder() {
        ByteBuffer[] biosData = new ByteBuffer[CpuDeviceAccess.values().length];
        ByteBuffer data = ByteBuffer.allocate(0x1000);

        biosData[0] = data;
        biosData[1] = data;
        biosData[2] = data;
        return new BiosHolder(biosData);
    }

    public static int readBus(Sh2LaunchContext lc, CpuDeviceAccess sh2Access, int reg, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        if (sh2Access == M68K) {
            return (int) lc.bus.read(reg, size);
        } else {
            return lc.memory.read(reg, size);
        }
    }

    public static void writeBus(Sh2LaunchContext lc, CpuDeviceAccess sh2Access, int reg, int data, Size size) {
        Md32xRuntimeData.setAccessTypeExt(sh2Access);
        if (sh2Access == M68K) {
            lc.bus.write(reg, data, size);
        } else {
            lc.memory.write(reg, data, size);
        }
    }

    public static void assertFrameBufferDisplay(S32XMMREG s32XMMREG, int num) {
        int res = s32XMMREG.read(SH2_FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(num, res & 1);
    }

    public static void assertPEN(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(SH2_FBCR_OFFSET, Size.BYTE);
        Assertions.assertEquals(enable ? 1 : 0, (res >> 5) & 1);
    }

    public static void assertFEN(S32XMMREG s32XMMREG, int value) {
        int res = s32XMMREG.read(SH2_FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(value, (res >> 1) & 1);
    }

    public static void assertPRIO(S32XMMREG s32XMMREG, boolean enable) {
        int res = s32XMMREG.read(SH2_BITMAP_MODE_OFFSET, Size.WORD);
        Assertions.assertEquals(enable ? 1 : 0, (res >> 7) & 1);
    }

    public static void assertVBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(SH2_FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, res >> 15);
    }

    public static void assertHBlank(S32XMMREG s32XMMREG, boolean on) {
        int res = s32XMMREG.read(SH2_FBCR_OFFSET, Size.WORD);
        Assertions.assertEquals(on ? 1 : 0, (res >> 14) & 1);
    }

    public static void setFmAccess(Sh2LaunchContext lc, int fm) {
        int val = readBus(lc, MASTER, SH2_INT_MASK, Size.WORD) & 0x7FFF;
        writeBus(lc, MASTER, SH2_INT_MASK, (fm << 15) | val, Size.WORD);
        checkFm(lc, fm);
    }

    public static void setRv(Sh2LaunchContext lc, int rv) {
        int val = readBus(lc, M68K, MD_DMAC_CTRL, Size.WORD) & 0xFFFE;
        writeBus(lc, M68K, MD_DMAC_CTRL, (rv & 1) | val, Size.WORD);
        checkRv(lc, rv);
    }

    public static void checkFm(Sh2LaunchContext lc, int exp) {
        Assertions.assertEquals(exp, (readBus(lc, M68K, MD_ADAPTER_CTRL, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (readBus(lc, M68K, MD_ADAPTER_CTRL, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (readBus(lc, MASTER, SH2_INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (readBus(lc, MASTER, SH2_INT_MASK, Size.BYTE) >> 7) & 1);
        Assertions.assertEquals(exp, (readBus(lc, SLAVE, SH2_INT_MASK, Size.WORD) >> 15) & 1);
        Assertions.assertEquals(exp, (readBus(lc, SLAVE, SH2_INT_MASK, Size.BYTE) >> 7) & 1);
    }

    public static void checkRv(Sh2LaunchContext lc, int exp) {
        Assertions.assertEquals(exp, readBus(lc, M68K, MD_DMAC_CTRL, Size.WORD) & 1);
        Assertions.assertEquals(exp, readBus(lc, M68K, MD_DMAC_CTRL + 1, Size.BYTE) & 1);
        Assertions.assertEquals(exp, readBus(lc, MASTER, SH2_DREQ_CTRL, Size.WORD) & 1);
        Assertions.assertEquals(exp, readBus(lc, MASTER, SH2_DREQ_CTRL + 1, Size.BYTE) & 1);
        Assertions.assertEquals(exp, readBus(lc, SLAVE, SH2_DREQ_CTRL, Size.WORD) & 1);
        Assertions.assertEquals(exp, readBus(lc, SLAVE, SH2_DREQ_CTRL + 1, Size.BYTE) & 1);
    }

    public static void checkAden(Sh2LaunchContext lc, int expAden) {
        Assertions.assertEquals(expAden, readBus(lc, M68K, MD_ADAPTER_CTRL, Size.WORD) & 1);
        Assertions.assertEquals(expAden, readBus(lc, M68K, MD_ADAPTER_CTRL + 1, Size.BYTE) & 1);
        Assertions.assertEquals(expAden, (readBus(lc, MASTER, SH2_INT_MASK, Size.WORD) >> 9) & 1);
        Assertions.assertEquals(expAden, (readBus(lc, MASTER, SH2_INT_MASK, Size.BYTE) >> 1) & 1);
        Assertions.assertEquals(expAden, (readBus(lc, SLAVE, SH2_INT_MASK, Size.WORD) >> 9) & 1);
        Assertions.assertEquals(expAden, (readBus(lc, SLAVE, SH2_INT_MASK, Size.BYTE) >> 1) & 1);
    }

    public static void checkCart(Sh2LaunchContext lc, int exp) {
        Assertions.assertEquals(exp, readBus(lc, MASTER, SH2_INT_MASK, Size.WORD));
        Assertions.assertEquals(exp, readBus(lc, SLAVE, SH2_INT_MASK, Size.WORD));
        Assertions.assertEquals(exp >> 8, readBus(lc, MASTER, SH2_INT_MASK, Size.BYTE));
        Assertions.assertEquals(exp >> 8, readBus(lc, SLAVE, SH2_INT_MASK, Size.BYTE));
    }

    public static void checkHen(Sh2LaunchContext lc, int exp) {
        Assertions.assertEquals(exp, readBus(lc, MASTER, SH2_INT_MASK, Size.WORD));
        Assertions.assertEquals(exp, readBus(lc, SLAVE, SH2_INT_MASK, Size.WORD));
        Assertions.assertEquals(exp, readBus(lc, MASTER, SH2_INT_MASK + 1, Size.BYTE));
        Assertions.assertEquals(exp, readBus(lc, SLAVE, SH2_INT_MASK + 1, Size.BYTE));
    }
}
