package sh2.sh2.drc;

import omegadrive.util.FileUtil;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import sh2.S32xUtil;
import sh2.Sh2MMREG;
import sh2.Sh2Memory;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Impl;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.device.Sh2DeviceHelper;
import sh2.sh2.prefetch.Sh2Prefetch.BytecodeContext;
import sh2.sh2.prefetch.Sh2Prefetch.Sh2DrcContext;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static omegadrive.util.Util.th;
import static org.objectweb.asm.Opcodes.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2BlockRecompiler {

    private final static Logger LOG = LogManager.getLogger(Ow2Sh2BlockRecompiler.class.getSimpleName());
    private static final Path drcFolder = Paths.get("./res/drc_" + System.currentTimeMillis());

    private static OwnClassLoader cl = new OwnClassLoader();

    public static final String drcPackage = "sh2.sh2.drc";
    public static final String intArrayDesc = Type.getDescriptor(int[].class);
    public static final String intDesc = Type.getDescriptor(int.class);
    public static final String noArgsNoRetDesc = "()V";

    private static class OwnClassLoader extends ClassLoader {
        public Class<?> defineClass(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

    public static Runnable createDrcClass(Sh2Prefetcher.Sh2Block block, Sh2DrcContext drcCtx) {
        String blockClass = drcPackage + "." + drcCtx.sh2Ctx.sh2TypeCode + "_" + th(block.prefetchPc) + "_" + System.nanoTime();
        byte[] binc = createClassBinary(block, drcCtx, blockClass);
        writeClassMaybe(blockClass, binc);
        Class clazz = cl.defineClass(blockClass, binc);
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

    private static byte[] createClassBinary(Sh2Prefetcher.Sh2Block block, Sh2DrcContext drcCtx, String blockClass) {
        String blockClassDesc = blockClass.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V11, ACC_PUBLIC | ACC_FINAL, blockClassDesc, null, Type.getInternalName(Object.class),
                new String[]{Type.getInternalName(Runnable.class)});
        {
            //fields
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "regs", intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "opcodes", intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "sh2DrcContext", Type.getDescriptor(Sh2DrcContext.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "sh2Context", Type.getDescriptor(Sh2Context.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "sh2MMREG", Type.getDescriptor(Sh2MMREG.class), null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "memory", Type.getDescriptor(Sh2Memory.class), null, null).visitEnd();
//            cw.visitField(ACC_PRIVATE, "cycles", intDesc, null, null).visitEnd();
//            cw.visitField(ACC_PRIVATE, "pc", intDesc, null, null).visitEnd();
        }
        {

            // constructor
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                    Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(int[].class), Type.getType(int[].class),
                            Type.getType(Sh2DrcContext.class)), null, null);
            mv.visitVarInsn(ALOAD, 0); // push `this` to the operand stack
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", noArgsNoRetDesc);// call the constructor of super class
            {
                //set regs
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 1); // push `regs`
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "regs", intArrayDesc);

                //set opcodes
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 2); // push `opcodes`
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "opcodes", intArrayDesc);

                //set sh2DrcContext
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "sh2DrcContext", Type.getDescriptor(Sh2DrcContext.class));

                //set sh2Context
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class), "sh2Ctx",
                        Type.getDescriptor(Sh2Context.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "sh2Context", Type.getDescriptor(Sh2Context.class));

                //set sh2mmreg
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class), "sh2Ctx",
                        Type.getDescriptor(Sh2Context.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Context.class), "devices",
                        Type.getDescriptor(Sh2DeviceHelper.Sh2DeviceContext.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DeviceHelper.Sh2DeviceContext.class), "sh2MMREG",
                        Type.getDescriptor(Sh2MMREG.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "sh2MMREG", Type.getDescriptor(Sh2MMREG.class));

                //set memory
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitVarInsn(ALOAD, 3); // push `sh2DrcContext`
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2DrcContext.class), "memory",
                        Type.getDescriptor(Sh2Memory.class));
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "memory", Type.getDescriptor(Sh2Memory.class));
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "run", noArgsNoRetDesc, null, null);
            LocalVariablesSorter lvs = new LocalVariablesSorter(ACC_PUBLIC | ACC_FINAL, noArgsNoRetDesc, mv);
            int limit = block.prefetchWords.length;
            BytecodeContext ctx = new BytecodeContext();
            ctx.classDesc = blockClassDesc;
            ctx.drcCtx = drcCtx;
            ctx.mv = lvs;
            int totCycles = 0;
            for (int i = 0; i < limit; i++) {
                final Sh2Prefetcher.Sh2BlockUnit sbu = block.inst[i];
                ctx.opcode = sbu.opcode;
                ctx.pc = sbu.pc;
                ctx.sh2Inst = sbu.inst;
                Ow2Sh2Helper.createInst(ctx);
                totCycles += ctx.sh2Inst.cycles;
                if (ctx.sh2Inst.isBranchDelaySlot) {
                    break;
                }
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

    static boolean writeClass = false;

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
        String testBlockClass = "sh2.sh2.Block01";
        String testBlockClassDesc = "sh2/sh2/Block01";
        Sh2Prefetcher.Sh2Block block = new Sh2Prefetcher.Sh2Block();
        block.prefetchWords = new int[0];
        Sh2Context sh2Context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        Sh2Impl sh2 = new Sh2Impl(null);
        Sh2DrcContext testDrcCtx = new Sh2DrcContext();
        testDrcCtx.cpu = sh2Context.cpuAccess;
        testDrcCtx.sh2Ctx = sh2Context;
        testDrcCtx.sh2 = sh2;

        OwnClassLoader cl = new OwnClassLoader();
        Sh2Instructions.createOpcodeMap(sh2);
        byte[] binc = createClassBinary(block, testDrcCtx, testBlockClass);
        writeClassMaybe(testBlockClass, binc);
        Class clazz = cl.defineClass(testBlockClass, binc);
        Object b = clazz.getDeclaredConstructor(int[].class, int[].class, Sh2DrcContext.class).
                newInstance(sh2Context.registers, block.prefetchWords, testDrcCtx);
        System.out.println(b.getClass());
        Runnable r = (Runnable) b;
        Arrays.fill(sh2Context.registers, 1);
        r.run();
        Util.executorService.shutdownNow();
    }
}