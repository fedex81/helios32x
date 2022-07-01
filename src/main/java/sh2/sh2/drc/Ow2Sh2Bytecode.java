package sh2.sh2.drc;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import sh2.Sh2MMREG;
import sh2.Sh2Memory;
import sh2.sh2.*;
import sh2.sh2.prefetch.Sh2Prefetch.BytecodeContext;

import java.util.HashSet;
import java.util.Set;

import static omegadrive.util.Util.th;
import static org.objectweb.asm.Opcodes.*;
import static sh2.sh2.Sh2.*;
import static sh2.sh2.Sh2Impl.RM;
import static sh2.sh2.Sh2Impl.RN;
import static sh2.sh2.drc.Ow2Sh2BlockRecompiler.*;
import static sh2.sh2.drc.Ow2Sh2Helper.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2Bytecode {

    private final static Logger LOG = LogManager.getLogger(Ow2Sh2Bytecode.class.getSimpleName());

    public static final boolean addPrintStuff = false, printMissingOpcodes = false;
    private static final Set<String> instSet = new HashSet<>();

    /**
     * @see Sh2Impl#ADD(int)
     */
    public static void ADD(BytecodeContext ctx) {
        opRegToReg(ctx, IADD);
    }


    public static final void ADDC(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void ADDI(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        byte b = (byte) (ctx.opcode & 0xff);
        opRegImm(ctx, IADD, n, b);
    }

    public static final void ADDV(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void AND(BytecodeContext ctx) {
        opRegToReg(ctx, IAND);
    }

    public static void ANDI(BytecodeContext ctx) {
        opReg0Imm(ctx, IAND, ((ctx.opcode >> 0) & 0xff));
    }

    public static void ANDM(BytecodeContext ctx) {
        opReg0Mem(ctx, IAND);
    }

    public static void BF(BytecodeContext ctx) {
        int d = (byte) (ctx.opcode & 0xFF) << 1;
        int newPcJump = ctx.pc + 4 + d;
        branchInternal(ctx, newPcJump, true, false);
    }

    public static void BT(BytecodeContext ctx) {
        int d = (byte) (ctx.opcode & 0xFF) << 1;
        int newPcJump = ctx.pc + 4 + d;
        branchInternal(ctx, newPcJump, false, false);
    }

    private static void branchInternal(BytecodeContext ctx, int pcJump, boolean isBF, boolean isDelaySlot) {
        Label elseLbl = new Label();
        Label doneLbl = new Label();
        pushSh2ContextAndField(ctx, "SR", int.class);
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitJumpInsn(isBF ? IFNE : IFEQ, elseLbl);
        pushSh2Context(ctx);
        pushIntConstStack(ctx, pcJump);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cyclesBranch);
        if (isDelaySlot) {
            delaySlot(ctx);
        }
        ctx.mv.visitJumpInsn(GOTO, doneLbl);
        ctx.mv.visitLabel(elseLbl);
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 2);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);
        ctx.mv.visitLabel(doneLbl);
    }

    public static void BFS(BytecodeContext ctx) {
        int d = (byte) (ctx.opcode & 0xFF) << 1;
        int newPcJump = ctx.pc + 4 + d;
        branchInternal(ctx, newPcJump, true, true);
    }

    public static void BRA(BytecodeContext ctx) {
        int disp;

        if ((ctx.opcode & 0x800) == 0)
            disp = (0x00000FFF & ctx.opcode);
        else disp = (0xFFFFF000 | ctx.opcode);

        int d = 4 + (disp << 1);

        //PC = PC + d
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + d);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void BRAF(BytecodeContext ctx) {
        int n = RN(ctx.opcode);

        //PC = reg[n] + (pc + 4)
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 4);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void BSR(BytecodeContext ctx) {
        int disp = 0;
        if ((ctx.opcode & 0x800) == 0)
            disp = (0x00000FFF & ctx.opcode);
        else disp = (0xFFFFF000 | ctx.opcode);

        int d = (disp << 1) + 4;

        //PR = pc + 4;
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 4);
        popSh2ContextIntField(ctx, "PR");
        //PC = pc + d;
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + d);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void BSRF(BytecodeContext ctx) {
        int n = RN(ctx.opcode);

        //PR = pc + 4;
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 4);
        popSh2ContextIntField(ctx, "PR");

        //PC = reg[n] + (pc + 4)
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 4);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void BTS(BytecodeContext ctx) {
        int d = (byte) (ctx.opcode & 0xFF) << 1;
        int newPcJump = ctx.pc + 4 + d;
        branchInternal(ctx, newPcJump, false, true);
    }

    public static void CLRMAC(BytecodeContext ctx) {
        pushSh2Context(ctx);
        pushSh2Context(ctx);
        pushIntConstStack(ctx, 0);
        ctx.mv.visitInsn(DUP_X1);
        popSh2ContextIntField(ctx, "MACH");
        popSh2ContextIntField(ctx, "MACL");
    }

    public static void CLRT(BytecodeContext ctx) {
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);
        popSR(ctx);
    }

    public static final void CMPEQ(BytecodeContext ctx) {
        cmpRegToReg(ctx, IF_ICMPNE);
    }

    public static final void CMPGE(BytecodeContext ctx) {
        cmpRegToReg(ctx, IF_ICMPLT);
    }

    public static final void CMPGT(BytecodeContext ctx) {
        cmpRegToReg(ctx, IF_ICMPLE);
    }

    public static final void CMPHS(BytecodeContext ctx) {
        cmpRegToRegUnsigned(ctx, IFLT);
    }

    public static final void CMPHI(BytecodeContext ctx) {
        cmpRegToRegUnsigned(ctx, IFLE);
    }

    public static final void CMPIM(BytecodeContext ctx) {
        int i = (byte) (ctx.opcode & 0xFF);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, i);
        cmpInternal(ctx, IF_ICMPNE);

    }

    public static final void CMPPL(BytecodeContext ctx) {
        cmpRegToZero(ctx, RN(ctx.opcode), IFLE);
    }

    public static final void CMPPZ(BytecodeContext ctx) {
        cmpRegToZero(ctx, RN(ctx.opcode), IFLT);
    }

    public static final void CMPSTR(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void cmpRegToZero(BytecodeContext ctx, int reg, int cmpOpcode) {
        pushRegStack(ctx, reg);
        ctx.mv.visitInsn(IALOAD);
        cmpInternal(ctx, cmpOpcode);
    }

    public static void cmpRegToReg(BytecodeContext ctx, int cmpOpcode) {
        int n = RN(ctx.opcode);
        int m = RM(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        cmpInternal(ctx, cmpOpcode);

    }

    public static void cmpRegToRegUnsigned(BytecodeContext ctx, int cmpOpcode) {
        int n = RN(ctx.opcode);
        int m = RM(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        pushLongConstStack(ctx, 0xFFFF_FFFFL);
        ctx.mv.visitInsn(LAND);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        pushLongConstStack(ctx, 0xFFFF_FFFFL);
        ctx.mv.visitInsn(LAND);
        ctx.mv.visitInsn(LCMP);
        cmpInternal(ctx, cmpOpcode);
    }


    /**
     * if(a cmp b){
     * ctx.SR |= flagT;
     * } else {
     * ctx.SR &= ~flagT;
     * }
     */
    public static void cmpInternal(BytecodeContext ctx, int cmpOpcode) {
        Label elseLabel = new Label();
        Label endLabel = new Label();
        ctx.mv.visitJumpInsn(cmpOpcode, elseLabel);
        pushSh2Context(ctx); //then branch
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IOR);
        popSR(ctx);
        ctx.mv.visitJumpInsn(GOTO, endLabel); //then branch end
        ctx.mv.visitLabel(elseLabel);
        pushSh2Context(ctx); //else branch
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);
        popSR(ctx); //else branch end
        ctx.mv.visitLabel(endLabel);
    }

    public static void DIV0S(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);

        //SR &= ~(flagQ | flagM | flagT);
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~(flagM | flagQ | flagT));
        ctx.mv.visitInsn(IAND);
        popSR(ctx);

        // (((regs[10] >>> 31) & 1) << posQ)
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 31);
        ctx.mv.visitInsn(IUSHR);
//        pushIntConstStack(ctx ,1);  // &1 can be skipped
//        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, posQ);
        ctx.mv.visitInsn(ISHL);

        //  OR (((regs[11] >>> 31) & 1) << posM)
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 31);
        ctx.mv.visitInsn(IUSHR);
        pushIntConstStack(ctx, posM);
        ctx.mv.visitInsn(ISHL);
        ctx.mv.visitInsn(IOR);

        // OR ((((regs[11] ^ regs[10]) >>> 31) & 1) << posT);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IXOR);
        pushIntConstStack(ctx, 31);
        ctx.mv.visitInsn(IUSHR);
//        pushIntConstStack(ctx , posT); // << 0 can be skipped
//        ctx.mv.visitInsn(ISHL);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IOR);

        popSR(ctx);
    }

    public static void DIV1(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void DIV0U(BytecodeContext ctx) {
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~(flagQ | flagM | flagT));
        ctx.mv.visitInsn(IAND);
        popSR(ctx);

    }

    public static void DMULS(BytecodeContext ctx) {
        assert LocalVariablesSorter.class.isInstance(ctx.mv);
        int longVarIdx = ctx.mv.newLocal(Type.LONG_TYPE);
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        ctx.mv.visitInsn(LMUL);
        ctx.mv.visitVarInsn(LSTORE, longVarIdx);
        storeToMAC(ctx, longVarIdx);

    }

    public static void DMULU(BytecodeContext ctx) {
        assert LocalVariablesSorter.class.isInstance(ctx.mv);
        int longVarIdx = ctx.mv.newLocal(Type.LONG_TYPE);
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        ctx.mv.visitLdcInsn(0xffffffffL);
        ctx.mv.visitInsn(LAND);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2L);
        ctx.mv.visitLdcInsn(0xffffffffL);
        ctx.mv.visitInsn(LAND);
        ctx.mv.visitInsn(LMUL);
        ctx.mv.visitVarInsn(LSTORE, longVarIdx);
        storeToMAC(ctx, longVarIdx);
    }

    public static void DT(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        decReg(ctx, n, 1);
        cmpRegToZero(ctx, n, IFNE);
    }

    public static void EXTSB(BytecodeContext ctx) {
        extSigned(ctx, Size.BYTE);
    }

    public static void EXTSW(BytecodeContext ctx) {
        extSigned(ctx, Size.WORD);
    }

    public static void EXTUB(BytecodeContext ctx) {
        extUnsigned(ctx, Size.BYTE);
    }

    public static void EXTUW(BytecodeContext ctx) {
        extUnsigned(ctx, Size.WORD);
    }

    public static void ILLEGAL(BytecodeContext ctx) {
        LOG.error("{} illegal instruction: {}\n{}", ctx.drcCtx.sh2Ctx.cpuAccess, th(ctx.opcode),
                Sh2Helper.toDebuggingString(ctx.drcCtx.sh2Ctx));
        pushSh2ContextAndField(ctx, "PC", int.class);
        pushSh2ContextAndField(ctx, "PC", int.class);
        pushSh2ContextAndField(ctx, "SR", int.class);
        sh2PushReg15(ctx);
        sh2PushReg15(ctx);
        pushSh2ContextAndField(ctx, "VBR", int.class);
        pushIntConstStack(ctx, ILLEGAL_INST_VN << 2);
        ctx.mv.visitInsn(IADD);
        readMem(ctx, Size.LONG);
        ctx.mv.visitFieldInsn(PUTFIELD, Type.getInternalName(Sh2Context.class), "PC", intDesc);
    }

    public static void JMP(BytecodeContext ctx) {
        int n = RN(ctx.opcode);

        //PC = reg[n]
        pushSh2Context(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void JSR(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        //PR = PC + 4
        pushSh2Context(ctx);
        pushIntConstStack(ctx, ctx.pc + 4);
        popSh2ContextIntField(ctx, "PR");

        //PC = reg[n]
        pushSh2Context(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static void LDCGBR(BytecodeContext ctx) {
        ldcReg(ctx, "GBR", -1, 1);
    }

    public static void LDCSR(BytecodeContext ctx) {
        ldcReg(ctx, "SR", Sh2.SR_MASK, 1);
    }

    public static void LDCVBR(BytecodeContext ctx) {
        ldcReg(ctx, "VBR", -1, 1);
    }

    public static final void LDCMGBR(BytecodeContext ctx) {
        ldcmReg(ctx, "GBR", -1, 3);
    }

    public static final void LDCMSR(BytecodeContext ctx) {
        ldcmReg(ctx, "SR", SR_MASK, 3);
    }

    public static final void LDCMVBR(BytecodeContext ctx) {
        ldcmReg(ctx, "VBR", -1, 3);
    }

    public static void LDSMACH(BytecodeContext ctx) {
        ldcReg(ctx, "MACH", -1, 2);
    }

    public static void LDSMACL(BytecodeContext ctx) {
        ldcReg(ctx, "MACL", -1, 2);
    }

    public static void LDSPR(BytecodeContext ctx) {
        ldcReg(ctx, "PR", -1, 1);
    }

    public static final void LDSMMACH(BytecodeContext ctx) {
        ldcmReg(ctx, "MACH", -1, 1);
    }

    public static final void LDSMMACL(BytecodeContext ctx) {
        ldcmReg(ctx, "MACL", -1, 1);
    }

    public static final void LDSMPR(BytecodeContext ctx) {
        ldcmReg(ctx, "PR", -1, 1);
    }

    public static final void MACL(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static final void MACW(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void MOV(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        int m = RM(ctx.opcode);
        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void MOVA(BytecodeContext ctx) {
        int dShift = (ctx.opcode & 0x000000ff) << 2;
        int pcRef = ((ctx.pc + 4) & 0xfffffffc) + dShift;
        int refIdx = ctx.mv.newLocal(Type.INT_TYPE);
        assert !ctx.drcCtx.sh2Ctx.delaySlot;
        Label endLabel = new Label();
        pushIntConstStack(ctx, pcRef);
        ctx.mv.visitVarInsn(ISTORE, refIdx);
        pushSh2ContextAndField(ctx, "delaySlot", boolean.class);
        ctx.mv.visitJumpInsn(IFEQ, endLabel);
        //else { ref = ((ctx.delayPC + 2) & 0xfffffffc) + (d << 2);}
        pushSh2ContextAndField(ctx, "delaySlot", boolean.class);
        pushIntConstStack(ctx, 2);
        ctx.mv.visitInsn(IADD);
        pushIntConstStack(ctx, 0xfffffffc);
        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, dShift);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitVarInsn(ISTORE, refIdx);
        ctx.mv.visitLabel(endLabel);
        // end of else
        pushRegStack(ctx, 0);
        ctx.mv.visitVarInsn(ILOAD, refIdx);
        ctx.mv.visitInsn(IASTORE);

    }

    public final static void MOVBL(BytecodeContext ctx) {
        movMemToReg(ctx, Size.BYTE);
    }

    public final static void MOVWL(BytecodeContext ctx) {
        movMemToReg(ctx, Size.WORD);
    }

    public final static void MOVLL(BytecodeContext ctx) {
        movMemToReg(ctx, Size.LONG);
    }

    public final static void MOVBL0(BytecodeContext ctx) {
        movMemWithReg0ShiftToReg(ctx, Size.BYTE);
    }

    public final static void MOVWL0(BytecodeContext ctx) {
        movMemWithReg0ShiftToReg(ctx, Size.WORD);
    }

    public final static void MOVLL0(BytecodeContext ctx) {
        movMemWithReg0ShiftToReg(ctx, Size.LONG);
    }

    public final static void MOVBL4(BytecodeContext ctx) {
        movMemToRegShift(ctx, Size.BYTE);
    }

    public final static void MOVWL4(BytecodeContext ctx) {
        movMemToRegShift(ctx, Size.WORD);
    }

    public final static void MOVLL4(BytecodeContext ctx) {
        movMemToRegShift(ctx, Size.LONG);
    }

    public final static void MOVBLG(BytecodeContext ctx) {
        movMemWithGBRShiftToReg0(ctx, Size.BYTE);
    }

    public final static void MOVWLG(BytecodeContext ctx) {
        movMemWithGBRShiftToReg0(ctx, Size.WORD);
    }

    public final static void MOVLLG(BytecodeContext ctx) {
        movMemWithGBRShiftToReg0(ctx, Size.LONG);
    }

    public static final void MOVBP(BytecodeContext ctx) {
        movMemToRegPostInc(ctx, Size.BYTE);
    }

    public static final void MOVWP(BytecodeContext ctx) {
        movMemToRegPostInc(ctx, Size.WORD);
    }

    public static final void MOVLP(BytecodeContext ctx) {
        movMemToRegPostInc(ctx, Size.LONG);
    }

    public final static void MOVBM(BytecodeContext ctx) {
        movRegPredecToMem(ctx, Size.BYTE);
    }

    public final static void MOVWM(BytecodeContext ctx) {
        movRegPredecToMem(ctx, Size.WORD);
    }

    public final static void MOVLM(BytecodeContext ctx) {
        movRegPredecToMem(ctx, Size.LONG);
    }

    public final static void MOVBS(BytecodeContext ctx) {
        movRegToReg(ctx, Size.BYTE);
    }

    public final static void MOVWS(BytecodeContext ctx) {
        movRegToReg(ctx, Size.WORD);
    }

    public final static void MOVLS(BytecodeContext ctx) {
        movRegToReg(ctx, Size.LONG);
    }

    public final static void MOVBS0(BytecodeContext ctx) {
        movRegToMemWithReg0Shift(ctx, Size.BYTE);
    }

    public final static void MOVWS0(BytecodeContext ctx) {
        movRegToMemWithReg0Shift(ctx, Size.WORD);
    }

    public final static void MOVLS0(BytecodeContext ctx) {
        movRegToMemWithReg0Shift(ctx, Size.LONG);
    }

    public static final void MOVBS4(BytecodeContext ctx) {
        movRegToRegShift(ctx, Size.BYTE);
    }

    public static final void MOVWS4(BytecodeContext ctx) {
        movRegToRegShift(ctx, Size.WORD);
    }

    public static final void MOVLS4(BytecodeContext ctx) {
        movRegToRegShift(ctx, Size.LONG);
    }

    public static final void MOVBSG(BytecodeContext ctx) {
        movGBRShiftToReg0(ctx, Size.BYTE);
    }

    public static final void MOVWSG(BytecodeContext ctx) {
        movGBRShiftToReg0(ctx, Size.WORD);
    }

    public static final void MOVLSG(BytecodeContext ctx) {
        movGBRShiftToReg0(ctx, Size.LONG);
    }

    public static void MOVI(BytecodeContext ctx) {
        int reg = (ctx.opcode >> 8) & 0xF;
        storeToReg(ctx, reg, ctx.opcode, Size.BYTE);

    }

    public static void MOVT(BytecodeContext ctx) {
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        pushSh2ContextAndField(ctx, "SR", int.class);
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IASTORE);

    }

    public static final void MOVWI(BytecodeContext ctx) {
        movMemWithPcOffsetToReg(ctx, Size.WORD);
    }

    public static final void MOVLI(BytecodeContext ctx) {
        movMemWithPcOffsetToReg(ctx, Size.LONG);
    }

    public static void MULL(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushSh2Context(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IMUL);
        ctx.mv.visitInsn(ICONST_M1);
        ctx.mv.visitInsn(IAND);
        popSh2ContextIntField(ctx, "MACL");

    }

    public static void MULSW(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushSh2Context(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2S);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(I2S);
        ctx.mv.visitInsn(IMUL);
        popSh2ContextIntField(ctx, "MACL");

    }

    public static void MULSU(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushSh2Context(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitLdcInsn(0xffff);
        ctx.mv.visitInsn(IAND);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitLdcInsn(0xffff);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IMUL);
        popSh2ContextIntField(ctx, "MACL");

    }

    public static void NEG(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(ICONST_0);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(ISUB);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void NEGC(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void NOP(BytecodeContext ctx) {
        //nop
    }

    public static void NOT(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(ICONST_M1);
        ctx.mv.visitInsn(IXOR);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void OR(BytecodeContext ctx) {
        opRegToReg(ctx, IOR);
    }

    public static void ORI(BytecodeContext ctx) {
        opReg0Imm(ctx, IOR, ((ctx.opcode >> 0) & 0xff));
    }

    public static void ORM(BytecodeContext ctx) {
        opReg0Mem(ctx, IOR);
    }

    public static final void ROTCL(BytecodeContext ctx) {
        rotateRegWithCarry(ctx, true);
    }

    public static final void ROTCR(BytecodeContext ctx) {
        rotateRegWithCarry(ctx, false);
    }

    public static final void ROTL(BytecodeContext ctx) {
        rotateReg(ctx, true);
    }

    public static final void ROTR(BytecodeContext ctx) {
        rotateReg(ctx, false);
    }

    public static final void RTE(BytecodeContext ctx) {
        pushSh2Context(ctx);
        sh2PopReg15(ctx);
        popSh2ContextIntField(ctx, "PC");

        pushSh2Context(ctx);
        sh2PopReg15(ctx);
        pushIntConstStack(ctx, SR_MASK);
        ctx.mv.visitInsn(IAND);
        popSh2ContextIntField(ctx, "SR");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    public static final void RTS(BytecodeContext ctx) {
        //store PR to PC
        pushSh2Context(ctx);
        pushSh2ContextAndField(ctx, "PR", int.class);
        popSh2ContextIntField(ctx, "PC");
        subCyclesExt(ctx, ctx.sh2Inst.cycles);

        delaySlot(ctx);
    }

    private static void delaySlot(BytecodeContext ctx) {
        assert ctx.delaySlotCtx != null && ctx.delaySlotCtx.delaySlot && !ctx.delaySlot;
        assert ctx.drcCtx.sh2Ctx == ctx.delaySlotCtx.drcCtx.sh2Ctx;
        createInst(ctx.delaySlotCtx);
    }

    public final static void SETT(BytecodeContext ctx) {
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        ctx.mv.visitInsn(ICONST_1); //Sh2.flagT
        ctx.mv.visitInsn(IOR);
        popSR(ctx);

    }

    public final static void SLEEP(BytecodeContext ctx) {
        //do nothing
    }

    public final static void STCSR(BytecodeContext ctx) {
        stsToReg(ctx, "SR", 2);
    }

    public static void STCVBR(BytecodeContext ctx) {
        stsToReg(ctx, "VBR", 2);
    }

    public static void STCGBR(BytecodeContext ctx) {
        stsToReg(ctx, "GBR", 2);
    }

    public static final void STCMGBR(BytecodeContext ctx) {
        stsMem(ctx, "GBR", 2);
    }

    public static final void STCMSR(BytecodeContext ctx) {
        stsMem(ctx, "SR", 2);
    }

    public static final void STCMVBR(BytecodeContext ctx) {
        stsMem(ctx, "VBR", 2);
    }

    public static final void STSMMACH(BytecodeContext ctx) {
        stsMem(ctx, "MACH", 1);
    }

    public static final void STSMMACL(BytecodeContext ctx) {
        stsMem(ctx, "MACL", 1);
    }

    public static final void STSMPR(BytecodeContext ctx) {
        stsMem(ctx, "PR", 1);
    }

    public static void STSMACH(BytecodeContext ctx) {
        stsToReg(ctx, "MACH", 1);
    }

    public static void STSMACL(BytecodeContext ctx) {
        stsToReg(ctx, "MACL", 1);
    }

    public static void STSPR(BytecodeContext ctx) {
        stsToReg(ctx, "PR", 2);
    }

    public static void SUB(BytecodeContext ctx) {
        opRegToReg(ctx, ISUB);
    }

    public static void SUBC(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static void SUBV(BytecodeContext ctx) {
        fallback(ctx);
    }

    public static final void SHAL(BytecodeContext ctx) {
        shiftArithmetic(ctx, true);
    }

    public static final void SHAR(BytecodeContext ctx) {
        shiftArithmetic(ctx, false);
    }

    public static final void SHLL(BytecodeContext ctx) {
        shiftLogical(ctx, true);
    }

    public static final void SHLR(BytecodeContext ctx) {
        shiftLogical(ctx, false);
    }

    public static void SHLL2(BytecodeContext ctx) {
        shiftConst(ctx, ISHL, 2);
    }

    public static void SHLL8(BytecodeContext ctx) {
        shiftConst(ctx, ISHL, 8);
    }

    public static void SHLL16(BytecodeContext ctx) {
        shiftConst(ctx, ISHL, 16);
    }

    public static void SHLR2(BytecodeContext ctx) {
        shiftConst(ctx, IUSHR, 2);
    }

    public static void SHLR8(BytecodeContext ctx) {
        shiftConst(ctx, IUSHR, 8);
    }

    public static void SHLR16(BytecodeContext ctx) {
        shiftConst(ctx, IUSHR, 16);
    }

    public static void SWAPB(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        int temp0Idx = ctx.mv.newLocal(Type.INT_TYPE);
        int temp1Idx = ctx.mv.newLocal(Type.INT_TYPE);

        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 0xFFFF0000);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitVarInsn(ISTORE, temp0Idx);

        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 0xFF);
        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, 8);
        ctx.mv.visitInsn(ISHL);
        ctx.mv.visitVarInsn(ISTORE, temp1Idx);

        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 0xFF00);
        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, 8);
        ctx.mv.visitInsn(ISHR);
        ctx.mv.visitInsn(IASTORE);

        pushRegStack(ctx, n);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitVarInsn(ILOAD, temp1Idx);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitVarInsn(ILOAD, temp0Idx);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void SWAPW(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);

        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 16);
        ctx.mv.visitInsn(ISHL);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 16);
        ctx.mv.visitInsn(IUSHR);
        pushIntConstStack(ctx, 0xFFFF);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void TAS(BytecodeContext ctx) {
        int n = RN(ctx.opcode);

        int valIdx = ctx.mv.newLocal(Type.INT_TYPE);

        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        readMem(ctx, Size.BYTE);
        ctx.mv.visitInsn(DUP);
        ctx.mv.visitVarInsn(ISTORE, valIdx);

        cmpInternal(ctx, IFNE);

        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitVarInsn(ILOAD, valIdx);
        pushIntConstStack(ctx, 0x80);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(I2B);
        writeMem(ctx, Size.BYTE);
    }

    /**
     * :
     * LINE A 34
     * BIPUSH 27
     * ISTORE code
     * B:
     * LINE B 35
     * SIPUSH 255
     * ILOAD code
     * IAND
     * ISTORE imm
     * C:
     * LINE C 40
     * ALOAD this
     * GETFIELD sh2/AsmExample.sh2Context Lsh2/sh2/Sh2Context;
     * ALOAD this
     * GETFIELD sh2/AsmExample.memory Lsh2/Sh2Memory;
     * ALOAD this
     * GETFIELD sh2/AsmExample.sh2Context Lsh2/sh2/Sh2Context;
     * GETFIELD sh2/sh2/Sh2Context.VBR I
     * ILOAD imm
     * ICONST_2
     * ISHL
     * IADD
     * INVOKEVIRTUAL sh2/Sh2Memory.read32(I)I
     * ICONST_4
     * IADD
     * PUTFIELD sh2/sh2/Sh2Context.PC I
     * D:
     * LINE D 41
     * RETURN
     * E:
     */
    public static void TRAPA(BytecodeContext ctx) {
//        int imm = (0xFF & ctx.opcode);
//
//        pushSh2Context(ctx);
//        pushSR(ctx);
//        sh2PushReg15(ctx);
//        pushIntConstStack(ctx, ctx.pc + 2);
//        sh2PushReg15(ctx);
        //TODO PC change is unsupported

        fallback(ctx);
    }

    public static void TST(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IAND);
        cmpInternal(ctx, IFNE);

    }

    public static void TSTI(BytecodeContext ctx) {
        int i = ((ctx.opcode >> 0) & 0xff);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, i);
        ctx.mv.visitInsn(IAND);
        cmpInternal(ctx, IFNE);

    }

    public static void TSTM(BytecodeContext ctx) {
        int i = ((ctx.opcode >> 0) & 0xff);
        pushMemory(ctx);
        pushSh2ContextAndField(ctx, "GBR", int.class);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        readMem(ctx, Size.BYTE);
        pushIntConstStack(ctx, i);
        ctx.mv.visitInsn(IAND);
        cmpInternal(ctx, IFNE);
    }

    public static void XOR(BytecodeContext ctx) {
        opRegToReg(ctx, IXOR);
    }

    public static void XORI(BytecodeContext ctx) {
        opReg0Imm(ctx, IXOR, ((ctx.opcode >> 0) & 0xff));
    }

    public static void XORM(BytecodeContext ctx) {
        LOG.warn("Check this: XORM");
        opReg0Mem(ctx, IXOR); //TODO test
    }

    public static void XTRCT(BytecodeContext ctx) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);

        pushRegStack(ctx, n);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 0xffff0000);
        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, 16);
        ctx.mv.visitInsn(IUSHR);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 0xffff);
        ctx.mv.visitInsn(IAND);
        pushIntConstStack(ctx, 16);
        ctx.mv.visitInsn(ISHL);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void fallback(BytecodeContext ctx) {
        if (printMissingOpcodes) {
            if (instSet.add(ctx.sh2Inst.name())) {
                LOG.warn("DRC unimplemented: {},{}", ctx.sh2Inst, ctx.opcode);
            }
        }
        setContextPc(ctx);
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Sh2Instructions.class), "instOpcodeMap",
                Type.getDescriptor(Sh2Instructions.Sh2InstructionWrapper[].class));
        ctx.mv.visitLdcInsn(ctx.opcode);
        ctx.mv.visitInsn(AALOAD);
        ctx.mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Instructions.Sh2InstructionWrapper.class), "runnable",
                Type.getDescriptor(Runnable.class));
        ctx.mv.visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(Runnable.class), "run", noArgsNoRetDesc);
    }

    public static void shiftConst(BytecodeContext ctx, int shiftBytecode, int shift) {
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        switch (shift) {
            case 2:
                ctx.mv.visitInsn(ICONST_2);
                break;
            case 8:
                ctx.mv.visitIntInsn(BIPUSH, 8);
                break;
            case 16:
                ctx.mv.visitIntInsn(BIPUSH, 16);
                break;
        }
        ctx.mv.visitInsn(shiftBytecode);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void opRegToReg(BytecodeContext ctx, int opBytecode) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(opBytecode);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void opReg0Imm(BytecodeContext ctx, int opBytecode, int i) {
        opRegImm(ctx, opBytecode, 0, i);
    }

    public static void opRegImm(BytecodeContext ctx, int opBytecode, int reg, int i) {
        pushRegStack(ctx, reg);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, i);
        ctx.mv.visitInsn(opBytecode);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void opReg0Mem(BytecodeContext ctx, int opBytecode) {
        int i = (byte) ((ctx.opcode >> 0) & 0xff);
        int memValIdx = ctx.mv.newLocal(Type.INT_TYPE);
        int memAddrIdx = ctx.mv.newLocal(Type.INT_TYPE);

        pushSh2ContextAndField(ctx, "GBR", int.class);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitVarInsn(ISTORE, memAddrIdx);

        pushMemory(ctx);
        ctx.mv.visitVarInsn(ILOAD, memAddrIdx);
        readMem(ctx, Size.BYTE);
        pushCastFromInt(ctx, Size.BYTE);
        ctx.mv.visitVarInsn(ISTORE, memValIdx);

        pushMemory(ctx);
        ctx.mv.visitVarInsn(ILOAD, memAddrIdx);
        ctx.mv.visitVarInsn(ILOAD, memValIdx);
        pushIntConstStack(ctx, i);
        ctx.mv.visitInsn(opBytecode);
        writeMem(ctx, Size.BYTE);
    }

    public static void stsToReg(BytecodeContext ctx, String source, int cycles) {
        pushRegStack(ctx, RN(ctx.opcode));
        pushSh2ContextAndField(ctx, source, int.class);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void stsMem(BytecodeContext ctx, String source, int cycles) {
        int n = RN(ctx.opcode);
        decReg(ctx, n, 4);
        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushSh2ContextAndField(ctx, source, int.class);
        writeMem(ctx, Size.LONG);
    }

    public static void ldcReg(BytecodeContext ctx, String dest, int mask, int cycles) {
        pushSh2Context(ctx);
        int m = RN(ctx.opcode);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        if (mask > 0) {
            pushIntConstStack(ctx, Sh2.SR_MASK);
            ctx.mv.visitInsn(IAND);
        }
        popSh2ContextIntField(ctx, dest);
    }

    public static void ldcmReg(BytecodeContext ctx, String src, int mask, int cycles) {
        int m = RN(ctx.opcode);
        pushSh2Context(ctx);
        pushMemory(ctx);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        readMem(ctx, Size.LONG);
        if (mask > 0) {
            pushIntConstStack(ctx, mask);
            ctx.mv.visitInsn(IAND);
        }
        popSh2ContextIntField(ctx, src);

        pushRegStack(ctx, m);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(ICONST_4);
        ctx.mv.visitInsn(IADD);
        ctx.mv.visitInsn(IASTORE);
    }


    /**
     * LEFT
     * ctx.SR &= ~flagT;
     * ctx.SR |= (ctx.registers[n] >>> 31) & flagT;
     * ctx.registers[n] = (ctx.registers[n] << 1) | (ctx.registers[n] >>> 31);
     * <p>
     * RIGHT
     * ctx.SR &= ~flagT;
     * ctx.SR |= ctx.registers[n] & flagT;
     * ctx.registers[n] = (ctx.registers[n] >>> 1) | (ctx.registers[n] << 31);
     */
    public static void rotateReg(BytecodeContext ctx, boolean left) {
        int n = RN(ctx.opcode);
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);

        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        if (left) {
            pushIntConstStack(ctx, 31);
            ctx.mv.visitInsn(IUSHR);
        }
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IOR);
        popSR(ctx);

        pushRegStack(ctx, n);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(left ? ISHL : IUSHR);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 31);
        ctx.mv.visitInsn(left ? IUSHR : ISHL);
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IASTORE);


    }

    /**
     * LEFT
     * int msbit = (ctx.registers[n] >>> 31) & 1;
     * ctx.registers[n] = (ctx.registers[n] << 1) | (ctx.SR & flagT);
     * ctx.SR &= ~flagT;
     * ctx.SR |= msbit;
     * <p>
     * RIGHT
     * int lsbit = ctx.registers[n] & 1;
     * ctx.registers[n] = (ctx.registers[n] >>> 1) | ((ctx.SR & flagT) << 31);
     * ctx.SR &= ~flagT;
     * ctx.SR |= lsbit;
     */
    public static void rotateRegWithCarry(BytecodeContext ctx, boolean left) {
        int n = RN(ctx.opcode);
        int highLowBit = ctx.mv.newLocal(Type.INT_TYPE);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        if (left) {
            pushIntConstStack(ctx, 31);
            ctx.mv.visitInsn(IUSHR);
        }
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitVarInsn(ISTORE, highLowBit);

        pushRegStack(ctx, n);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(left ? ISHL : IUSHR);
        pushSh2ContextAndField(ctx, "SR", int.class);
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IAND);
        if (!left) {
            pushIntConstStack(ctx, 31);
            ctx.mv.visitInsn(ISHL);
        }
        ctx.mv.visitInsn(IOR);
        ctx.mv.visitInsn(IASTORE);

        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitVarInsn(ILOAD, highLowBit);
        ctx.mv.visitInsn(IOR);
        popSR(ctx);

    }

    /**
     * RIGHT
     * ctx.SR &= (~flagT);
     * ctx.SR |= ctx.registers[n] & 1;
     * ctx.registers[n] = ctx.registers[n] >> 1;
     * <p>
     * LEFT
     * ctx.SR &= (~flagT);
     * ctx.SR |= (ctx.registers[n] >>> 31) & 1;
     * ctx.registers[n] = ctx.registers[n] << 1;
     */
    public static void shiftArithmetic(BytecodeContext ctx, boolean left) {
        int n = RN(ctx.opcode);
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        pushSR(ctx);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);

        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        if (left) {
            pushIntConstStack(ctx, 31);
            ctx.mv.visitInsn(IUSHR);
        }
        pushIntConstStack(ctx, flagT);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IOR);
        popSR(ctx);

        pushRegStack(ctx, n);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(left ? ISHL : ISHR);
        ctx.mv.visitInsn(IASTORE);

    }

    /**
     * LEFT
     * ctx.SR = (ctx.SR & ~flagT) | ((ctx.registers[n] >>> 31) & 1);
     * ctx.registers[n] <<= 1;
     * RIGHT
     * ctx.SR = (ctx.SR & ~flagT) | (ctx.registers[n] & 1);
     * ctx.registers[n] >>>= 1;
     */
    public static void shiftLogical(BytecodeContext ctx, boolean left) {
        int n = RN(ctx.opcode);

        pushSh2Context(ctx);
        pushSh2ContextAndField(ctx, "SR", int.class);
        pushIntConstStack(ctx, ~flagT);
        ctx.mv.visitInsn(IAND);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        if (left) {
            pushIntConstStack(ctx, 31);
            ctx.mv.visitInsn(IUSHR);
        }
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IOR);
        popSR(ctx);

        pushRegStack(ctx, n);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, 1);
        ctx.mv.visitInsn(left ? ISHL : IUSHR);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void extSigned(BytecodeContext ctx, Size size) {
        assert size != Size.LONG;
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void extUnsigned(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitLdcInsn((int) size.getMask());
        ctx.mv.visitInsn(IAND);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movMemToReg(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);

        pushRegStack(ctx, n);
        pushMemory(ctx);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movMemToRegShift(BytecodeContext ctx, Size size) {
        int d = ((ctx.opcode >> 0) & 0x0f);
        int n = size == Size.LONG ? RN(ctx.opcode) : 0;
        int m = RM(ctx.opcode);
        pushRegStack(ctx, n);
        pushMemory(ctx);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, d);
        switch (size) {
            case WORD:
                pushIntConstStack(ctx, 1);
                ctx.mv.visitInsn(ISHL);
                break;
            case LONG:
                pushIntConstStack(ctx, 2);
                ctx.mv.visitInsn(ISHL);
                break;
        }
        ctx.mv.visitInsn(IADD);
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movRegToReg(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        writeMem(ctx, size);

    }

    public static void movRegToRegShift(BytecodeContext ctx, Size size) {
        int d = ((ctx.opcode >> 0) & 0x0f);
        int n = size == Size.LONG ? RN(ctx.opcode) : RM(ctx.opcode);
        int m = size == Size.LONG ? RM(ctx.opcode) : 0;
        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, d);
        switch (size) {
            case WORD:
                pushIntConstStack(ctx, 1);
                ctx.mv.visitInsn(ISHL);
                break;
            case LONG:
                pushIntConstStack(ctx, 2);
                ctx.mv.visitInsn(ISHL);
                break;
        }
        ctx.mv.visitInsn(IADD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        writeMem(ctx, size);

    }

    public static void movRegToMemWithReg0Shift(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushCastFromInt(ctx, size);
        writeMem(ctx, size);

    }

    public static void movMemWithReg0ShiftToReg(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        pushRegStack(ctx, n);
        pushMemory(ctx);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        ctx.mv.visitInsn(IADD);
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movMemWithPcOffsetToReg(BytecodeContext ctx, Size size) {
        assert size != Size.BYTE;
        int d = (ctx.opcode & 0xff);
        int n = ((ctx.opcode >> 8) & 0x0f);

        pushRegStack(ctx, n);
        pushMemory(ctx);

        //If this instruction is placed immediately after a delayed branch instruction, the PC must
        //point to an address specified by (the starting address of the branch destination) + 2.
        int memAddr = size == Size.WORD ? ctx.pc + 4 + (d << 1) : (ctx.pc & 0xfffffffc) + 4 + (d << 2);
        if (false && ctx.delaySlot) { //TODO
//            //int memAddr = !ctx.delaySlot ? ctx.PC + 4 + (d << 1) : ctx.delayPC + 2 + (d << 1);
//            int offset = size == Size.WORD ? 4 + (d << 1) : 0;
//            pushSh2ContextAndField(ctx, "PC", int.class);
//            pushIntConstStack(ctx, offset);
//            ctx.mv.visitInsn(IADD);
        } else {
            pushIntConstStack(ctx, memAddr);
        }
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movRegPredecToMem(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);

        int predecVal = size == Size.BYTE ? 1 : (size == Size.WORD ? 2 : 4);

        decReg(ctx, n, predecVal);
        pushMemory(ctx);
        pushRegStack(ctx, n);
        ctx.mv.visitInsn(IALOAD);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        writeMem(ctx, size);

    }

    public static void movMemToRegPostInc(BytecodeContext ctx, Size size) {
        int m = RM(ctx.opcode);
        int n = RN(ctx.opcode);
        int postIncVal = size == Size.BYTE ? 1 : (size == Size.WORD ? 2 : 4);
        pushRegStack(ctx, n);
        pushMemory(ctx);
        pushRegStack(ctx, m);
        ctx.mv.visitInsn(IALOAD);
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);
        if (n != m) {
            pushRegStack(ctx, m);
            ctx.mv.visitInsn(DUP2);
            ctx.mv.visitInsn(IALOAD);
            pushIntConstStack(ctx, postIncVal);
            ctx.mv.visitInsn(IADD);
            ctx.mv.visitInsn(IASTORE);
        }

    }

    public static void movMemWithGBRShiftToReg0(BytecodeContext ctx, Size size) {
        int d = ((ctx.opcode >> 0) & 0xff);
        int shiftVal = size == Size.BYTE ? 0 : (size == Size.WORD ? 1 : 2);
        pushRegStack(ctx, 0);
        pushMemory(ctx);
        pushSh2ContextAndField(ctx, "GBR", int.class);
        pushIntConstStack(ctx, d);
        pushIntConstStack(ctx, shiftVal);
        ctx.mv.visitInsn(ISHL);
        ctx.mv.visitInsn(IADD);
        readMem(ctx, size);
        pushCastFromInt(ctx, size);
        ctx.mv.visitInsn(IASTORE);

    }

    public static void movGBRShiftToReg0(BytecodeContext ctx, Size size) {
        int v = size == Size.BYTE ? 0 : (size == Size.WORD ? 1 : 2);
        int gbrOffset = (ctx.opcode & 0xff) << v;
        pushMemory(ctx);
        pushSh2ContextAndField(ctx, "GBR", int.class);
        pushIntConstStack(ctx, gbrOffset);
        ctx.mv.visitInsn(IADD);
        pushRegStack(ctx, 0);
        ctx.mv.visitInsn(IALOAD);
        writeMem(ctx, size);

    }


    public static void writeMem(BytecodeContext ctx, Size size) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Size.class), size.name(), Type.getDescriptor(Size.class));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(Sh2Memory.class), "write",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.getType(Size.class)));
    }

    public static void readMem(BytecodeContext ctx, Size size) {
        ctx.mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Size.class), size.name(), Type.getDescriptor(Size.class));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(Sh2Memory.class), "read",
                Type.getMethodDescriptor(Type.INT_TYPE, Type.INT_TYPE, Type.getType(Size.class)));
    }

    /**
     * {
     * regs[n] -= val
     * }
     */
    public static void decReg(BytecodeContext ctx, int reg, int val) {
        pushRegStack(ctx, reg);
        ctx.mv.visitInsn(DUP2);
        ctx.mv.visitInsn(IALOAD);
        pushIntConstStack(ctx, val);
        ctx.mv.visitInsn(ISUB);
        ctx.mv.visitInsn(IASTORE);
    }

    public static void pushRegStack(BytecodeContext ctx, int reg) {
        ctx.mv.visitVarInsn(ALOAD, 0); //this
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, "regs", intArrayDesc);
        pushIntConstStack(ctx, reg);
    }


    public static void storeToReg(BytecodeContext ctx, int reg, int val, Size size) {
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

    //store the long at varIndex to MACH, MACL
    public static void storeToMAC(BytecodeContext ctx, int varIndex) {
        pushSh2Context(ctx);
        ctx.mv.visitVarInsn(LLOAD, varIndex);
        ctx.mv.visitLdcInsn(-1L);
        ctx.mv.visitInsn(LAND);
        ctx.mv.visitInsn(L2I);
        popSh2ContextIntField(ctx, "MACL");

        pushSh2Context(ctx);
        ctx.mv.visitVarInsn(LLOAD, varIndex);
        pushIntConstStack(ctx, 32);
        ctx.mv.visitInsn(LUSHR);
        ctx.mv.visitLdcInsn(-1L);
        ctx.mv.visitInsn(LAND);
        ctx.mv.visitInsn(L2I);
        popSh2ContextIntField(ctx, "MACH");
    }

    /**
     * Set the context.PC to the current PC
     */
    public static void setContextPc(BytecodeContext ctx) {
        assert ctx.pc != 0;
        pushSh2Context(ctx);
        ctx.mv.visitLdcInsn(ctx.pc);
        ctx.mv.visitFieldInsn(PUTFIELD, Type.getInternalName(Sh2Context.class), "PC", intDesc);
    }

    /**
     * {
     * ctx.cycles -= val;
     * }
     */
    public static void subCyclesExt(BytecodeContext ctx, int cycles) {
        pushSh2Context(ctx);
        ctx.mv.visitInsn(DUP);
        ctx.mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Context.class), "cycles", intDesc);
        pushIntConstStack(ctx, cycles);
        ctx.mv.visitInsn(ISUB);
        popSh2ContextIntField(ctx, "cycles");
    }

    /**
     * {
     * for (int i = 0; i < limit; i++) {
     * sh2MMREG.deviceStep(bc);
     * }
     * }
     */
    public static void deviceStepFor(BytecodeContext ctx, int limit) {
        int iIdx = ctx.mv.newLocal(Type.INT_TYPE);
        Label topForLbl = new Label();
        Label doneLbl = new Label();

        pushIntConstStack(ctx, 0);
        ctx.mv.visitVarInsn(ISTORE, iIdx);
        ctx.mv.visitLabel(topForLbl);
        ctx.mv.visitVarInsn(ILOAD, iIdx);
        pushIntConstStack(ctx, limit);
        ctx.mv.visitJumpInsn(IF_ICMPGE, doneLbl);
        deviceStep(ctx);
        ctx.mv.visitIincInsn(iIdx, 1);
        ctx.mv.visitJumpInsn(GOTO, topForLbl);
        ctx.mv.visitLabel(doneLbl);
    }

    /**
     * {
     * sh2MMREG.deviceStep();
     * }
     */
    private static void deviceStep(BytecodeContext ctx) {
        ctx.mv.visitVarInsn(ALOAD, 0); // push `this`
        ctx.mv.visitFieldInsn(GETFIELD, ctx.classDesc, "sh2MMREG", Type.getDescriptor(Sh2MMREG.class));
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(Sh2MMREG.class), "deviceStep", noArgsNoRetDesc);
    }
}