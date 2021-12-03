package sh2;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Helper {

    public static final Sh2Disassembler disasm = new Sh2Disassembler();

    public static void printInst(Sh2Context ctx, int opcode) {
        System.out.println(getInstString(ctx, opcode));
    }

    public static String getInstString(Sh2Context ctx, int opcode) {
        return ctx.sh2TypeCode + " " + Integer.toHexString(ctx.PC) + ": " + disasm.disassemble(ctx.PC, opcode);
    }

    public static void printState(Sh2Context ctx, int opcode) {
        System.out.println(toDebuggingString(ctx, opcode));
    }

    public static String toDebuggingString(Sh2Context ctx, int opcode) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append(disasm.disassemble(ctx.PC, opcode)).append("\n");
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