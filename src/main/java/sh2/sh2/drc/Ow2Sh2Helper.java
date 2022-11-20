package sh2.sh2.drc;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import sh2.IMemory;
import sh2.sh2.Sh2Context;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.io.PrintStream;
import java.util.Arrays;

import static org.objectweb.asm.Opcodes.*;
import static sh2.sh2.drc.Ow2Sh2BlockRecompiler.intDesc;
import static sh2.sh2.drc.Ow2Sh2Bytecode.JSR;
import static sh2.sh2.drc.Ow2Sh2Bytecode.NOP;
import static sh2.sh2.drc.Ow2Sh2Bytecode.*;
import static sh2.sh2.drc.Ow2Sh2Helper.SH2CTX_CLASS_FIELD.SR;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2Helper {

    private final static Logger LOG = LogHelper.getLogger(Ow2Sh2Helper.class.getSimpleName());

    /**
     * Boundaries between ASM generated code and normal code
     */
    public enum DRC_CLASS_FIELD {regs, opcodes, sh2DrcContext, sh2Context, sh2MMREG, memory}

    public enum SH2CTX_CLASS_FIELD {PC, PR, SR, GBR, VBR, MACH, MACL, delaySlot, cycles, devices}

    public enum SH2_DRC_CTX_CLASS_FIELD {sh2Ctx, memory}

    public enum SH2_DEVICE_CTX_CLASS_FIELD {sh2MMREG}

    public enum SH2MMREG_METHOD {deviceStep}

    public enum SH2MEMORY_METHOD {read, write}

    //@formatter:off
    public static void createInst(Sh2Prefetch.BytecodeContext ctx) {
//        printString(ctx.mv, ctx.sh2Inst + "," + ctx.opcode);
        switch (ctx.sh2Inst) {
            case ADD -> ADD(ctx);
            case ADDC -> ADDC(ctx);
            case ADDI -> ADDI(ctx);
            case ADDV -> ADDV(ctx);
            case AND -> AND(ctx);
            case ANDI -> ANDI(ctx);
            case ANDM -> ANDM(ctx);
            case BF -> BF(ctx);
            case BFS -> BFS(ctx);
            case BRA -> BRA(ctx);
            case BRAF -> BRAF(ctx);
            case BSR -> BSR(ctx);
            case BSRF -> BSRF(ctx);
            case BT -> BT(ctx);
            case BTS -> BTS(ctx);
            case CLRMAC -> CLRMAC(ctx);
            case CLRT -> CLRT(ctx);
            case CMPEQ -> CMPEQ(ctx);
            case CMPGE -> CMPGE(ctx);
            case CMPGT -> CMPGT(ctx);
            case CMPHI -> CMPHI(ctx);
            case CMPHS -> CMPHS(ctx);
            case CMPIM -> CMPIM(ctx);
            case CMPPL -> CMPPL(ctx);
            case CMPPZ -> CMPPZ(ctx);
            case CMPSTR -> CMPSTR(ctx);
            case DIV0S -> DIV0S(ctx);
            case DIV0U -> DIV0U(ctx);
            case DIV1 -> DIV1(ctx);
            case DMULS -> DMULS(ctx);
            case DMULU -> DMULU(ctx);
            case DT -> DT(ctx);
            case EXTSB -> EXTSB(ctx);
            case EXTSW -> EXTSW(ctx);
            case EXTUB -> EXTUB(ctx);
            case EXTUW -> EXTUW(ctx);
            case ILLEGAL -> ILLEGAL(ctx);
            case JMP -> JMP(ctx);
            case JSR -> JSR(ctx);
            case LDCGBR -> LDCGBR(ctx);
            case LDCMGBR -> LDCMGBR(ctx);
            case LDCMSR -> LDCMSR(ctx);
            case LDCMVBR -> LDCMVBR(ctx);
            case LDCSR -> LDCSR(ctx);
            case LDCVBR -> LDCVBR(ctx);
            case LDSMACH -> LDSMACH(ctx);
            case LDSMACL -> LDSMACL(ctx);
            case LDSMMACH -> LDSMMACH(ctx);
            case LDSMMACL -> LDSMMACL(ctx);
            case LDSMPR -> LDSMPR(ctx);
            case LDSPR -> LDSPR(ctx);
            case MACL -> MACL(ctx);
            case MACW -> MACW(ctx);
            case MOV -> MOV(ctx);
            case MOVA -> MOVA(ctx);
            case MOVBL -> MOVBL(ctx);
            case MOVBL0 -> MOVBL0(ctx);
            case MOVBL4 -> MOVBL4(ctx);
            case MOVBLG -> MOVBLG(ctx);
            case MOVBM -> MOVBM(ctx);
            case MOVBP -> MOVBP(ctx);
            case MOVBS -> MOVBS(ctx);
            case MOVBS0 -> MOVBS0(ctx);
            case MOVBS4 -> MOVBS4(ctx);
            case MOVBSG -> MOVBSG(ctx);
            case MOVI -> MOVI(ctx);
            case MOVLI -> MOVLI(ctx);
            case MOVLL -> MOVLL(ctx);
            case MOVLL0 -> MOVLL0(ctx);
            case MOVLL4 -> MOVLL4(ctx);
            case MOVLLG -> MOVLLG(ctx);
            case MOVLM -> MOVLM(ctx);
            case MOVLP -> MOVLP(ctx);
            case MOVLS -> MOVLS(ctx);
            case MOVLS0 -> MOVLS0(ctx);
            case MOVLS4 -> MOVLS4(ctx);
            case MOVLSG -> MOVLSG(ctx);
            case MOVT -> MOVT(ctx);
            case MOVWI -> MOVWI(ctx);
            case MOVWL -> MOVWL(ctx);
            case MOVWL0 -> MOVWL0(ctx);
            case MOVWL4 -> MOVWL4(ctx);
            case MOVWLG -> MOVWLG(ctx);
            case MOVWM -> MOVWM(ctx);
            case MOVWP -> MOVWP(ctx);
            case MOVWS -> MOVWS(ctx);
            case MOVWS0 -> MOVWS0(ctx);
            case MOVWS4 -> MOVWS4(ctx);
            case MOVWSG -> MOVWSG(ctx);
            case MULL -> MULL(ctx);
            case MULSU -> MULSU(ctx);
            case MULSW -> MULSW(ctx);
            case NEG -> NEG(ctx);
            case NEGC -> NEGC(ctx);
            case NOP -> NOP(ctx);
            case NOT -> NOT(ctx);
            case OR -> OR(ctx);
            case ORI -> ORI(ctx);
            case ORM -> ORM(ctx);
            case ROTCL -> ROTCL(ctx);
            case ROTCR -> ROTCR(ctx);
            case ROTL -> ROTL(ctx);
            case ROTR -> ROTR(ctx);
            case RTE -> RTE(ctx);
            case RTS -> RTS(ctx);
            case SETT -> SETT(ctx);
            case SHAL -> SHAL(ctx);
            case SHAR -> SHAR(ctx);
            case SHLL -> SHLL(ctx);
            case SHLL16 -> SHLL16(ctx);
            case SHLL2 -> SHLL2(ctx);
            case SHLL8 -> SHLL8(ctx);
            case SHLR -> SHLR(ctx);
            case SHLR16 -> SHLR16(ctx);
            case SHLR2 -> SHLR2(ctx);
            case SHLR8 -> SHLR8(ctx);
            case SLEEP -> SLEEP(ctx);
            case STCGBR -> STCGBR(ctx);
            case STCMGBR -> STCMGBR(ctx);
            case STCMSR -> STCMSR(ctx);
            case STCMVBR -> STCMVBR(ctx);
            case STCSR -> STCSR(ctx);
            case STCVBR -> STCVBR(ctx);
            case STSMACH -> STSMACH(ctx);
            case STSMACL -> STSMACL(ctx);
            case STSMMACH -> STSMMACH(ctx);
            case STSMMACL -> STSMMACL(ctx);
            case STSMPR -> STSMPR(ctx);
            case STSPR -> STSPR(ctx);
            case SUB -> SUB(ctx);
            case SUBC -> SUBC(ctx);
            case SUBV -> SUBV(ctx);
            case SWAPB -> SWAPB(ctx);
            case SWAPW -> SWAPW(ctx);
            case TAS -> TAS(ctx);
            case TRAPA -> TRAPA(ctx);
            case TST -> TST(ctx);
            case TSTI -> TSTI(ctx);
            case TSTM -> TSTM(ctx);
            case XOR -> XOR(ctx);
            case XORI -> XORI(ctx);
            case XORM -> XORM(ctx);
            case XTRCT -> XTRCT(ctx);
            default -> {
                LOG.warn("Fallback: {}", ctx.sh2Inst);
                System.out.println("Fallback: " + ctx.sh2Inst);
                fallback(ctx);
            }
        }
    }
    //@formatter:on

    public static void printString(Sh2Prefetch.BytecodeContext ctx, String str) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        ctx.mv.visitLdcInsn(str);
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)));
    }

    public static void printRegValue(Sh2Prefetch.BytecodeContext ctx, int reg) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        pushRegStack(ctx, reg);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(int.class)));
    }

    public static void printSh2ContextField(Sh2Prefetch.BytecodeContext ctx, String name, Class<?> clazz) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        pushSh2Context(ctx);
        ctx.mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Context.class), name, Type.getDescriptor(clazz));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(clazz)));
    }

    public static void popSh2ContextIntField(Sh2Prefetch.BytecodeContext ctx, String name) {
        ctx.mv.visitFieldInsn(PUTFIELD, Type.getInternalName(Sh2Context.class), name, intDesc);
    }

    /**
     * Pop the value top of the stack and stores it in Sh2Context::SR
     * A reference to Sh2Context should already be on the stack.
     */
    public static void popSR(Sh2Prefetch.BytecodeContext ctx) {
        ctx.mv.visitFieldInsn(PUTFIELD, Type.getInternalName(Sh2Context.class), SR.name(), intDesc);
    }

    /**
     * Push Sh2Context::SR on the stack, a reference to Sh2Context should already be on the stack.
     */
    public static void pushSR(Sh2Prefetch.BytecodeContext ctx) {
        pushField(ctx, Sh2Context.class, SR.name(), int.class);
    }

    public static void pushField(Sh2Prefetch.BytecodeContext ctx, Class<?> refClass, String fieldName, Class<?> fieldClass) {
        ctx.mv.visitFieldInsn(GETFIELD, Type.getInternalName(refClass), fieldName, Type.getDescriptor(fieldClass));
    }

    public static void pushSh2Context(Sh2Prefetch.BytecodeContext ctx) {
        ctx.mv.visitVarInsn(ALOAD, 0); //this
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, DRC_CLASS_FIELD.sh2Context.name(), Type.getDescriptor(Sh2Context.class));
    }

    public static void pushMemory(Sh2Prefetch.BytecodeContext ctx) {
        ctx.mv.visitVarInsn(ALOAD, 0); //this
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, DRC_CLASS_FIELD.memory.name(), Type.getDescriptor(IMemory.class));
    }

    public static void pushSh2ContextIntField(Sh2Prefetch.BytecodeContext ctx, String name) {
        pushSh2Context(ctx);
        pushField(ctx, Sh2Context.class, name, int.class);
    }

    public static void sh2PushReg15(Sh2Prefetch.BytecodeContext ctx) {
        //NOTE the value to push must be on the top of the stack
        int valIdx = ctx.mv.newLocal(Type.INT_TYPE);
        ctx.mv.visitVarInsn(ISTORE, valIdx);
        decReg(ctx, 15, 4);
        pushMemory(ctx);
        pushRegStack(ctx, 15);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitVarInsn(ILOAD, valIdx);
        writeMem(ctx, Size.LONG);
    }

    /**
     * NOTE the result of pop will be on the top of the stack
     */
    public static void sh2PopReg15(Sh2Prefetch.BytecodeContext ctx) {
        int resIdx = ctx.mv.newLocal(Type.INT_TYPE);
        pushMemory(ctx);
        pushRegStack(ctx, 15);
        ctx.mv.visitInsn(IALOAD);
        readMem(ctx, Size.LONG);
        ctx.mv.visitVarInsn(ISTORE, resIdx);
        pushRegStack(ctx, 15);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(ICONST_4);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitInsn(IASTORE);
        ctx.mv.visitVarInsn(ILOAD, resIdx);
    }


    private static void printArrayField(Sh2Prefetch.BytecodeContext ctx, String name, String fieldDesc) {
        if (!addPrintStuff) {
            return;
        }
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        ctx.mv.visitVarInsn(ALOAD, 0); // push `this`
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, name, fieldDesc);
        ctx.mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Arrays.class), "toString",
                Type.getMethodDescriptor(Type.getType(String.class), Type.getType(int[].class)));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class)));
    }

    private static void emitPrintField(Sh2Prefetch.BytecodeContext ctx, String name, Class<?> clazz) {
        if (!addPrintStuff) {
            return;
        }
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(System.class), "out", Type.getDescriptor(PrintStream.class));
        ctx.mv.visitVarInsn(ALOAD, 0); // push `this`
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, name, Type.getDescriptor(clazz));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(PrintStream.class), "println",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(clazz)));
    }

    public static void emitPushLongConstToStack(Sh2Prefetch.BytecodeContext ctx, long val) {
        if (val == 0 || val == 1) {
            ctx.mv.visitInsn((int) (LCONST_0 + val));
        } else {
            ctx.mv.visitLdcInsn(val);
        }
    }

    public static void emitPushConstToStack(Sh2Prefetch.BytecodeContext ctx, int val) {
        if (val >= 0 && val <= 5) {
            ctx.mv.visitInsn(ICONST_0 + val);
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            ctx.mv.visitIntInsn(BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            ctx.mv.visitIntInsn(SIPUSH, val);
        } else {
            ctx.mv.visitLdcInsn(val);
        }
    }

    /**
     * {
     * int d = ...
     * byte b = (byte)d;
     * or
     * short s = (short)d
     * }
     */
    public static void emitCastIntToSize(Sh2Prefetch.BytecodeContext ctx, Size size) {
        switch (size) {
            case BYTE:
                ctx.mv.visitInsn(I2B);
                break;
            case WORD:
                ctx.mv.visitInsn(I2S);
                break;
        }
    }
}
