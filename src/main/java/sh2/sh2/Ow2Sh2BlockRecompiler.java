package sh2.sh2;

import omegadrive.util.FileUtil;
import omegadrive.util.Util;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import sh2.S32xUtil;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.objectweb.asm.Opcodes.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2Sh2BlockRecompiler {

    public static Sh2Context sh2Context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
    public static Sh2Prefetcher.Sh2Block block = new Sh2Prefetcher.Sh2Block();
    private static OwnClassLoader cl = new OwnClassLoader();

    public static final String blockClass = "sh2.sh2.Block01";
    public static final String blockClassDesc = "sh2/sh2/Block01";
    public static final String intArrayDesc = Type.getDescriptor(int[].class);
    public static final String intDesc = Type.getDescriptor(int.class);
    public static final String noArgsNoRetDesc = "()V";

    private static class OwnClassLoader extends ClassLoader {
        public Class<?> defineClass(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

    private static Class<?> createClass(byte[] b) {
        return cl.defineClass(blockClass, b);
    }

    private static byte[] createClassBinary(Sh2Prefetcher.Sh2Block block, Sh2Context sh2Ctx) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V11, ACC_PUBLIC | ACC_FINAL, blockClassDesc, null, Type.getInternalName(Object.class), null);
        {
            //fields
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "regs", intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "opcodes", intArrayDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE, "cycles", intDesc, null, null).visitEnd();
            cw.visitField(ACC_PRIVATE, "pc", intDesc, null, null).visitEnd();
        }
        {
            // constructor
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", noArgsNoRetDesc, null, null);
            mv.visitVarInsn(ALOAD, 0); // push `this` to the operand stack
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", noArgsNoRetDesc);// call the constructor of super class
            {
                //set regs
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Ow2Sh2BlockRecompiler.class), "sh2Context", Type.getDescriptor(Sh2Context.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Context.class), "registers", intArrayDesc);
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "regs", intArrayDesc);

                //set opcodes
                mv.visitVarInsn(ALOAD, 0); // push `this`
                mv.visitFieldInsn(GETSTATIC, Type.getInternalName(Ow2Sh2BlockRecompiler.class), "block",
                        Type.getDescriptor(Sh2Prefetcher.Sh2Block.class));
                mv.visitFieldInsn(GETFIELD, Type.getInternalName(Sh2Prefetcher.Sh2Block.class), "prefetchWords", intArrayDesc);
                mv.visitFieldInsn(PUTFIELD, blockClassDesc, "opcodes", intArrayDesc);
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "exec", noArgsNoRetDesc, null, null);
//            int limit = 1000; //block.prefetchWords.length;
//            for (int i = 0; i < limit; i++) {
//                Ow2Sh2Bytecode.createInst(mv, ctx,  i);
//            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        //write all methods for opcodes
        {
            Ow2Sh2Bytecode.BytecodeContext ctx = new Ow2Sh2Bytecode.BytecodeContext();
            ctx.sh2Ctx = sh2Ctx;
            sh2Ctx.MACH = 0x8000_0000;
            int limit = 0x100; //block.prefetchWords.length;
            for (int i = 0; i < limit; i++) {
                Sh2Instructions.Sh2Inst inst = Sh2Instructions.instOpcodeMap[i].inst;
                if (Sh2Instructions.Sh2Inst.ILLEGAL == inst && i > 0) {
                    continue;
                }
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "exec_" + i, noArgsNoRetDesc, null, null);
                ctx.mv = mv;
                ctx.opcode = i;
                ctx.sh2Inst = Sh2Instructions.sh2OpcodeMap[i];
                Ow2Sh2Bytecode.createInst(ctx);
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    static boolean writeClass = true;

    public static void main(String[] args) throws Exception {
        block.prefetchWords = new int[0x10_000];
        for (int i = 0; i < 0x10_000; i++) {
            block.prefetchWords[i] = i;
        }
        OwnClassLoader cl = new OwnClassLoader();
        Sh2Impl sh2 = new Sh2Impl(null);
        Sh2Instructions.createOpcodeMap(sh2);
        byte[] binc = createClassBinary(block, sh2Context);
        if (writeClass) {
            Path p = Paths.get(".", ("Block01_" + System.currentTimeMillis() + ".class"));
            FileUtil.writeFileSafe(p, binc);
            System.out.println("Class written: " + p.toAbsolutePath());
        }
        Class clazz = createClass(binc);
        Object b = clazz.newInstance();
        System.out.println(b.getClass());
        Method exec = clazz.getMethod("exec");
        Arrays.fill(sh2Context.registers, 1);
        exec.invoke(b);
        Util.executorService.shutdownNow();
    }
}