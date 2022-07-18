package sh2.sh2.drc;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;
import sh2.Sh2Memory;
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

    private final static Logger LOG = LogManager.getLogger(Ow2Sh2Helper.class.getSimpleName());

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
            case ADD:
                ADD(ctx);
                break;
            case ADDC:
                ADDC(ctx);
                break;
            case ADDI:
                ADDI(ctx);
                break;
            case ADDV:
                ADDV(ctx);
                break;
            case AND:
                AND(ctx);
                break;
            case ANDI:
                ANDI(ctx);
                break;
            case ANDM:
                ANDM(ctx);
                break;
            case BF:
                BF(ctx);
                break;
            case BFS:
                BFS(ctx);
                break;
            case BRA:
                BRA(ctx);
                break;
            case BRAF:
                BRAF(ctx);
                break;
            case BSR:
                BSR(ctx);
                break;
            case BSRF:
                BSRF(ctx);
                break;
            case BT:
                BT(ctx);
                break;
            case BTS:
                BTS(ctx);
                break;
            case CLRMAC:
                CLRMAC(ctx);
                break;
            case CLRT:
                CLRT(ctx);
                break;
            case CMPEQ:
                CMPEQ(ctx);
                break;
            case CMPGE:
                CMPGE(ctx);
                break;
            case CMPGT:
                CMPGT(ctx);
                break;
            case CMPHI:
                CMPHI(ctx);
                break;
            case CMPHS:
                CMPHS(ctx);
                break;
            case CMPIM:
                CMPIM(ctx);
                break;
            case CMPPL:
                CMPPL(ctx);
                break;
            case CMPPZ:
                CMPPZ(ctx);
                break;
            case CMPSTR:
                CMPSTR(ctx);
                break;
            case DIV0S:
                DIV0S(ctx);
                break;
            case DIV0U:
                DIV0U(ctx);
                break;
            case DIV1:
                DIV1(ctx);
                break;
            case DMULS:
                DMULS(ctx);
                break;
            case DMULU:
                DMULU(ctx);
                break;
            case DT:
                DT(ctx);
                break;
            case EXTSB:
                EXTSB(ctx);
                break;
            case EXTSW:
                EXTSW(ctx);
                break;
            case EXTUB:
                EXTUB(ctx);
                break;
            case EXTUW:
                EXTUW(ctx);
                break;
            case ILLEGAL:
                ILLEGAL(ctx);
                break;
            case JMP:
                JMP(ctx);
                break;
            case JSR:
                JSR(ctx);
                break;
            case LDCGBR:
                LDCGBR(ctx);
                break;
            case LDCMGBR:
                LDCMGBR(ctx);
                break;
            case LDCMSR:
                LDCMSR(ctx);
                break;
            case LDCMVBR:
                LDCMVBR(ctx);
                break;
            case LDCSR:
                LDCSR(ctx);
                break;
            case LDCVBR:
                LDCVBR(ctx);
                break;
            case LDSMACH:
                LDSMACH(ctx);
                break;
            case LDSMACL:
                LDSMACL(ctx);
                break;
            case LDSMMACH:
                LDSMMACH(ctx);
                break;
            case LDSMMACL:
                LDSMMACL(ctx);
                break;
            case LDSMPR:
                LDSMPR(ctx);
                break;
            case LDSPR:
                LDSPR(ctx);
                break;
            case MACL:
                MACL(ctx);
                break;
            case MACW:
                MACW(ctx);
                break;
            case MOV:
                MOV(ctx);
                break;
            case MOVA:
                MOVA(ctx);
                break;
            case MOVBL:
                MOVBL(ctx);
                break;
            case MOVBL0:
                MOVBL0(ctx);
                break;
            case MOVBL4:
                MOVBL4(ctx);
                break;
            case MOVBLG:
                MOVBLG(ctx);
                break;
            case MOVBM:
                MOVBM(ctx);
                break;
            case MOVBP:
                MOVBP(ctx);
                break;
            case MOVBS:
                MOVBS(ctx);
                break;
            case MOVBS0:
                MOVBS0(ctx);
                break;
            case MOVBS4:
                MOVBS4(ctx);
                break;
            case MOVBSG:
                MOVBSG(ctx);
                break;
            case MOVI:
                MOVI(ctx);
                break;
            case MOVLI:
                MOVLI(ctx);
                break;
            case MOVLL:
                MOVLL(ctx);
                break;
            case MOVLL0:
                MOVLL0(ctx);
                break;
            case MOVLL4:
                MOVLL4(ctx);
                break;
            case MOVLLG:
                MOVLLG(ctx);
                break;
            case MOVLM:
                MOVLM(ctx);
                break;
            case MOVLP:
                MOVLP(ctx);
                break;
            case MOVLS:
                MOVLS(ctx);
                break;
            case MOVLS0:
                MOVLS0(ctx);
                break;
            case MOVLS4:
                MOVLS4(ctx);
                break;
            case MOVLSG:
                MOVLSG(ctx);
                break;
            case MOVT:
                MOVT(ctx);
                break;
            case MOVWI:
                MOVWI(ctx);
                break;
            case MOVWL:
                MOVWL(ctx);
                break;
            case MOVWL0:
                MOVWL0(ctx);
                break;
            case MOVWL4:
                MOVWL4(ctx);
                break;
            case MOVWLG:
                MOVWLG(ctx);
                break;
            case MOVWM:
                MOVWM(ctx);
                break;
            case MOVWP:
                MOVWP(ctx);
                break;
            case MOVWS:
                MOVWS(ctx);
                break;
            case MOVWS0:
                MOVWS0(ctx);
                break;
            case MOVWS4:
                MOVWS4(ctx);
                break;
            case MOVWSG:
                MOVWSG(ctx);
                break;
            case MULL:
                MULL(ctx);
                break;
            case MULSU:
                MULSU(ctx);
                break;
            case MULSW:
                MULSW(ctx);
                break;
            case NEG:
                NEG(ctx);
                break;
            case NEGC:
                NEGC(ctx);
                break;
            case NOP:
                NOP(ctx);
                break;
            case NOT:
                NOT(ctx);
                break;
            case OR:
                OR(ctx);
                break;
            case ORI:
                ORI(ctx);
                break;
            case ORM:
                ORM(ctx);
                break;
            case ROTCL:
                ROTCL(ctx);
                break;
            case ROTCR:
                ROTCR(ctx);
                break;
            case ROTL:
                ROTL(ctx);
                break;
            case ROTR:
                ROTR(ctx);
                break;
            case RTE:
                RTE(ctx);
                break;
            case RTS:
                RTS(ctx);
                break;
            case SETT:
                SETT(ctx);
                break;
            case SHAL:
                SHAL(ctx);
                break;
            case SHAR:
                SHAR(ctx);
                break;
            case SHLL:
                SHLL(ctx);
                break;
            case SHLL16:
                SHLL16(ctx);
                break;
            case SHLL2:
                SHLL2(ctx);
                break;
            case SHLL8:
                SHLL8(ctx);
                break;
            case SHLR:
                SHLR(ctx);
                break;
            case SHLR16:
                SHLR16(ctx);
                break;
            case SHLR2:
                SHLR2(ctx);
                break;
            case SHLR8:
                SHLR8(ctx);
                break;
            case SLEEP:
                SLEEP(ctx);
                break;
            case STCGBR:
                STCGBR(ctx);
                break;
            case STCMGBR:
                STCMGBR(ctx);
                break;
            case STCMSR:
                STCMSR(ctx);
                break;
            case STCMVBR:
                STCMVBR(ctx);
                break;
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
            case STSMMACH:
                STSMMACH(ctx);
                break;
            case STSMMACL:
                STSMMACL(ctx);
                break;
            case STSMPR:
                STSMPR(ctx);
                break;
            case STSPR:
                STSPR(ctx);
                break;
            case SUB:
                SUB(ctx);
                break;
            case SUBC:
                SUBC(ctx);
                break;
            case SUBV:
                SUBV(ctx);
                break;
            case SWAPB:
                SWAPB(ctx);
                break;
            case SWAPW:
                SWAPW(ctx);
                break;
            case TAS:
                TAS(ctx);
                break;
            case TRAPA:
                TRAPA(ctx);
                break;
            case TST:
                TST(ctx);
                break;
            case TSTI:
                TSTI(ctx);
                break;
            case TSTM:
                TSTM(ctx);
                break;
            case XOR:
                XOR(ctx);
                break;
            case XORI:
                XORI(ctx);
                break;
            case XORM:
                XORM(ctx);
                break;
            case XTRCT:
                XTRCT(ctx);
                break;
            default:
                fallback(ctx);
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
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, DRC_CLASS_FIELD.memory.name(), Type.getDescriptor(Sh2Memory.class));
    }

    public static void pushSh2ContextAndField(Sh2Prefetch.BytecodeContext ctx, String name, Class<?> clazz) {
        pushSh2Context(ctx);
        pushField(ctx, Sh2Context.class, name, clazz);
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
        ctx.mv.visitLdcInsn(val);
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
