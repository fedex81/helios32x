package sh2.sh2;

import sh2.IMemory;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.util.Arrays;
import java.util.Optional;

import static sh2.sh2.Sh2Instructions.Sh2Inst.*;

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

    public static class Sh2Instruction {
        public final Runnable runnable;
        public final Sh2Inst inst;
        public final int opcode;
        public final boolean isBranch, isBranchDelaySlot, isIllegal;

        public Sh2Instruction(int opcode, Sh2Inst inst, Runnable r) {
            this.opcode = opcode;
            this.inst = inst;
            this.runnable = r;
            this.isBranch = isBranchOpcode(inst.name());
            this.isBranchDelaySlot = isBranchDelaySlotOpcode(inst.name());
            this.isIllegal = ILLEGAL == inst;
        }
    }

    public static final int NUM_OPCODES = 0x10000;
    public static Sh2Instruction[] instOpcodeMap;
    public static Sh2Inst[] sh2OpcodeMap;

    public static Sh2Instruction[] createOpcodeMap(Sh2Impl sh2) {
        instOpcodeMap = new Sh2Instruction[NUM_OPCODES];
        sh2OpcodeMap = new Sh2Inst[NUM_OPCODES];
        for (int i = 0; i < instOpcodeMap.length; i++) {
            instOpcodeMap[i] = getInstruction(sh2, i);
            sh2OpcodeMap[i] = getInstruction(i);
        }
        return instOpcodeMap;
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
        return Arrays.stream(opcodes).mapToObj(op -> new Sh2Prefetcher.Sh2BlockUnit(instOpcodeMap[op])).toArray(Sh2Prefetcher.Sh2BlockUnit[]::new);
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

    public final static Sh2Inst getInstruction(final int opcode) {
        switch ((opcode >>> 12) & 0xf) {
            case 0:
                switch ((opcode >>> 0) & 0xf) {
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return STCSR;
                            case 1:
                                return STCGBR;
                            case 2:
                                return STCVBR;
                            default:
                                return ILLEGAL;
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return BSRF;
                            case 2:
                                return BRAF;
                            default:
                                return ILLEGAL;
                        }
                    case 4:
                        return MOVBS0;
                    case 5:
                        return MOVWS0;
                    case 6:
                        return MOVLS0;
                    case 7:
                        return MULL;
                    case 8:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return CLRT;
                            case 1:
                                return SETT;
                            case 2:
                                return CLRMAC;
                            default:
                                return ILLEGAL;
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return NOP;
                            case 1:
                                return DIV0U;
                            case 2:
                                return MOVT;
                            default:
                                switch ((opcode >>> 4) & 0xf) {
                                    case 2:
                                        return MOVT;
                                    default:
                                        return ILLEGAL;
                                }
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return STSMACH;
                            case 1:
                                return STSMACL;
                            case 2:
                                return STSPR;
                            default:
                                return ILLEGAL;
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xfff) {
                            case 0:
                                return RTS;
                            case 1:
                                return SLEEP;
                            case 2:
                                return RTE;
                            default:
                                return ILLEGAL;
                        }
                    case 12:
                        return MOVBL0;
                    case 13:
                        return MOVWL0;
                    case 14:
                        return MOVLL0;
                    case 15:
                        return MACL;
                    default:
                        return ILLEGAL;
                }
            case 1:
                return MOVLS4;
            case 2:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return MOVBS;
                    case 1:
                        return MOVWS;
                    case 2:
                        return MOVLS;
                    case 4:
                        return MOVBM;
                    case 5:
                        return MOVWM;
                    case 6:
                        return MOVLM;
                    case 7:
                        return DIV0S;
                    case 8:
                        return TST;
                    case 9:
                        return AND;
                    case 10:
                        return XOR;
                    case 11:
                        return OR;
                    case 12:
                        return CMPSTR;
                    case 13:
                        return XTRCT;
                    case 14:
                        return MULSU;
                    case 15:
                        return MULSW;
                    default:
                        return ILLEGAL;
                }
            case 3:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return CMPEQ;
                    case 2:
                        return CMPHS;
                    case 3:
                        return CMPGE;
                    case 4:
                        return DIV1;
                    case 5:
                        return DMULU;
                    case 6:
                        return CMPHI;
                    case 7:
                        return CMPGT;
                    case 8:
                        return SUB;
                    case 10:
                        return SUBC;
                    case 11:
                        return SUBV;
                    case 12:
                        return ADD;
                    case 13:
                        return DMULS;
                    case 14:
                        return ADDC;
                    case 15:
                        return ADDV;
                    default:
                        return ILLEGAL;
                }
            case 4:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return SHLL;
                            case 1:
                                return DT;
                            case 2:
                                return SHAL;
                            default:
                                return ILLEGAL;
                        }
                    case 1:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return SHLR;
                            case 1:
                                return CMPPZ;
                            case 2:
                                return SHAR;
                            default:
                                return ILLEGAL;
                        }
                    case 2:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return STSMMACH;
                            case 1:
                                return STSMMACL;
                            case 2:
                                return STSMPR;
                            default:
                                return ILLEGAL;
                        }
                    case 3:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return STCMSR;
                            case 1:
                                return STCMGBR;
                            case 2:
                                return STCMVBR;
                            default:
                                return ILLEGAL;
                        }
                    case 4:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return ROTL;
                            case 2:
                                return ROTCL;
                            default:
                                return ILLEGAL;
                        }
                    case 5:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return ROTR;
                            case 1:
                                return CMPPL;
                            case 2:
                                return ROTCR;
                            default:
                                return ILLEGAL;
                        }
                    case 6:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return LDSMMACH;
                            case 1:
                                return LDSMMACL;
                            case 2:
                                return LDSMPR;
                            default:
                                return ILLEGAL;
                        }
                    case 7:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return LDCMSR;
                            case 1:
                                return LDCMGBR;
                            case 2:
                                return LDCMVBR;
                            default:
                                return ILLEGAL;
                        }
                    case 8:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return SHLL2;
                            case 1:
                                return SHLL8;
                            case 2:
                                return SHLL16;
                            default:
                                return ILLEGAL;
                        }
                    case 9:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return SHLR2;
                            case 1:
                                return SHLR8;
                            case 2:
                                return SHLR16;
                            default:
                                return ILLEGAL;
                        }
                    case 10:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return LDSMACH;
                            case 1:
                                return LDSMACL;
                            case 2:
                                return LDSPR;
                            default:
                                return ILLEGAL;
                        }
                    case 11:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return JSR;
                            case 1:
                                return TAS;
                            case 2:
                                return JMP;
                            default:
                                return ILLEGAL;
                        }
                    case 14:
                        switch ((opcode >>> 4) & 0xf) {
                            case 0:
                                return LDCSR;
                            case 1:
                                return LDCGBR;
                            case 2:
                                return LDCVBR;
                            default:
                                return ILLEGAL;
                        }
                    case 15:
                        return MACW;
                    default:
                        return ILLEGAL;
                }
            case 5:
                return MOVLL4;
            case 6:
                switch ((opcode >>> 0) & 0xf) {
                    case 0:
                        return MOVBL;
                    case 1:
                        return MOVWL;
                    case 2:
                        return MOVLL;
                    case 3:
                        return MOV;
                    case 4:
                        return MOVBP;
                    case 5:
                        return MOVWP;
                    case 6:
                        return MOVLP;
                    case 7:
                        return NOT;
                    case 8:
                        return SWAPB;
                    case 9:
                        return SWAPW;
                    case 10:
                        return NEGC;
                    case 11:
                        return NEG;
                    case 12:
                        return EXTUB;
                    case 13:
                        return EXTUW;
                    case 14:
                        return EXTSB;
                    case 15:
                        return EXTSW;
                }
            case 7:
                return ADDI;
            case 8:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return MOVBS4;
                    case 1:
                        return MOVWS4;
                    case 4:
                        return MOVBL4;
                    case 5:
                        return MOVWL4;
                    case 8:
                        return CMPIM;
                    case 9:
                        return BT;
                    case 11:
                        return BF;
                    case 13:
                        return BTS;
                    case 15:
                        return BFS;
                    default:
                        return ILLEGAL;
                }
            case 9:
                return MOVWI;
            case 10:
                return BRA;
            case 11:
                return BSR;
            case 12:
                switch ((opcode >>> 8) & 0xf) {
                    case 0:
                        return MOVBSG;
                    case 1:
                        return MOVWSG;
                    case 2:
                        return MOVLSG;
                    case 3:
                        return TRAPA;
                    case 4:
                        return MOVBLG;
                    case 5:
                        return MOVWLG;
                    case 6:
                        return MOVLLG;
                    case 7:
                        return MOVA;
                    case 8:
                        return TSTI;
                    case 9:
                        return ANDI;
                    case 10:
                        return XORI;
                    case 11:
                        return ORI;
                    case 12:
                        return TSTM;
                    case 13:
                        return ANDM;
                    case 14:
                        return XORM;
                    case 15:
                        return ORM;
                }
            case 13:
                return MOVLI;
            case 14:
                return MOVI;
        }
        return ILLEGAL;
    }

    public final static Sh2Instruction getInstruction(final Sh2Impl sh2, final int opcode) {
        Sh2Inst sh2Inst = getInstruction(opcode);
        switch (sh2Inst) {
            case ADD:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ADD(opcode));
            case ADDC:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ADDC(opcode));
            case ADDI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ADDI(opcode));
            case ADDV:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ADDV(opcode));
            case AND:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.AND(opcode));
            case ANDI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ANDI(opcode));
            case ANDM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ANDM(opcode));
            case BF:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BF(opcode));
            case BFS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BFS(opcode));
            case BRA:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BRA(opcode));
            case BRAF:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BRAF(opcode));
            case BSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BSR(opcode));
            case BSRF:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BSRF(opcode));
            case BT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BT(opcode));
            case BTS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.BTS(opcode));
            case CLRMAC:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CLRMAC(opcode));
            case CLRT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CLRT(opcode));
            case CMPEQ:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPEQ(opcode));
            case CMPGE:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPGE(opcode));
            case CMPGT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPGT(opcode));
            case CMPHI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPHI(opcode));
            case CMPHS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPHS(opcode));
            case CMPIM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPIM(opcode));
            case CMPPL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPPL(opcode));
            case CMPPZ:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPPZ(opcode));
            case CMPSTR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.CMPSTR(opcode));
            case DIV0S:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DIV0S(opcode));
            case DIV0U:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DIV0U(opcode));
            case DIV1:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DIV1(opcode));
            case DMULS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DMULS(opcode));
            case DMULU:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DMULU(opcode));
            case DT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.DT(opcode));
            case EXTSB:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.EXTSB(opcode));
            case EXTSW:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.EXTSW(opcode));
            case EXTUB:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.EXTUB(opcode));
            case EXTUW:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.EXTUW(opcode));
            case ILLEGAL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ILLEGAL(opcode));
            case JMP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.JMP(opcode));
            case JSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.JSR(opcode));
            case LDCGBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCGBR(opcode));
            case LDCMGBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCMGBR(opcode));
            case LDCMSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCMSR(opcode));
            case LDCMVBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCMVBR(opcode));
            case LDCSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCSR(opcode));
            case LDCVBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDCVBR(opcode));
            case LDSMACH:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSMACH(opcode));
            case LDSMACL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSMACL(opcode));
            case LDSMMACH:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSMMACH(opcode));
            case LDSMMACL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSMMACL(opcode));
            case LDSMPR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSMPR(opcode));
            case LDSPR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.LDSPR(opcode));
            case MACL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MACL(opcode));
            case MACW:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MACW(opcode));
            case MOV:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOV(opcode));
            case MOVA:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVA(opcode));
            case MOVBL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBL(opcode));
            case MOVBL0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBL0(opcode));
            case MOVBL4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBL4(opcode));
            case MOVBLG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBLG(opcode));
            case MOVBM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBM(opcode));
            case MOVBP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBP(opcode));
            case MOVBS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBS(opcode));
            case MOVBS0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBS0(opcode));
            case MOVBS4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBS4(opcode));
            case MOVBSG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVBSG(opcode));
            case MOVI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVI(opcode));
            case MOVLI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLI(opcode));
            case MOVLL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLL(opcode));
            case MOVLL0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLL0(opcode));
            case MOVLL4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLL4(opcode));
            case MOVLLG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLLG(opcode));
            case MOVLM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLM(opcode));
            case MOVLP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLP(opcode));
            case MOVLS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLS(opcode));
            case MOVLS0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLS0(opcode));
            case MOVLS4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLS4(opcode));
            case MOVLSG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVLSG(opcode));
            case MOVT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVT(opcode));
            case MOVWI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWI(opcode));
            case MOVWL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWL(opcode));
            case MOVWL0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWL(opcode));
            case MOVWL4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWL(opcode));
            case MOVWLG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWLG(opcode));
            case MOVWM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWM(opcode));
            case MOVWP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWP(opcode));
            case MOVWS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWS(opcode));
            case MOVWS0:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWS0(opcode));
            case MOVWS4:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWS4(opcode));
            case MOVWSG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MOVWSG(opcode));
            case MULL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MULL(opcode));
            case MULSU:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MULSU(opcode));
            case MULSW:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.MULSW(opcode));
            case NEG:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.NEG(opcode));
            case NEGC:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.NEGC(opcode));
            case NOP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.NOP(opcode));
            case NOT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.NOT(opcode));
            case OR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.OR(opcode));
            case ORI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ORI(opcode));
            case ORM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ORM(opcode));
            case ROTCL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ROTCL(opcode));
            case ROTCR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ROTCR(opcode));
            case ROTL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ROTL(opcode));
            case ROTR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ROTR(opcode));
            case RTE:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.RTE(opcode));
            case RTS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.RTS(opcode));
            case SETT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SETT(opcode));
            case SHAL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHAL(opcode));
            case SHAR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHAR(opcode));
            case SHLL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLL(opcode));
            case SHLL16:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLL16(opcode));
            case SHLL2:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLL2(opcode));
            case SHLL8:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLL8(opcode));
            case SHLR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLR(opcode));
            case SHLR16:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLR16(opcode));
            case SHLR2:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLR2(opcode));
            case SHLR8:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SHLR8(opcode));
            case SLEEP:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SLEEP(opcode));
            case STCGBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCGBR(opcode));
            case STCMGBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCMGBR(opcode));
            case STCMSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCMSR(opcode));
            case STCMVBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCMVBR(opcode));
            case STCSR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCSR(opcode));
            case STCVBR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STCVBR(opcode));
            case STSMACH:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSMACH(opcode));
            case STSMACL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSMACL(opcode));
            case STSMMACH:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSMMACH(opcode));
            case STSMMACL:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSMMACL(opcode));
            case STSMPR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSMPR(opcode));
            case STSPR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.STSPR(opcode));
            case SUB:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SUB(opcode));
            case SUBC:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SUBC(opcode));
            case SUBV:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SUBV(opcode));
            case SWAPB:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SWAPB(opcode));
            case SWAPW:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.SWAPW(opcode));
            case TAS:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.TAS(opcode));
            case TRAPA:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.TRAPA(opcode));
            case TST:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.TST(opcode));
            case TSTI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.TSTI(opcode));
            case TSTM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.TSTM(opcode));
            case XOR:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.XOR(opcode));
            case XORI:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.XORI(opcode));
            case XORM:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.XORM(opcode));
            case XTRCT:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.XTRCT(opcode));
            default:
                return new Sh2Instruction(opcode, sh2Inst, () -> sh2.ILLEGAL(opcode));
        }
    }

    static enum Sh2Inst {
        ADD,
        ADDC,
        ADDI,
        ADDV,
        AND,
        ANDI,
        ANDM,
        BF,
        BFS,
        BRA,
        BRAF,
        BSR,
        BSRF,
        BT,
        BTS,
        CLRMAC,
        CLRT,
        CMPEQ,
        CMPGE,
        CMPGT,
        CMPHI,
        CMPHS,
        CMPIM,
        CMPPL,
        CMPPZ,
        CMPSTR,
        DIV0S,
        DIV0U,
        DIV1,
        DMULS,
        DMULU,
        DT,
        EXTSB,
        EXTSW,
        EXTUB,
        EXTUW,
        ILLEGAL,
        JMP,
        JSR,
        LDCGBR,
        LDCMGBR,
        LDCMSR,
        LDCMVBR,
        LDCSR,
        LDCVBR,
        LDSMACH,
        LDSMACL,
        LDSMMACH,
        LDSMMACL,
        LDSMPR,
        LDSPR,
        MACL,
        MACW,
        MOV,
        MOVA,
        MOVBL,
        MOVBL0,
        MOVBL4,
        MOVBLG,
        MOVBM,
        MOVBP,
        MOVBS,
        MOVBS0,
        MOVBS4,
        MOVBSG,
        MOVI,
        MOVLI,
        MOVLL,
        MOVLL0,
        MOVLL4,
        MOVLLG,
        MOVLM,
        MOVLP,
        MOVLS,
        MOVLS0,
        MOVLS4,
        MOVLSG,
        MOVT,
        MOVWI,
        MOVWL,
        MOVWL0,
        MOVWL4,
        MOVWLG,
        MOVWM,
        MOVWP,
        MOVWS,
        MOVWS0,
        MOVWS4,
        MOVWSG,
        MULL,
        MULSU,
        MULSW,
        NEG,
        NEGC,
        NOP,
        NOT,
        OR,
        ORI,
        ORM,
        ROTCL,
        ROTCR,
        ROTL,
        ROTR,
        RTE,
        RTS,
        SETT,
        SHAL,
        SHAR,
        SHLL,
        SHLL16,
        SHLL2,
        SHLL8,
        SHLR,
        SHLR16,
        SHLR2,
        SHLR8,
        SLEEP,
        STCGBR,
        STCMGBR,
        STCMSR,
        STCMVBR,
        STCSR,
        STCVBR,
        STSMACH,
        STSMACL,
        STSMMACH,
        STSMMACL,
        STSMPR,
        STSPR,
        SUB,
        SUBC,
        SUBV,
        SWAPB,
        SWAPW,
        TAS,
        TRAPA,
        TST,
        TSTI,
        TSTM,
        XOR,
        XORI,
        XORM,
        XTRCT;
    }

}