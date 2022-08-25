package sh2.sh2;

import sh2.sh2.drc.Sh2Block;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Helper {

    public static final Sh2Disassembler disasm = new Sh2Disassembler();
    private static final String simpleFormat = "%s %08x\t%04x\t%s";

    public static void printInst(Sh2Context ctx) {
        System.out.println(getInstString(ctx));
    }

    public static String getInstString(Sh2Context ctx) {
        return String.format(simpleFormat, ctx.sh2TypeCode, ctx.PC, ctx.opcode, disasm.disassemble(ctx.PC, ctx.opcode));
    }

    public static String getInstString(int pc, int opcode) {
        return disasm.disassemble(pc, opcode);
    }

    public static String getInstString(String sh2Type, int pc, int opcode) {
        return String.format(simpleFormat, sh2Type, pc, opcode, disasm.disassemble(pc, opcode));
    }

    public static void printState(Sh2Context ctx) {
        System.out.println(toDebuggingString(ctx));
    }

    public static StringBuilder toListOfInst(Sh2Block ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.prefetchWords.length; i++) {
            int pc = ctx.start + (i << 1);
            pc = pc != ctx.pcMasked ? pc : ctx.prefetchPc;
            sb.append(Sh2Helper.getInstString("", pc, ctx.prefetchWords[i])).append("\n");
        }
        return sb;
    }

    public static String toDebuggingString(Sh2Context ctx) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append(disasm.disassemble(ctx.PC, ctx.opcode)).append("\n");
        sb.append(String.format("PC : %08x\t", ctx.PC));
        sb.append(String.format("GBR: %08x\t", ctx.GBR));
        sb.append(String.format("VBR: %08x\t", ctx.VBR));
        sb.append(String.format("SR : %08x\t", ctx.SR));

        sb.append(((ctx.SR & Sh2.flagT) != 0 ? "T" : "-") + ((ctx.SR & Sh2.flagS) != 0 ? "S" : "-") +
                ((ctx.SR & Sh2.flagQ) != 0 ? "Q" : "-") + (((ctx.SR & Sh2.flagM) != 0 ? "M" : "-")));
        sb.append("\n");


        for (int i = 0; i < 16; i++) {
            sb.append(String.format("R%02d: %08x\t", i, ctx.registers[i]));
            if (i == 7) {
                sb.append("\n");
            }
        }
        sb.append("\n");
        sb.append(String.format("MCH: %08x\t", ctx.MACH));
        sb.append(String.format("MCL: %08x\t", ctx.MACL));
        sb.append(String.format("PR : %08x\t", ctx.PR));
        sb.append("\n");
        return sb.toString();
    }
}