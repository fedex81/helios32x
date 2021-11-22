package sh4;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh4Helper {

    public static void printState(Sh4Context ctx, int opcode) {
        System.out.println(toDebuggingString(ctx, opcode));
    }

     public static String toDebuggingString(Sh4Context ctx, int opcode){
        StringBuilder sb = new StringBuilder("\n");
        sb.append(ctx.disassembler.disassemble(ctx.PC, opcode)).append("\n");
        sb.append(String.format("PC : %08x\t", ctx.PC));
        sb.append(String.format("GBR: %08x\t", ctx.GBR));
        sb.append(String.format("VBR: %08x\t", ctx.VBR));
        sb.append(String.format("SPC: %08x\t", ctx.SPC));
        sb.append(String.format("SR : %08x\t", ctx.SR));

        sb.append( ((ctx.SR & ctx.flagT)!=0 ? "T" : "-") + ((ctx.SR & ctx.flagS)!=0 ? "S" : "-") +
                ((ctx.SR & ctx.flagQ)!=0 ? "Q" : "-") + (((ctx.SR & ctx.flagBL)!=0 ? "BL" : "-")) );
        sb.append("\n");


        for(int i =0; i < 16; i++){
            sb.append(String.format("R%02d: %08x\t", i, ctx.registers[i]));
            if(i == 7){
                sb.append("\n");
            }
        }
        sb.append("\n");
        sb.append(String.format("MCH: %08x\t", ctx.MACH));
        sb.append(String.format("MCL: %08x\t", ctx.MACL));
        sb.append(String.format("PR : %08x\t", ctx.PR));

        //sh4 only
        sb.append("\n\n");

        sb.append(String.format("FPUL: %08x\t", ctx.FPUL));
        sb.append(String.format("FSCR: %08x\t", ctx.FPSCR));

        sb.append("\n");

        for(int i =0; i < 16; i++){
            sb.append(String.format("FR%02d: %08f\t", i, ctx.FRm[i]));
            if(i == 7){
                sb.append("\n");
            }
        }
        return sb.toString();
    }

//    public void showDebuggingOld(int opcode){
//        Logger.log(Logger.CPU,disassembler.disassemble(PC, opcode));
//        for(int i =0; i < 16; i++){
//            Logger.log(Logger.CPU,"R" + i + " --> " + Integer.toHexString(registers[i]));
//        }
//        Logger.log(Logger.CPU,"PC " + Integer.toHexString(PC));
//        Logger.log(Logger.CPU,"SPC " + Integer.toHexString(SPC));
//        Logger.log(Logger.CPU,"SR " + Integer.toHexString(SR));
//
//        Logger.log(Logger.CPU,"T: " + ((SR & flagT)!=0 ? 1 : 0));
//        Logger.log(Logger.CPU,"S: " +  ((SR & flagS)!=0 ? 1 : 0));
//        Logger.log(Logger.CPU,"Q: " +  ((SR & flagQ)!=0 ? 1 : 0));
//        Logger.log(Logger.CPU,"BL: " +  ((SR & flagBL)!=0 ? 1 : 0));
//
//        Logger.log(Logger.CPU,"FPUL:" + Integer.toHexString(FPUL));
//
//        Logger.log(Logger.CPU,"FPSCR:" + Integer.toHexString(FPSCR));
//
//        Logger.log(Logger.CPU, "PR:"  + Integer.toHexString(PR));
//
//        for(int i =0; i < 16; i++){
//            Logger.log(Logger.CPU,"FR" + i + " --> " + FRm[i]);
//        }
//    }
}
