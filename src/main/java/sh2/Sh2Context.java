package sh2;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Context {
    /* System Registers */
    public int registers[];

    /* Flags to access the bit fields in SR and FPSCR
     * Sintax : flagRegName
     */
    public int GBR, SSR, VBR, SR;
    public int MACH, MACL, PR;
    public int PC;

    public Sh2Emu.Sh2Access sh2Access;
    public String sh2TypeCode;

    public Sh2Context(Sh2Emu.Sh2Access sh2Access) {
        this.registers = new int[24];
        this.sh2Access = sh2Access;
        this.sh2TypeCode = sh2Access.name().substring(0, 1);
    }
}
