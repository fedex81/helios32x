package sh2.sh2;

import sh2.S32xUtil;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceContext;
import sh2.sh2.drc.Sh2Block;

import java.util.Arrays;

import static omegadrive.util.Util.th;

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
    public boolean debug;
    public Sh2.FetchResult fetchResult;

    public Sh2DeviceContext devices;

    public Sh2Context(S32xUtil.CpuDeviceAccess cpuAccess) {
        this.registers = new int[NUM_REG];
        this.cpuAccess = cpuAccess;
        this.sh2TypeCode = cpuAccess.name().substring(0, 1);
        this.fetchResult = new Sh2.FetchResult();
        this.fetchResult.block = Sh2Block.INVALID_BLOCK;
    }

    @Override
    public String toString() {
        return "Sh2Context{" +
                "registers=" + Arrays.toString(registers) +
                ", GBR=" + th(GBR) +
                ", VBR=" + th(VBR) +
                ", SR=" + th(SR) +
                ", MACH=" + th(MACH) +
                ", MACL=" + th(MACL) +
                ", PR=" + th(PR) +
                ", PC=" + th(PC) +
                ", delayPC=" + delayPC +
                ", cycles=" + cycles +
                ", cycles_ran=" + cycles_ran +
                ", cpuAccess=" + cpuAccess +
                ", sh2TypeCode='" + sh2TypeCode + '\'' +
                ", delaySlot=" + delaySlot +
                ", debug=" + debug +
                '}';
    }
}
