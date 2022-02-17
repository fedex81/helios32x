package sh2.sh2;

import sh2.S32xUtil;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceContext;

import java.util.Arrays;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Context {

    final static int NUM_REG = 16;
    public static int burstCycles = 1;

    /* System Registers */
    public int registers[];

    /* Flags to access the bit fields in SR and FPSCR
     * Sintax : flagRegName
     */
    public int GBR, VBR, SR;
    public int MACH, MACL, PR;
    public int PC;

    public int opcode, delayPC;

    /*
     * defines the number of cycles we can ran before stopping the interpreter
     */
    public int cycles;
    public int cycles_ran;

    public S32xUtil.CpuDeviceAccess cpuAccess;
    public String sh2TypeCode;
    public boolean delaySlot;
    public boolean clearNextInt;
    public boolean debug;

    public Sh2DeviceContext devices;
    public int[] prefetchPc = new int[1];

    public Sh2Context(S32xUtil.CpuDeviceAccess cpuAccess) {
        this.registers = new int[NUM_REG];
        this.cpuAccess = cpuAccess;
        this.sh2TypeCode = cpuAccess.name().substring(0, 1);
    }

    @Override
    public String toString() {
        return "Sh2Context{" +
                "registers=" + Arrays.toString(registers) +
                ", GBR=" + GBR +
                ", VBR=" + VBR +
                ", SR=" + SR +
                ", MACH=" + MACH +
                ", MACL=" + MACL +
                ", PR=" + PR +
                ", PC=" + PC +
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
