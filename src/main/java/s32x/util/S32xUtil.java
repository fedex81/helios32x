package s32x.util;

import com.google.common.math.IntMath;
import omegadrive.Device;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import org.slf4j.Logger;
import s32x.S32XMMREG;
import s32x.dict.S32xDict;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

import static omegadrive.util.Util.th;
import static omegadrive.vdp.model.BaseVdpProvider.H40;
import static s32x.dict.Sh2Dict.RegSpec;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xUtil {

    private static final Logger LOG = LogHelper.getLogger(S32xUtil.class.getSimpleName());

    public static final int[] EMPTY_INT_ARRAY = {};

    public static final boolean assertionsEnabled;

    static {
        boolean res = false;
        assert res = true;
        assertionsEnabled = res;
    }

    public static final boolean ENFORCE_FM_BIT_ON_READS = assertionsEnabled;

    public interface StepDevice extends Device {
        default void step(int cycles) {
        } //DO NOTHING
    }

    public interface Sh2Device extends StepDevice {
        void write(RegSpec regSpec, int pos, int value, Size size);

        int read(RegSpec regSpec, int reg, Size size);

        default void write(RegSpec regSpec, int value, Size size) {
            write(regSpec, regSpec.addr, value, size);
        }

        default int read(RegSpec regSpec, Size size) {
            return read(regSpec, regSpec.addr, size);
        }
    }

    public static void writeBuffers(ByteBuffer b1, ByteBuffer b2, int pos, int value, Size size) {
        writeBuffer(b1, pos, value, size);
        writeBuffer(b2, pos, value, size);
    }

    public static int readBufferLong(ByteBuffer b, RegSpec r) {
        return readBuffer(b, r.addr & RegSpec.REG_MASK, Size.LONG);
    }

    public static void writeBufferLong(ByteBuffer b, RegSpec r, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    public static void writeBuffersLong(ByteBuffer b, RegSpec r, RegSpec r1, RegSpec r2, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r1.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r2.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    public static void writeBuffersLong(ByteBuffer b, RegSpec r, RegSpec r1, int value) {
        writeBuffer(b, r.addr & RegSpec.REG_MASK, value, Size.LONG);
        writeBuffer(b, r1.addr & RegSpec.REG_MASK, value, Size.LONG);
    }

    private static final Set<String> s = new HashSet<>();
    public static boolean writeBufferHasChangedWithMask(S32xDict.RegSpecS32x regSpec, ByteBuffer b, int reg, int value, Size size) {
        //TODO slower, esp. Metal Head
        if (assertionsEnabled) {
            assert regSpec.size == Size.WORD;
            assert size != Size.LONG;
            int andMask = size == Size.WORD ? regSpec.writeAndMask : ((reg & 1) == 0) ? regSpec.writeAndMask >> 8 : regSpec.writeAndMask & 0xFF;
            int orMask = size == Size.WORD ? regSpec.writeOrMask : ((reg & 1) == 0) ? regSpec.writeOrMask >> 8 : regSpec.writeOrMask & 0xFF;
//            if (((value & andMask) | orMask) != value) {
//                String str = s + " pos: " + reg + " val: " + value + " masked: " + ((value & andMask) | orMask) +
//                        " " + size;
//                if (s.add(str)) {
//                    System.out.println(s);
//                }
//            }
            return writeBuffer(b, reg, (value & andMask) | orMask, size);
        } else {
            return writeBuffer(b, reg, value, size);
        }
    }

    public static void writeRegBuffer(RegSpec r, ByteBuffer b, int value, Size size) {
        writeBuffer(b, r.addr, value & r.writeMask, size);
    }

    public static final VarHandle INT_BYTEBUF_HANDLE = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    public static final VarHandle SHORT_BYTEBUF_HANDLE = MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);

    public static boolean writeBuffer(ByteBuffer b, int pos, int value, Size size) {
        boolean changed = false;
        if (size == Size.WORD) {
            if ((short) SHORT_BYTEBUF_HANDLE.get(b, pos) != value) {
                SHORT_BYTEBUF_HANDLE.set(b, pos, (short) value);
                changed = true;
            }
        } else if (size == Size.LONG) {
            if ((int) INT_BYTEBUF_HANDLE.get(b, pos) != value) {
                INT_BYTEBUF_HANDLE.set(b, pos, value);
                changed = true;
            }
        } else if (size == Size.BYTE) {
            if (b.get(pos) != value) {
                b.put(pos, (byte) value);
                changed = true;
            }
        }
        return changed;
    }

    public static int readBuffer(ByteBuffer b, int pos, Size size) {
        switch (size) {
            case WORD:
                assert (pos & 1) == 0;
                return (int) SHORT_BYTEBUF_HANDLE.get(b, pos);
            case LONG:
                assert (pos & 1) == 0;
                return (int) INT_BYTEBUF_HANDLE.get(b, pos);
            case BYTE:
                return b.get(pos);
            default:
                System.err.println("Unsupported size: " + size);
                return 0xFF;
        }
    }

    public static int readBufferByte(ByteBuffer b, int pos) {
        return b.get(pos);
    }

    public static int readBufferWord(ByteBuffer b, int pos) {
        assert (pos & 1) == 0;
        return (int) SHORT_BYTEBUF_HANDLE.get(b, pos);
    }

    public static int readBufferLong(ByteBuffer b, int pos) {
        assert (pos & 1) == 0;
        return (int) INT_BYTEBUF_HANDLE.get(b, pos);
    }

    public static void setBit(ByteBuffer b1, ByteBuffer b2, int pos, int bitPos, int bitValue, Size size) {
        setBit(b1, pos, bitPos, bitValue, size);
        setBit(b2, pos, bitPos, bitValue, size);
    }

    public static int getBitFromByte(byte b, int bitPos) {
        return (b >> bitPos) & 1;
    }

    /**
     * @return true - has changed, false - otherwise
     */
    public static boolean setBit(ByteBuffer b, int pos, int bitPos, int bitValue, Size size) {
        int val = readBuffer(b, pos, size);
        //clear bit and then set it
        int newVal = (val & ~(1 << bitPos)) | (bitValue << bitPos);
        if (val != newVal) {
            writeBuffer(b, pos, newVal, size);
            return true;
        }
        return false;
    }

    /**
     * @return the new value
     */
    public static int setBitVal(ByteBuffer b, int pos, int bitPos, int bitValue, Size size) {
        int val = readBuffer(b, pos, size);
        //clear bit and then set it
        int newVal = (val & ~(1 << bitPos)) | (bitValue << bitPos);
        if (val != newVal) {
            writeBuffer(b, pos, newVal, size);
            return newVal;
        }
        return val;
    }

    public static int readWordFromBuffer(S32XMMREG.RegContext ctx, S32xDict.RegSpecS32x reg) {
        return readBufferReg(ctx, reg, reg.addr, Size.WORD);
    }

    public static int readBufferReg(S32XMMREG.RegContext ctx, S32xDict.RegSpecS32x reg, int address, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == S32xDict.S32xRegType.VDP) {
            return readBuffer(ctx.vdpRegs, address, size);
        }
        switch (reg.regCpuType) {
            case REG_BOTH:
                assert readBuffer(ctx.sysRegsMd, address, size) == readBuffer(ctx.sysRegsSh2, address, size);
            case REG_MD:
                return readBuffer(ctx.sysRegsMd, address, size);
            case REG_SH2:
                return readBuffer(ctx.sysRegsSh2, address, size);
        }
        LOG.error("Unable to read buffer: {}, addr: {} {}", reg.name, th(address), size);
        return size.getMask();
    }

    public static void setBitReg(S32XMMREG.RegContext rc, S32xDict.RegSpecS32x reg, int address, int pos, int value, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == S32xDict.S32xRegType.VDP) {
            S32xUtil.setBit(rc.vdpRegs, address, pos, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH -> {
                S32xUtil.setBit(rc.sysRegsMd, rc.sysRegsSh2, address, pos, value, size);
                return;
            }
            case REG_MD -> {
                S32xUtil.setBit(rc.sysRegsMd, address, pos, value, size);
                return;
            }
            case REG_SH2 -> {
                S32xUtil.setBit(rc.sysRegsSh2, address, pos, value, size);
                return;
            }
        }
        LOG.error("Unable to setBit: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    public static void writeBufferReg(S32XMMREG.RegContext rc, S32xDict.RegSpecS32x reg, int address, int value, Size size) {
        address &= reg.addrMask;
        if (reg.deviceType == S32xDict.S32xRegType.VDP) {
            writeBuffer(rc.vdpRegs, address, value, size);
            return;
        }
        switch (reg.regCpuType) {
            case REG_BOTH -> {
                writeBuffer(rc.sysRegsMd, address, value, size);
                writeBuffer(rc.sysRegsSh2, address, value, size);
                return;
            }
            case REG_MD -> {
                writeBuffer(rc.sysRegsMd, address, value, size);
                return;
            }
            case REG_SH2 -> {
                writeBuffer(rc.sysRegsSh2, address, value, size);
                return;
            }
        }
        LOG.error("Unable to write buffer: {}, addr: {}, value: {} {}", reg.name, th(address), th(value), size);
    }

    public static String toHexString(ByteBuffer b, int pos, Size size) {
        return Integer.toHexString(readBuffer(b, pos, size));
    }

    public static void assertPowerOf2Minus1(String name, int value) {
        if (!IntMath.isPowerOfTwo(value + 1)) {
            LOG.error(name + " should be a (powerOf2 - 1), ie. 0xFF, actual: " + th(value - 1));
        }
        assert IntMath.isPowerOfTwo(value + 1) :
                name + " should be a (powerOf2 - 1), ie. 0xFF, actual: " + th(value - 1);
    }

    //duplicate every 4th pixel, 5*256/4 = 320
    public static void vidH32StretchToH40(VideoMode srcv, int[] src, int[] dest) {
        assert dest.length > src.length;
        assert srcv.getDimension().height * srcv.getDimension().width == src.length;
        assert srcv.getDimension().height * H40 == dest.length;
        int k = 0;
        final int h = srcv.getDimension().height;
        final int w = srcv.getDimension().width;
        for (int i = 0; i < h; i++) {
            int base = i * w;
            for (int j = 0; j < w; j += 4) {
                dest[k++] = src[base + j];
                dest[k++] = src[base + j + 1];
                dest[k++] = src[base + j + 2];
                dest[k++] = src[base + j + 3];
                dest[k++] = src[base + j + 3];
            }
        }
    }

    public static int hashCode(int a[], int len) {
        if (a == null)
            return 0;

        int result = 1;
        for (int i = 0; i < len; i++) {
            result = 31 * result + a[i];
        }

        return result;
    }

    public enum S32xRegSide {MD, SH2}

    public enum CpuDeviceAccess {
        MASTER, SLAVE, M68K, Z80;

        public final S32xRegSide regSide;

        CpuDeviceAccess() {
            this.regSide = this.ordinal() < 2 ? S32xRegSide.SH2 : S32xRegSide.MD;
        }

        public static final CpuDeviceAccess[] cdaValues = CpuDeviceAccess.values();
    }
}