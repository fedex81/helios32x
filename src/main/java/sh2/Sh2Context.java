package sh2;

import memory.IMemory;
import sh4.Sh4Context;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Context extends Sh4Context {

    private Sh2Emu.Sh2Access sh2Access;
    public String sh2TypeCode;

    public Sh2Context(Sh2Emu.Sh2Access sh2Access, IMemory memory) {
        super(memory);
        this.sh2Access = sh2Access;
        this.sh2TypeCode = sh2Access.name().substring(0, 1);
    }

    @Override
    public void reset() {
        super.reset();
        PC = memory.read32i(0);
        registers[15] = memory.read32i(4); //SP
    }

    @Override
    protected void printDebugMaybe(int instruction) {
        if (debugging) {
//            Sh2Helper.printState(this, instruction);
            Sh2Helper.printInst(this, instruction);
        }
    }

    @Override
    public void run() {
        memory.setSh2Access(sh2Access);
        super.run();
    }
}
