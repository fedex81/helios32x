package sh2;

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

    public Sh2Util.CpuDeviceAccess sh2Access;
    public String sh2TypeCode;
    public boolean debug;

    public Sh2Context(Sh2Util.CpuDeviceAccess sh2Access) {
        this.registers = new int[NUM_REG];
        this.sh2Access = sh2Access;
        this.sh2TypeCode = sh2Access.name().substring(0, 1);
    }
}
