package sh2.sh2.drc;

import omegadrive.util.FileUtil;
import omegadrive.util.LogHelper;
import omegadrive.util.Util;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.util.ASMifier;
import org.slf4j.Logger;
import sh2.Sh2MMREG;
import sh2.Sh2Memory;
import sh2.sh2.Sh2Context;
import sh2.sh2.device.Sh2DeviceHelper;
import sh2.sh2.prefetch.Sh2Prefetch.BytecodeContext;
import sh2.sh2.prefetch.Sh2Prefetch.Sh2DrcContext;
import sh2.sh2.prefetch.Sh2Prefetcher.Sh2BlockUnit;

import java.nio.file.Path;
import java.nio.file.Paths;

import static omegadrive.util.Util.th;
import static org.objectweb.asm.Opcodes.*;
import static sh2.sh2.drc.Ow2Sh2Helper.DRC_CLASS_FIELD.*;
import static sh2.sh2.drc.Ow2Sh2Helper.SH2CTX_CLASS_FIELD.devices;
import static sh2.sh2.drc.Ow2Sh2Helper.SH2_DRC_CTX_CLASS_FIELD.sh2Ctx;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2BlockRecompiler {

    private final static Logger LOG = LogHelper.getLogger(Ow2Sh2BlockRecompiler.class.getSimpleName());
    private static final Path drcFolder = Paths.get("./res/drc_" + System.currentTimeMillis());
    private final static boolean writeClass = false;

    private final OwnClassLoader cl = new OwnClassLoader();

    public static final String drcPackage = "sh2.sh2.drc";
    public static final String intArrayDesc = Type.getDescriptor(int[].class);
    public static final String intDesc = Type.getDescriptor(int.class);
    public static final String noArgsNoRetDesc = "()V";
    public static final String classConstructor = "<init>";
    public static final String runMethodName = "run";

    private static Ow2Sh2BlockRecompiler current = null;
    private String token;

    /**
     * There is no easy way of releasing/removing a classLoader,
     * GC should take care of it.
     */
    public static Ow2Sh2BlockRecompiler newInstance(String token) {
        boolean firstOne = current == null;
        boolean newOne = firstOne || current.token != token;
        if (newOne) {
            Ow2Sh2BlockRecompiler recompiler = new Ow2Sh2BlockRecompiler();
            recompiler.token = token;
            current = recompiler;
            LOG.info("New recompiler with token: {}", token);
        }
        return current;
    }

    public static Ow2Sh2BlockRecompiler getInstance() {
        assert current != null;
        return current;
    }

    private static class OwnClassLoader extends ClassLoader {
        public Class<?> defineClass(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

    public Runnable createDrcClass(Sh2Block block, Sh2DrcContext drcCtx) {
        String blockClass = drcPackage + "." + drcCtx.sh2Ctx.sh2TypeCode + "_" + th(block.prefetchPc) + "_" + System.nanoTime();
        byte[] binc = createClassBinary(block, drcCtx, blockClass);
        writeClassMaybe(blockClass, binc);
        Class<?> clazz = cl.defineClass(blockClass, binc);
        Runnable r = null;
        try {
            Object b = clazz.getDeclaredConstructor(int[].class, int[].class, Sh2DrcContext.class).
                    newInstance(drcCtx.sh2Ctx.registers, block.prefetchWords, drcCtx);
            assert Runnable.class.isInstance(b);
            r = (Runnable) b;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Fatal!");
//            return null;
        }
        return r;
    }

    private static byte[] createClassBinary(Sh2Block block, Sh2DrcContext drcCtx, String blockClass) {
        String blockClassDesc = blockClass.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V11, ACC_PUBLIC | ACC_FINAL, blockClassDesc, null, Type.getInternalName(Object.class),
                new String[]{Type.getInternalName(Runnable.class)});
        {
            //fields
            cw.visitField(ACC_PRIVATE | ACC_FINAL, regs.name(), intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, opcodes.name(), intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, sh2DrcContext.name(), Type.getDescriptor(Sh2DrcContext.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, sh2Context.name(), Type.getDescriptor(Sh2Context.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, sh2MMREG.name(), Type.getDescriptor(Sh2MMREG.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, memory.name(), Type.getDescriptor(Sh2Memory.class), null, null).visitEnd();
        }
        {

            // constructor
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, classConstructor,
                    Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(int[].class), Type.getType(int[].class),
                            Type.getType(Sh2DrcContext.class)), null, null);
            mv.visitVarInsn(ALOAD, 0); // push `this` to the operand stack
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), classConstructor, noArgsNoRetDesc);// call the constructor of super class
            {
                //set fields
                setClassField(mv, blockClassDesc, 1, regs, intArrayDesc);
                setClassField(mv, blockClassDesc, 2, opcodes, intArrayDesc);
                setClassField(mv, blockClassDesc, 3, sh2DrcContext, Type.getDescriptor(Sh2DrcContext.class));

                //set sh2Context
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class), sh2Ctx.name(), Type.getDescriptor(Sh2Context.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, sh2Context.name(), Type.getDescriptor(Sh2Context.class));

                //set sh2mmreg
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class), sh2Ctx.name(),
                        Type.getDescriptor(Sh2Context.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Context.class), devices.name(),
                        Type.getDescriptor(Sh2DeviceHelper.Sh2DeviceContext.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DeviceHelper.Sh2DeviceContext.class),
                        Ow2Sh2Helper.SH2_DEVICE_CTX_CLASS_FIELD.sh2MMREG.name(),
                        Type.getDescriptor(Sh2MMREG.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, sh2MMREG.name(), Type.getDescriptor(Sh2MMREG.class));

                //set memory
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class),
                        Ow2Sh2Helper.SH2_DRC_CTX_CLASS_FIELD.memory.name(),
                        Type.getDescriptor(Sh2Memory.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, memory.name(), Type.getDescriptor(Sh2Memory.class));
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, runMethodName, noArgsNoRetDesc, null, null);
            LocalVariablesSorter lvs = new LocalVariablesSorter(ACC_PUBLIC | ACC_FINAL, noArgsNoRetDesc, mv);
            int limit = block.prefetchWords.length;
            BytecodeContext ctx = new BytecodeContext();
            BytecodeContext dsCtx = new BytecodeContext();
            ctx.classDesc = dsCtx.classDesc = blockClassDesc;
            ctx.drcCtx = dsCtx.drcCtx = drcCtx;
            ctx.mv = dsCtx.mv = lvs;
            int totCycles = 0;
            Ow2DrcOptimizer.InstCtx instCtx = Ow2DrcOptimizer.optimizeMaybe(block, ctx);
            for (int i = instCtx.start; i < instCtx.end; i++) {
                setDrcContext(ctx, block.inst[i], false);
                if (ctx.sh2Inst.isBranchDelaySlot) {
                    setDrcContext(dsCtx, block.inst[i + 1], true);
                    ctx.delaySlotCtx = dsCtx;
                }
                Ow2Sh2Helper.createInst(ctx);
                //branch inst cycles taken are not known at this point
                if (!ctx.sh2Inst.isBranch) {
                    totCycles += ctx.sh2Inst.cycles;
                }
                //delay slot will be run within
                if (ctx.sh2Inst.isBranchDelaySlot) {
                    break;
                }
            }
            //TODO reached the block len limit, needs to set the PC, this should be a flag in Sh2Block
            if (limit == Sh2Block.MAX_INST_LEN) {
                Ow2Sh2Bytecode.setPcExt(ctx, block.inst[limit - 1].pc + 2);
            }
            Ow2Sh2Bytecode.subCyclesExt(ctx, totCycles);
            Ow2Sh2Bytecode.deviceStepFor(ctx, limit);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void setClassField(MethodVisitor mv, String classDesc, int varIndex, Ow2Sh2Helper.DRC_CLASS_FIELD field, String typeDesc) {
        mv.visitVarInsn(ALOAD, 0); // push `this`
        mv.visitVarInsn(ALOAD, varIndex); // push field
        mv.visitFieldInsn(PUTFIELD, classDesc, field.name(), typeDesc);
    }

    private static void setDrcContext(BytecodeContext ctx, Sh2BlockUnit sbu, boolean delaySlot) {
        ctx.opcode = sbu.opcode;
        ctx.pc = sbu.pc;
        ctx.sh2Inst = sbu.inst;
        ctx.delaySlot = delaySlot;
        ctx.delaySlotCtx = null;
    }

    private static void writeClassMaybe(String blockClass, byte[] binc) {
        if (!writeClass) {
            return;
        }
        drcFolder.toFile().mkdirs();
        Path p = Paths.get(drcFolder.toAbsolutePath().toString(), (blockClass + ".class"));
        Util.executorService.submit(() -> {
            FileUtil.writeFileSafe(p, binc);
            LOG.info("Drc Class written: " + p.toAbsolutePath());
        });
    }

    public static void main(String[] args) throws Exception {
        String p = "./res/drc_1652793435182/sh2.sh2.drc.S_6000390_19768421105194.class";
        ASMifier.main(new String[]{p});
    }
}