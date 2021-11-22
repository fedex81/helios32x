package sh2;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Helper {

    public static void printInst(Sh2 ctx, int opcode) {
        System.out.println(ctx.sh2TypeCode + " " + Integer.toHexString(ctx.PC) + ": " + ctx.disassembler.disassemble(ctx.PC, opcode));
    }

    public static void printState(Sh2 ctx, int opcode) {
        System.out.println(toDebuggingString(ctx, opcode));
    }

    public static String toDebuggingString(Sh2 ctx, int opcode) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append(ctx.disassembler.disassemble(ctx.PC, opcode)).append("\n");
        sb.append(String.format("PC : %08x\t", ctx.PC));
        sb.append(String.format("GBR: %08x\t", ctx.GBR));
        sb.append(String.format("VBR: %08x\t", ctx.VBR));
        sb.append(String.format("SR : %08x\t", ctx.SR));

        sb.append(((ctx.SR & ctx.flagT) != 0 ? "T" : "-") + ((ctx.SR & ctx.flagS) != 0 ? "S" : "-") +
                ((ctx.SR & ctx.flagQ) != 0 ? "Q" : "-") + (((ctx.SR & ctx.flagBL) != 0 ? "BL" : "-")));
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