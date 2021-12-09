package sh2.sh2;

import sh2.S32xUtil;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Context {

    final static int NUM_REG = 16;
    /* System Registers */
    public int registers[];

    /* Flags to access the bit fields in SR and FPSCR
     * Sintax : flagRegName
     */
    public int GBR, VBR, SR;
    public int MACH, MACL, PR;
    public int PC;

    /*
     * defines the number of cycles we can ran before stopping the interpreter
     */
    public int cycles;
    public int cycles_ran;

    public S32xUtil.CpuDeviceAccess cpuAccess;
    public String sh2TypeCode;
    public boolean debug;

    public Sh2Context(S32xUtil.CpuDeviceAccess sh2Access) {
        this.registers = new int[NUM_REG];
        this.cpuAccess = sh2Access;
        this.sh2TypeCode = sh2Access.name().substring(0, 1);
    }
}
