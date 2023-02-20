package s32x.sh2;

import com.google.common.base.Objects;
import s32x.sh2.device.Sh2DeviceHelper.Sh2DeviceContext;
import s32x.sh2.drc.Sh2Block;
import s32x.util.S32xUtil;

import java.util.Arrays;
import java.util.StringJoiner;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Context {

    final static int NUM_REG = 16;
    public static int burstCycles = 1;

    /* System Registers */
    public final int registers[];

    public int GBR, VBR, SR;
    public int MACH, MACL, PR;
    public int PC;

    public int opcode, delayPC;

    /*
     * defines the number of cycles we can ran before stopping the interpreter
     */
    public int cycles;
    public int cycles_ran;

    public final S32xUtil.CpuDeviceAccess cpuAccess;
    public final String sh2TypeCode;
    public boolean delaySlot;
    public final boolean debug;
    public Sh2.FetchResult fetchResult;

    public Sh2DeviceContext devices;

    public Sh2Context(S32xUtil.CpuDeviceAccess cpuAccess) {
        this(cpuAccess, false);
    }

    public Sh2Context(S32xUtil.CpuDeviceAccess cpuAccess, boolean debug) {
        this.registers = new int[NUM_REG];
        this.cpuAccess = cpuAccess;
        this.sh2TypeCode = cpuAccess.name().substring(0, 1);
        this.fetchResult = new Sh2.FetchResult();
        this.fetchResult.block = Sh2Block.INVALID_BLOCK;
        this.debug = debug;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Sh2Context that = (Sh2Context) o;
        return GBR == that.GBR && VBR == that.VBR && SR == that.SR && MACH == that.MACH && MACL == that.MACL &&
                PR == that.PR && PC == that.PC &&
                delaySlot == that.delaySlot && cpuAccess == that.cpuAccess &&
                Objects.equal(sh2TypeCode, that.sh2TypeCode);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(GBR, VBR, SR, MACH, MACL, PR, PC, cpuAccess, sh2TypeCode, delaySlot);
    }


    @Override
    public String toString() {
        return new StringJoiner(", ", Sh2Context.class.getSimpleName() + "[", "]")
                .add("registers=" + Arrays.toString(registers))
                .add("GBR=" + GBR)
                .add("VBR=" + VBR)
                .add("SR=" + SR)
                .add("MACH=" + MACH)
                .add("MACL=" + MACL)
                .add("PR=" + PR)
                .add("PC=" + PC)
                .add("opcode=" + opcode)
                .add("delayPC=" + delayPC)
                .add("cycles=" + cycles)
                .add("cycles_ran=" + cycles_ran)
                .add("cpuAccess=" + cpuAccess)
                .add("sh2TypeCode='" + sh2TypeCode + "'")
                .add("delaySlot=" + delaySlot)
                .add("debug=" + debug)
                .add("fetchResult=" + fetchResult)
                .toString();
    }
}