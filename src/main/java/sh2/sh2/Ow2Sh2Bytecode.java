package sh2.sh2;

import omegadrive.util.Size;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.PrintStream;
import java.util.Arrays;

import static org.objectweb.asm.Opcodes.*;
import static sh2.sh2.Ow2Sh2BlockRecompiler.*;
import static sh2.sh2.Sh2Impl.RM;
import static sh2.sh2.Sh2Impl.RN;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2Bytecode {

    private static final boolean addPrintStuff = false;

    static class BytecodeContext {
        public Sh2Impl sh2;
        public MethodVisitor mv;
        public Sh2Context sh2Ctx;
        public int opcode;
        public Sh2Instructions.Sh2Inst sh2Inst;
    }

    /**
     * @see Sh2Impl#ADD(int)
     */
    private static void ADD(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitInsn(IASTORE);
        singleInstTail(ctx.mv, 1);
    }

    private static void ADDI(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        byte b = (byte) (ctx.opcode & 0xff);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitVarInsn(ILOAD, b);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitInsn(IASTORE);
        singleInstTail(ctx.mv, 1);
    }

    public static void MOVI(BytecodeContext ctx) {
        int reg = (ctx.opcode >> 8) & 0xF;
        storeToReg(ctx, reg, ctx.opcode, Size.BYTE);
        singleInstTail(ctx.mv, 1);
    }

    protected final static void STCSR(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.SR, Size.LONG);
        singleInstTail(ctx.mv, 2);
    }

    private static void STCVBR(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.VBR, Size.LONG);
        singleInstTail(ctx.mv, 2);
    }

    private static void STCGBR(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.GBR, Size.LONG);
        singleInstTail(ctx.mv, 2);
    }

    private static void STSMACH(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.MACH, Size.LONG);
        singleInstTail(ctx.mv, 1);
    }

    private static void STSMACL(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.MACH, Size.LONG);
        singleInstTail(ctx.mv, 1);
    }

    private static void STSPR(BytecodeContext ctx) {
        storeToReg(ctx, RN(ctx.opcode), ctx.sh2Ctx.PR, Size.LONG);
        singleInstTail(ctx.mv, 2);
    }

    private static void SUB(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(ISUB);
        ctx.mv.visitInsn(IASTORE);
        singleInstTail(ctx.mv, 1);
    }

    public static void fallback(BytecodeContext ctx) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Sh2Instructions.class), "instOpcodeMap",
                Type.getDescriptor(Sh2Instructions.Sh2Instruction[].class));
        ctx.mv.visitLdcInsn(ctx.opcode);
        ctx.mv.visitInsn(AALOAD);
        ctx.mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Instructions.Sh2Instruction.class), "runnable",
                Type.getDescriptor(Runnable.class));
        ctx.mv.visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(Runnable.class), "run", noArgsNoRetDesc);
    }

    private static void pushRegStack(BytecodeContext ctx, int reg) {
        ctx.mv.visitVarInsn(ALOAD, 0); //this
        ctx.mv.visitFieldInsn(GETFIELD, blockClassDesc, "regs", intArrayDesc);
        ctx.mv.visitIntInsn(BIPUSH, reg);
    }

    private static void storeToReg(BytecodeContext ctx, int reg, int val, Size size) {
        pushRegStack(ctx, reg);
        switch (size) {
            case BYTE:
                ctx.mv.visitIntInsn(BIPUSH, val);
                break;
            case WORD:
            case LONG:
                ctx.mv.visitLdcInsn(val);
                break;
        }
        ctx.mv.visitInsn(IASTORE);
    }

    /**
     * {
     * cycles -= val;
     * PC += 2;
     * }
     */
    public static void singleInstTail(MethodVisitor mv, int minusCycles) {
        mv.visitVarInsn(ALOAD, 0); // push `this`
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, blockClassDesc, "cycles", intDesc);
        switch (minusCycles) {
            case 1:
                mv.visitInsn(ICONST_1);
                break;
            case 2:
                mv.visitInsn(ICONST_2);
                break;
            default:
                new RuntimeException("" + minusCycles);
        }
        mv.visitInsn(ISUB);
        mv.visitFieldInsn(PUTFIELD, blockClassDesc, "cycles", intDesc);

        mv.visitVarInsn(ALOAD, 0); // push `this`
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, blockClassDesc, "pc", intDesc);
        mv.visitInsn(ICONST_2);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTFIELD, blockClassDesc, "pc", intDesc);
    }

    public static boolean createInst(BytecodeContext ctx) {
//        printString(ctx.mv, ctx.sh2Inst + "," + ctx.opcode);
        switch (ctx.sh2Inst) {
            case ADD:
                ADD(ctx);
                break;
//            case ADDC: ADDC(ctx); break;
            case ADDI:
                ADDI(ctx);
                break;
//            case ADDV: ADDV(ctx); break;
//            case AND: AND(ctx); break;
//            case ANDI: ANDI(ctx); break;
//            case ANDM: ANDM(ctx); break;
//            case BF: BF(ctx); break;
//            case BFS: BFS(ctx); break;
//            case BRA: BRA(ctx); break;
//            case BRAF: BRAF(ctx); break;
//            case BSR: BSR(ctx); break;
//            case BSRF: BSRF(ctx); break;
//            case BT: BT(ctx); break;
//            case BTS: BTS(ctx); break;
//            case CLRMAC: CLRMAC(ctx); break;
//            case CLRT: CLRT(ctx); break;
//            case CMPEQ: CMPEQ(ctx); break;
//            case CMPGE: CMPGE(ctx); break;
//            case CMPGT: CMPGT(ctx); break;
//            case CMPHI: CMPHI(ctx); break;
//            case CMPHS: CMPHS(ctx); break;
//            case CMPIM: CMPIM(ctx); break;
//            case CMPPL: CMPPL(ctx); break;
//            case CMPPZ: CMPPZ(ctx); break;
//            case CMPSTR: CMPSTR(ctx); break;
//            case DIV0S: DIV0S(ctx); break;
//            case DIV0U: DIV0U(ctx); break;
//            case DIV1: DIV1(ctx); break;
//            case DMULS: DMULS(ctx); break;
//            case DMULU: DMULU(ctx); break;
//            case DT: DT(ctx); break;
//            case EXTSB: EXTSB(ctx); break;
//            case EXTSW: EXTSW(ctx); break;
//            case EXTUB: EXTUB(ctx); break;
//            case EXTUW: EXTUW(ctx); break;
//            case ILLEGAL: ILLEGAL(ctx); break;
//            case JMP: JMP(ctx); break;
//            case JSR: JSR(ctx); break;
//            case LDCGBR: LDCGBR(ctx); break;
//            case LDCMGBR: LDCMGBR(ctx); break;
//            case LDCMSR: LDCMSR(ctx); break;
//            case LDCMVBR: LDCMVBR(ctx); break;
//            case LDCSR: LDCSR(ctx); break;
//            case LDCVBR: LDCVBR(ctx); break;
//            case LDSMACH: LDSMACH(ctx); break;
//            case LDSMACL: LDSMACL(ctx); break;
//            case LDSMMACH: LDSMMACH(ctx); break;
//            case LDSMMACL: LDSMMACL(ctx); break;
//            case LDSMPR: LDSMPR(ctx); break;
//            case LDSPR: LDSPR(ctx); break;
//            case MACL: MACL(ctx); break;
//            case MACW: MACW(ctx); break;
//            case MOV: MOV(ctx); break;
//            case MOVA: MOVA(ctx); break;
//            case MOVBL: MOVBL(ctx); break;
//            case MOVBL0: MOVBL0(ctx); break;
//            case MOVBL4: MOVBL4(ctx); break;
//            case MOVBLG: MOVBLG(ctx); break;
//            case MOVBM: MOVBM(ctx); break;
//            case MOVBP: MOVBP(ctx); break;
//            case MOVBS: MOVBS(ctx); break;
//            case MOVBS0: MOVBS0(ctx); break;
//            case MOVBS4: MOVBS4(ctx); break;
//            case MOVBSG: MOVBSG(ctx); break;
            case MOVI:
                MOVI(ctx);
                break;
//            case MOVLI: MOVLI(ctx); break;
//            case MOVLL: MOVLL(ctx); break;
//            case MOVLL0: MOVLL0(ctx); break;
//            case MOVLL4: MOVLL4(ctx); break;
//            case MOVLLG: MOVLLG(ctx); break;
//            case MOVLM: MOVLM(ctx); break;
//            case MOVLP: MOVLP(ctx); break;
//            case MOVLS: MOVLS(ctx); break;
//            case MOVLS0: MOVLS0(ctx); break;
//            case MOVLS4: MOVLS4(ctx); break;
//            case MOVLSG: MOVLSG(ctx); break;
//            case MOVT: MOVT(ctx); break;
//            case MOVWI: MOVWI(ctx); break;
//            case MOVWL: MOVWL(ctx); break;
//            case MOVWL0: MOVWL0(ctx); break;
//            case MOVWL4: MOVWL4(ctx); break;
//            case MOVWLG: MOVWLG(ctx); break;
//            case MOVWM: MOVWM(ctx); break;
//            case MOVWP: MOVWP(ctx); break;
//            case MOVWS: MOVWS(ctx); break;
//            case MOVWS0: MOVWS0(ctx); break;
//            case MOVWS4: MOVWS4(ctx); break;
//            case MOVWSG: MOVWSG(ctx); break;
//            case MULL: MULL(ctx); break;
//            case MULSU: MULSU(ctx); break;
//            case MULSW: MULSW(ctx); break;
//            case NEG: NEG(ctx); break;
//            case NEGC: NEGC(ctx); break;
//            case NOP: NOP(ctx); break;
//            case NOT: NOT(ctx); break;
//            case OR: OR(ctx); break;
//            case ORI: ORI(ctx); break;
//            case ORM: ORM(ctx); break;
//            case ROTCL: ROTCL(ctx); break;
//            case ROTCR: ROTCR(ctx); break;
//            case ROTL: ROTL(ctx); break;
//            case ROTR: ROTR(ctx); break;
//            case RTE: RTE(ctx); break;
//            case RTS: RTS(ctx); break;
//            case SETT: SETT(ctx); break;
//            case SHAL: SHAL(ctx); break;
//            case SHAR: SHAR(ctx); break;
//            case SHLL: SHLL(ctx); break;
//            case SHLL16: SHLL16(ctx); break;
//            case SHLL2: SHLL2(ctx); break;
//            case SHLL8: SHLL8(ctx); break;
//            case SHLR: SHLR(ctx); break;
//            case SHLR16: SHLR16(ctx); break;
//            case SHLR2: SHLR2(ctx); break;
//            case SHLR8: SHLR8(ctx); break;
//            case SLEEP: SLEEP(ctx); break;
            case STCGBR:
                STCGBR(ctx);
                break;
//            case STCMGBR: STCMGBR(ctx); break;
//            case STCMSR: STCMSR(ctx); break;
//            case STCMVBR: STCMVBR(ctx); break;
            case STCSR:
                STCSR(ctx);
                break;
            case STCVBR:
                STCVBR(ctx);
                break;
            case STSMACH:
                STSMACH(ctx);
                break;
            case STSMACL:
                STSMACL(ctx);
                break;
//            case STSMMACH: STSMMACH(ctx); break;
//            case STSMMACL: STSMMACL(ctx); break;
//            case STSMPR: STSMPR(ctx); break;
            case STSPR:
                STSPR(ctx);
                break;
            case SUB:
                SUB(ctx);
                break;
//            case SUBC: SUBC(ctx); break;
//            case SUBV: SUBV(ctx); break;
//            case SWAPB: SWAPB(ctx); break;
//            case SWAPW: SWAPW(ctx); break;
//            case TAS: TAS(ctx); break;
//            case TRAPA: TRAPA(ctx); break;
//            case TST: TST(ctx); break;
//            case TSTI: TSTI(ctx); break;
//            case TSTM: TSTM(ctx); break;
//            case XOR: XOR(ctx); break;
//            case XORI: XORI(ctx); break;
//            case XORM: XORM(ctx); break;
//            case XTRCT: XTRCT(ctx); break;
            default:
                fallback(ctx);
                System.err.println("Unimplemented: " + ctx.sh2Inst + "," + ctx.opcode);
                return false;
        }
        return true;
    }

    private static void printString(MethodVisitor mv, String str) {
        mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        mv.visitLdcInsn(str);
        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)));
    }

    private static void printArrayField(MethodVisitor mv, String name, String fieldDesc) {
        if (!addPrintStuff) {
            return;
        }
        mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        mv.visitVarInsn(ALOAD, 0); // push `this`
        mv.visitFieldInsn(GETFIELD, blockClassDesc, name, fieldDesc);
        mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Arrays.class), "toString",
                Type.getMethodDescriptor(Type.getType(String.class), Type.getType(int[].class)));
        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)));
    }

    private static void printField(MethodVisitor mv, String name, Class<?> clazz) {
        if (!addPrintStuff) {
            return;
        }
        mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        mv.visitVarInsn(ALOAD, 0); // push `this`
        mv.visitFieldInsn(GETFIELD, blockClassDesc, name, Type.getDescriptor(clazz));
        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(clazz)));
    }
}
