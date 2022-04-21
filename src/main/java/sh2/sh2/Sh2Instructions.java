package sh2.sh2;

import sh2.IMemory;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.util.Arrays;
import java.util.Optional;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2Instructions {

    public static final String ILLEGAL_STR = "ILLEGAL";

    static class OpcodeCreateCtx {
        int opcode;
        IMemory memory;
    }

    public static class Sh2Instruction {
        public final Runnable runnable;
        public final String name;
        public final int opcode;
        public final boolean isBranch, isBranchDelaySlot, isIllegal;

        public Sh2Instruction(int opcode, String name, Runnable r) {
            this.opcode = opcode;
            this.name = name;
            this.runnable = r;
            this.isBranch = isBranchOpcode(name);
            this.isBranchDelaySlot = isBranchDelaySlotOpcode(name);
            this.isIllegal = ILLEGAL_STR.equalsIgnoreCase(name);
        }
    }

    public static final int NUM_OPCODES = 0x10000;
    public static Sh2Instruction[] opcodeMap;

    public static Sh2Instruction[] createOpcodeMap(Sh2Impl sh2) {
        opcodeMap = new Sh2Instruction[NUM_OPCODES];
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

    public static boolean isBranchOpcode(String name) {
        return name.startsWith("B") || name.startsWith("J") || name.startsWith("RT") || name.startsWith("BRA");
    }

    /**
     * Delayed branch instructions: JMP, JSR,
     * BRA, BSR, RTS, RTE, BF/S, BT/S, BSRF,
     * BRAF
     */
    public static boolean isBranchDelaySlotOpcode(String name) {
        return name.startsWith("J") || name.startsWith("BRA") || name.startsWith("BSR") || name.startsWith("RT")
                || name.startsWith("BTS") || name.startsWith("BFS");
    }

    public static Sh2Prefetcher.Sh2BlockUnit[] generateInst(int[] opcodes) {
        return Arrays.stream(opcodes).mapToObj(op -> new Sh2Prefetcher.Sh2BlockUnit(opcodeMap[op])).toArray(Sh2Prefetcher.Sh2BlockUnit[]::new);
    }

    public static StringBuilder toListOfInst(Sh2Prefetcher.Sh2Block ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.prefetchWords.length; i++) {
            int pc = ctx.start + (i << 1);
            pc = pc != ctx.pcMasked ? pc : ctx.prefetchPc;
            sb.append(Sh2Helper.getInstString("", pc, ctx.prefetchWords[i])).append("\n");
        }
        return sb;
    }

    public final static Sh2Instruction getInstruction(final Sh2Impl sh2, final int opcode) {
        switch ((opcode >>> 12) & 0xf) {
            case 0:
                switch ((opcode >>> 0) & 0xf) {
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "STCSR", () -> sh2.STCSR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "STCGBR", () -> sh2.STCGBR(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "STCVBR", () -> sh2.STCVBR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "BSRF", () -> sh2.BSRF(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "BRAF", () -> sh2.BRAF(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 4:
                        return new Sh2Instruction(opcode, "MOVBS0", () -> sh2.MOVBS0(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "MOVWS0", () -> sh2.MOVWS0(opcode));
                    case 6:
                        return new Sh2Instruction(opcode, "MOVLS0", () -> sh2.MOVLS0(opcode));
                    case 7:
                        return new Sh2Instruction(opcode, "MULL", () -> sh2.MULL(opcode));
                    case 8:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return new Sh2Instruction(opcode, "CLRT", () -> sh2.CLRT(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "SETT", () -> sh2.SETT(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "CLRMAC", () -> sh2.CLRMAC(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return new Sh2Instruction(opcode, "NOP", () -> sh2.NOP(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "DIV0U", () -> sh2.DIV0U(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "MOVT", () -> sh2.MOVT(opcode));
                            default:
                                switch ((opcode >>> 4) & 0xf) {
                                    case 2:
                                        return new Sh2Instruction(opcode, "MOVT", () -> sh2.MOVT(opcode));
                                    default:
                                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                                }
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "STSMACH", () -> sh2.STSMACH(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "STSMACL", () -> sh2.STSMACL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "STSPR", () -> sh2.STSPR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return new Sh2Instruction(opcode, "RTS", () -> sh2.RTS(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "SLEEP", () -> sh2.SLEEP(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "RTE", () -> sh2.RTE(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 12:
                        return new Sh2Instruction(opcode, "MOVBL0", () -> sh2.MOVBL0(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "MOVWL0", () -> sh2.MOVWL0(opcode));
                    case 14:
                        return new Sh2Instruction(opcode, "MOVLL0", () -> sh2.MOVLL0(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "MACL", () -> sh2.MACL(opcode));
                    default:
                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                }
            case 1:
                return new Sh2Instruction(opcode, "MOVLS4", () -> sh2.MOVLS4(opcode));
            case 2:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return new Sh2Instruction(opcode, "MOVBS", () -> sh2.MOVBS(opcode));
                    case 1:
                        return new Sh2Instruction(opcode, "MOVWS", () -> sh2.MOVWS(opcode));
                    case 2:
                        return new Sh2Instruction(opcode, "MOVLS", () -> sh2.MOVLS(opcode));
                    case 4:
                        return new Sh2Instruction(opcode, "MOVBM", () -> sh2.MOVBM(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "MOVWM", () -> sh2.MOVWM(opcode));
                    case 6:
                        return new Sh2Instruction(opcode, "MOVLM", () -> sh2.MOVLM(opcode));
                    case 7:
                        return new Sh2Instruction(opcode, "DIV0S", () -> sh2.DIV0S(opcode));
                    case 8:
                        return new Sh2Instruction(opcode, "TST", () -> sh2.TST(opcode));
                    case 9:
                        return new Sh2Instruction(opcode, "AND", () -> sh2.AND(opcode));
                    case 10:
                        return new Sh2Instruction(opcode, "XOR", () -> sh2.XOR(opcode));
                    case 11:
                        return new Sh2Instruction(opcode, "OR", () -> sh2.OR(opcode));
                    case 12:
                        return new Sh2Instruction(opcode, "CMPSTR", () -> sh2.CMPSTR(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "XTRCT", () -> sh2.XTRCT(opcode));
                    case 14:
                        return new Sh2Instruction(opcode, "MULSU", () -> sh2.MULSU(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "MULSW", () -> sh2.MULSW(opcode));
                    default:
                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                }
            case 3:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return new Sh2Instruction(opcode, "CMPEQ", () -> sh2.CMPEQ(opcode));
                    case 2:
                        return new Sh2Instruction(opcode, "CMPHS", () -> sh2.CMPHS(opcode));
                    case 3:
                        return new Sh2Instruction(opcode, "CMPGE", () -> sh2.CMPGE(opcode));
                    case 4:
                        return new Sh2Instruction(opcode, "DIV1", () -> sh2.DIV1(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "DMULU", () -> sh2.DMULU(opcode));
                    case 6:
                        return new Sh2Instruction(opcode, "CMPHI", () -> sh2.CMPHI(opcode));
                    case 7:
                        return new Sh2Instruction(opcode, "CMPGT", () -> sh2.CMPGT(opcode));
                    case 8:
                        return new Sh2Instruction(opcode, "SUB", () -> sh2.SUB(opcode));
                    case 10:
                        return new Sh2Instruction(opcode, "SUBC", () -> sh2.SUBC(opcode));
                    case 11:
                        return new Sh2Instruction(opcode, "SUBV", () -> sh2.SUBV(opcode));
                    case 12:
                        return new Sh2Instruction(opcode, "ADD", () -> sh2.ADD(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "DMULS", () -> sh2.DMULS(opcode));
                    case 14:
                        return new Sh2Instruction(opcode, "ADDC", () -> sh2.ADDC(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "ADDV", () -> sh2.ADDV(opcode));
                    default:
                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                }
            case 4:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "SHLL", () -> sh2.SHLL(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "DT", () -> sh2.DT(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "SHAL", () -> sh2.SHAL(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 1:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "SHLR", () -> sh2.SHLR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "CMPPZ", () -> sh2.CMPPZ(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "SHAR", () -> sh2.SHAR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "STSMMACH", () -> sh2.STSMMACH(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "STSMMACL", () -> sh2.STSMMACL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "STSMPR", () -> sh2.STSMPR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "STCMSR", () -> sh2.STCMSR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "STCMGBR", () -> sh2.STCMGBR(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "STCMVBR", () -> sh2.STCMVBR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 4:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "ROTL", () -> sh2.ROTL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "ROTCL", () -> sh2.ROTCL(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 5:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "ROTR", () -> sh2.ROTR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "CMPPL", () -> sh2.CMPPL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "ROTCR", () -> sh2.ROTCR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 6:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "LDSMMACH", () -> sh2.LDSMMACH(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "LDSMMACL", () -> sh2.LDSMMACL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "LDSMPR", () -> sh2.LDSMPR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 7:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "LDCMSR", () -> sh2.LDCMSR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "LDCMGBR", () -> sh2.LDCMGBR(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "LDCMVBR", () -> sh2.LDCMVBR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 8:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "SHLL2", () -> sh2.SHLL2(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "SHLL8", () -> sh2.SHLL8(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "SHLL16", () -> sh2.SHLL16(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "SHLR2", () -> sh2.SHLR2(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "SHLR8", () -> sh2.SHLR8(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "SHLR16", () -> sh2.SHLR16(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "LDSMACH", () -> sh2.LDSMACH(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "LDSMACL", () -> sh2.LDSMACL(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "LDSPR", () -> sh2.LDSPR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "JSR", () -> sh2.JSR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "TAS", () -> sh2.TAS(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "JMP", () -> sh2.JMP(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 14:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return new Sh2Instruction(opcode, "LDCSR", () -> sh2.LDCSR(opcode));
                            case 1:
                                return new Sh2Instruction(opcode, "LDCGBR", () -> sh2.LDCGBR(opcode));
                            case 2:
                                return new Sh2Instruction(opcode, "LDCVBR", () -> sh2.LDCVBR(opcode));
                            default:
                                return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                        }
                    case 15:
                        return new Sh2Instruction(opcode, "MACW", () -> sh2.MACW(opcode));
                    default:
                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                }
            case 5:
                return new Sh2Instruction(opcode, "MOVLL4", () -> sh2.MOVLL4(opcode));
            case 6:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return new Sh2Instruction(opcode, "MOVBL", () -> sh2.MOVBL(opcode));
                    case 1:
                        return new Sh2Instruction(opcode, "MOVWL", () -> sh2.MOVWL(opcode));
                    case 2:
                        return new Sh2Instruction(opcode, "MOVLL", () -> sh2.MOVLL(opcode));
                    case 3:
                        return new Sh2Instruction(opcode, "MOV", () -> sh2.MOV(opcode));
                    case 4:
                        return new Sh2Instruction(opcode, "MOVBP", () -> sh2.MOVBP(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "MOVWP", () -> sh2.MOVWP(opcode));
                    case 6:
                        return new Sh2Instruction(opcode, "MOVLP", () -> sh2.MOVLP(opcode));
                    case 7:
                        return new Sh2Instruction(opcode, "NOT", () -> sh2.NOT(opcode));
                    case 8:
                        return new Sh2Instruction(opcode, "SWAPB", () -> sh2.SWAPB(opcode));
                    case 9:
                        return new Sh2Instruction(opcode, "SWAPW", () -> sh2.SWAPW(opcode));
                    case 10:
                        return new Sh2Instruction(opcode, "NEGC", () -> sh2.NEGC(opcode));
                    case 11:
                        return new Sh2Instruction(opcode, "NEG", () -> sh2.NEG(opcode));
                    case 12:
                        return new Sh2Instruction(opcode, "EXTUB", () -> sh2.EXTUB(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "EXTUW", () -> sh2.EXTUW(opcode));
                    case 14:
                        return new Sh2Instruction(opcode, "EXTSB", () -> sh2.EXTSB(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "EXTSW", () -> sh2.EXTSW(opcode));
                }
            case 7:
                return new Sh2Instruction(opcode, "ADDI", () -> sh2.ADDI(opcode));
            case 8:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return new Sh2Instruction(opcode, "MOVBS4", () -> sh2.MOVBS4(opcode));
                    case 1:
                        return new Sh2Instruction(opcode, "MOVWS4", () -> sh2.MOVWS4(opcode));
                    case 4:
                        return new Sh2Instruction(opcode, "MOVBL4", () -> sh2.MOVBL4(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "MOVWL4", () -> sh2.MOVWL4(opcode));
                    case 8:
                        return new Sh2Instruction(opcode, "CMPIM", () -> sh2.CMPIM(opcode));
                    case 9:
                        return new Sh2Instruction(opcode, "BT", () -> sh2.BT(opcode));
                    case 11:
                        return new Sh2Instruction(opcode, "BF", () -> sh2.BF(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "BTS", () -> sh2.BTS(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "BFS", () -> sh2.BFS(opcode));
                    default:
                        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
                }
            case 9:
                return new Sh2Instruction(opcode, "MOVWI", () -> sh2.MOVWI(opcode));
            case 10:
                return new Sh2Instruction(opcode, "BRA", () -> sh2.BRA(opcode));
            case 11:
                return new Sh2Instruction(opcode, "BSR", () -> sh2.BSR(opcode));
            case 12:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return new Sh2Instruction(opcode, "MOVBSG", () -> sh2.MOVBSG(opcode));
                    case 1:
                        return new Sh2Instruction(opcode, "MOVWSG", () -> sh2.MOVWSG(opcode));
                    case 2:
                        return new Sh2Instruction(opcode, "MOVLSG", () -> sh2.MOVLSG(opcode));
                    case 3:
                        return new Sh2Instruction(opcode, "TRAPA", () -> sh2.TRAPA(opcode));
                    case 4:
                        return new Sh2Instruction(opcode, "MOVBLG", () -> sh2.MOVBLG(opcode));
                    case 5:
                        return new Sh2Instruction(opcode, "MOVWLG", () -> sh2.MOVWLG(opcode));
                    case 6:
                        return new Sh2Instruction(opcode, "MOVLLG", () -> sh2.MOVLLG(opcode));
                    case 7:
                        return new Sh2Instruction(opcode, "MOVA", () -> sh2.MOVA(opcode));
                    case 8:
                        return new Sh2Instruction(opcode, "TSTI", () -> sh2.TSTI(opcode));
                    case 9:
                        return new Sh2Instruction(opcode, "ANDI", () -> sh2.ANDI(opcode));
                    case 10:
                        return new Sh2Instruction(opcode, "XORI", () -> sh2.XORI(opcode));
                    case 11:
                        return new Sh2Instruction(opcode, "ORI", () -> sh2.ORI(opcode));
                    case 12:
                        return new Sh2Instruction(opcode, "TSTM", () -> sh2.TSTM(opcode));
                    case 13:
                        return new Sh2Instruction(opcode, "ANDM", () -> sh2.ANDM(opcode));
                    case 14:
                        return new Sh2Instruction(opcode, "XORM", () -> sh2.XORM(opcode));
                    case 15:
                        return new Sh2Instruction(opcode, "ORM", () -> sh2.ORM(opcode));
                }
            case 13:
                return new Sh2Instruction(opcode, "MOVLI", () -> sh2.MOVLI(opcode));
            case 14:
                return new Sh2Instruction(opcode, "MOVI", () -> sh2.MOVI(opcode));
        }
        return new Sh2Instruction(opcode, ILLEGAL_STR, () -> sh2.ILLEGAL(opcode));
    }

}