package sh2.sh2;

import sh2.IMemory;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.util.Arrays;
import java.util.Optional;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Instructions {

    static class OpcodeCreateCtx {
        int opcode;
        IMemory memory;
    }

    public static interface Sh2Instruction extends Runnable {
    }

    public static int NUM_OPCODES = 0x10000;
    public static String[] opcodeToNamesMap;
    public static boolean[] opcodeJump;
    private static Sh2Instruction[] opcodeMap;

    public static Sh2Instruction[] createOpcodeMap(Sh2Impl sh2) {
        opcodeMap = new Sh2Instruction[NUM_OPCODES];
        opcodeToNamesMap = new String[NUM_OPCODES];
        opcodeJump = new boolean[NUM_OPCODES];
        for (int i = 0; i < opcodeMap.length; i++) {
            opcodeMap[i] = getInstruction(sh2, i);
        }
        return opcodeMap;
    }

    private static String methodName() {
        StackWalker walker = StackWalker.getInstance();
        Optional<String> methodName = walker.walk(frames -> frames
                .skip(2).findFirst()
                .map(StackWalker.StackFrame::getMethodName));
        return methodName.orElse("ERROR");
    }

    private static void initOpcodeData(OpcodeCreateCtx op) {
        String name = methodName();
        opcodeToNamesMap[op.opcode] = name;
        opcodeJump[op.opcode] = name.startsWith("B") || name.startsWith("J") || name.startsWith("RT") || name.startsWith("BRA");
    }

    public static Sh2Instruction[] generateInst(int[] opcodes) {
        return Arrays.stream(opcodes).mapToObj(op -> opcodeMap[op]).toArray(Sh2Instruction[]::new);
    }

    public static StringBuilder toListOfInst(Sh2Prefetch.PrefetchContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.prefetchWords.length; i++) {
            int pc = ctx.start + (i << 1);
            pc = pc != ctx.pcMasked ? pc : ctx.prefetchPc;
            sb.append(Sh2Helper.getInstString("", pc, ctx.prefetchWords[i])).append("\n");
        }
        return sb;
    }

    public final static Sh2Instruction getInstruction(final Sh2Impl sh2, final int opcode) {
        opcodeToNamesMap[opcode] = Sh2Helper.getInstString("", 0, opcode);
        switch ((opcode >>> 12) & 0xf) {
            case 0:
                switch ((opcode >>> 0) & 0xf) {
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.STCSR(opcode);
                            case 1:
                                return () -> sh2.STCGBR(opcode);
                            case 2:
                                return () -> sh2.STCVBR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.BSRF(opcode);
                            case 2:
                                return () -> sh2.BRAF(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 4:
                        return () -> sh2.MOVBS0(opcode);
                    case 5:
                        return () -> sh2.MOVWS0(opcode);
                    case 6:
                        return () -> sh2.MOVLS0(opcode);
                    case 7:
                        return () -> sh2.MULL(opcode);
                    case 8:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.CLRT(opcode);
                            case 1:
                                return () -> sh2.SETT(opcode);
                            case 2:
                                return () -> sh2.CLRMAC(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.NOP(opcode);
                            case 1:
                                return () -> sh2.DIV0U(opcode);
                            case 2:
                                return () -> sh2.MOVT(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.STSMACH(opcode);
                            case 1:
                                return () -> sh2.STSMACL(opcode);
                            case 2:
                                return () -> sh2.STSPR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.RTS(opcode);
                            case 1:
                                return () -> sh2.SLEEP(opcode);
                            case 2:
                                return () -> sh2.RTE(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 12:
                        return () -> sh2.MOVBL0(opcode);
                    case 13:
                        return () -> sh2.MOVWL0(opcode);
                    case 14:
                        return () -> sh2.MOVLL0(opcode);
                    case 15:
                        return () -> sh2.MACL(opcode);
                    default:
                        return () -> sh2.ILLEGAL(opcode);
                }
            case 1:
                return () -> sh2.MOVLS4(opcode);
            case 2:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return () -> sh2.MOVBS(opcode);
                    case 1:
                        return () -> sh2.MOVWS(opcode);
                    case 2:
                        return () -> sh2.MOVLS(opcode);
                    case 4:
                        return () -> sh2.MOVBM(opcode);
                    case 5:
                        return () -> sh2.MOVWM(opcode);
                    case 6:
                        return () -> sh2.MOVLM(opcode);
                    case 7:
                        return () -> sh2.DIV0S(opcode);
                    case 8:
                        return () -> sh2.TST(opcode);
                    case 9:
                        return () -> sh2.AND(opcode);
                    case 10:
                        return () -> sh2.XOR(opcode);
                    case 11:
                        return () -> sh2.OR(opcode);
                    case 12:
                        return () -> sh2.CMPSTR(opcode);
                    case 13:
                        return () -> sh2.XTRCT(opcode);
                    case 14:
                        return () -> sh2.MULSU(opcode);
                    case 15:
                        return () -> sh2.MULSW(opcode);
                    default:
                        return () -> sh2.ILLEGAL(opcode);
                }
            case 3:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return () -> sh2.CMPEQ(opcode);
                    case 2:
                        return () -> sh2.CMPHS(opcode);
                    case 3:
                        return () -> sh2.CMPGE(opcode);
                    case 4:
                        return () -> sh2.DIV1(opcode);
                    case 5:
                        return () -> sh2.DMULU(opcode);
                    case 6:
                        return () -> sh2.CMPHI(opcode);
                    case 7:
                        return () -> sh2.CMPGT(opcode);
                    case 8:
                        return () -> sh2.SUB(opcode);
                    case 10:
                        return () -> sh2.SUBC(opcode);
                    case 11:
                        return () -> sh2.SUBV(opcode);
                    case 12:
                        return () -> sh2.ADD(opcode);
                    case 13:
                        return () -> sh2.DMULS(opcode);
                    case 14:
                        return () -> sh2.ADDC(opcode);
                    case 15:
                        return () -> sh2.ADDV(opcode);
                    default:
                        return () -> sh2.ILLEGAL(opcode);
                }
            case 4:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.SHLL(opcode);
                            case 1:
                                return () -> sh2.DT(opcode);
                            case 2:
                                return () -> sh2.SHAL(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 1:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.SHLR(opcode);
                            case 1:
                                return () -> sh2.CMPPZ(opcode);
                            case 2:
                                return () -> sh2.SHAR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.STSMMACH(opcode);
                            case 1:
                                return () -> sh2.STSMMACL(opcode);
                            case 2:
                                return () -> sh2.STSMPR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.STCMSR(opcode);
                            case 1:
                                return () -> sh2.STCMGBR(opcode);
                            case 2:
                                return () -> sh2.STCMVBR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 4:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.ROTL(opcode);
                            case 2:
                                return () -> sh2.ROTCL(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 5:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.ROTR(opcode);
                            case 1:
                                return () -> sh2.CMPPL(opcode);
                            case 2:
                                return () -> sh2.ROTCR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 6:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.LDSMMACH(opcode);
                            case 1:
                                return () -> sh2.LDSMMACL(opcode);
                            case 2:
                                return () -> sh2.LDSMPR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 7:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.LDCMSR(opcode);
                            case 1:
                                return () -> sh2.LDCMGBR(opcode);
                            case 2:
                                return () -> sh2.LDCMVBR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 8:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.SHLL2(opcode);
                            case 1:
                                return () -> sh2.SHLL8(opcode);
                            case 2:
                                return () -> sh2.SHLL16(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.SHLR2(opcode);
                            case 1:
                                return () -> sh2.SHLR8(opcode);
                            case 2:
                                return () -> sh2.SHLR16(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.LDSMACH(opcode);
                            case 1:
                                return () -> sh2.LDSMACL(opcode);
                            case 2:
                                return () -> sh2.LDSPR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.JSR(opcode);
                            case 1:
                                return () -> sh2.TAS(opcode);
                            case 2:
                                return () -> sh2.JMP(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 14:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return () -> sh2.LDCSR(opcode);
                            case 1:
                                return () -> sh2.LDCGBR(opcode);
                            case 2:
                                return () -> sh2.LDCVBR(opcode);
                            default:
                                return () -> sh2.ILLEGAL(opcode);
                        }
                    case 15:
                        return () -> sh2.MACW(opcode);
                    default:
                        return () -> sh2.ILLEGAL(opcode);
                }
            case 5:
                return () -> sh2.MOVLL4(opcode);
            case 6:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return () -> sh2.MOVBL(opcode);
                    case 1:
                        return () -> sh2.MOVWL(opcode);
                    case 2:
                        return () -> sh2.MOVLL(opcode);
                    case 3:
                        return () -> sh2.MOV(opcode);
                    case 4:
                        return () -> sh2.MOVBP(opcode);
                    case 5:
                        return () -> sh2.MOVWP(opcode);
                    case 6:
                        return () -> sh2.MOVLP(opcode);
                    case 7:
                        return () -> sh2.NOT(opcode);
                    case 8:
                        return () -> sh2.SWAPB(opcode);
                    case 9:
                        return () -> sh2.SWAPW(opcode);
                    case 10:
                        return () -> sh2.NEGC(opcode);
                    case 11:
                        return () -> sh2.NEG(opcode);
                    case 12:
                        return () -> sh2.EXTUB(opcode);
                    case 13:
                        return () -> sh2.EXTUW(opcode);
                    case 14:
                        return () -> sh2.EXTSB(opcode);
                    case 15:
                        return () -> sh2.EXTSW(opcode);
                }
            case 7:
                return () -> sh2.ADDI(opcode);
            case 8:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return () -> sh2.MOVBS4(opcode);
                    case 1:
                        return () -> sh2.MOVWS4(opcode);
                    case 4:
                        return () -> sh2.MOVBL4(opcode);
                    case 5:
                        return () -> sh2.MOVWL4(opcode);
                    case 8:
                        return () -> sh2.CMPIM(opcode);
                    case 9:
                        return () -> sh2.BT(opcode);
                    case 11:
                        return () -> sh2.BF(opcode);
                    case 13:
                        return () -> sh2.BTS(opcode);
                    case 15:
                        return () -> sh2.BFS(opcode);
                    default:
                        return () -> sh2.ILLEGAL(opcode);
                }
            case 9:
                return () -> sh2.MOVWI(opcode);
            case 10:
                return () -> sh2.BRA(opcode);
            case 11:
                return () -> sh2.BSR(opcode);
            case 12:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return () -> sh2.MOVBSG(opcode);
                    case 1:
                        return () -> sh2.MOVWSG(opcode);
                    case 2:
                        return () -> sh2.MOVLSG(opcode);
                    case 3:
                        return () -> sh2.TRAPA(opcode);
                    case 4:
                        return () -> sh2.MOVBLG(opcode);
                    case 5:
                        return () -> sh2.MOVWLG(opcode);
                    case 6:
                        return () -> sh2.MOVLLG(opcode);
                    case 7:
                        return () -> sh2.MOVA(opcode);
                    case 8:
                        return () -> sh2.TSTI(opcode);
                    case 9:
                        return () -> sh2.ANDI(opcode);
                    case 10:
                        return () -> sh2.XORI(opcode);
                    case 11:
                        return () -> sh2.ORI(opcode);
                    case 12:
                        return () -> sh2.TSTM(opcode);
                    case 13:
                        return () -> sh2.ANDM(opcode);
                    case 14:
                        return () -> sh2.XORM(opcode);
                    case 15:
                        return () -> sh2.ORM(opcode);
                }
            case 13:
                return () -> sh2.MOVLI(opcode);
            case 14:
                return () -> sh2.MOVI(opcode);
        }
        return () -> sh2.ILLEGAL(opcode);
    }
}
