package sh2.sh2;

import omegadrive.cpu.CpuFastDebug;
import omegadrive.cpu.CpuFastDebug.DebugMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;

import java.util.function.Predicate;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Debug extends Sh2Impl implements CpuFastDebug.CpuDebugInfoProvider {

    private static final Logger LOG = LogManager.getLogger(Sh2Debug.class.getSimpleName());

    private static final int PC_AREAS = 0x100;
    private static final int PC_AREA_SIZE = 0x4_0000;
    private static final int PC_AREA_MASK = PC_AREA_SIZE - 1;

    private static final Predicate<Integer> isBranchOpcode = op ->
            (op & 0xFF00) == 0x8900 //bt
                    || (op & 0xFF00) == 0x8B00 //bf
                    || (op & 0xFF00) == 0x8F00 //bf/s
                    || (op & 0xFF00) == 0x8D00 //bt/s
                    || (op & 0xF000) == 0xA000 //bsr
            ;

    private static final Predicate<Integer> isCmpOpcode = op ->
            (op & 0xF00F) == 0x3000 //cmp/eq
                    || (op & 0xF00F) == 0x3002 //CMP/HS Rm,Rn
                    || (op & 0xF00F) == 0x3006 //CMP/HI Rm,Rn
                    || (op & 0xFF00) == 0x8800 //cmp/eq #imm
                    || (op & 0xF0FF) == 0x4015 //CMP/PL Rn
                    || (op & 0xF0FF) == 0x4011 //CMP/PZ Rn

            ;

    private static final Predicate<Integer> isTstOpcode = op ->
            (op & 0xF00F) == 0x2008 //tst Rm,Rn
                    || (op & 0xFF00) == 0xC800 //TST#imm,R0
                    || (op & 0xFF00) == 0xCC00 //TST.B#imm,@(R0,GBR)
            ;

    private static final Predicate<Integer> isMovOpcode = op ->
            (op & 0xF00F) == 0x6002 //mov.l @Rm, Rn
                    || (op & 0xF00F) == 0x6001 //mov.w @Rm, Rn
                    || (op & 0xF00F) == 0x6000 //mov.b @Rm, Rn
                    || (op & 0xFF00) == 0xC600 //mov.l @(disp,GBR),R0
                    || (op & 0xFF00) == 0xC500 //MOV.W@(disp,GBR),R0
                    || (op & 0xFF00) == 0xC400 //MOV.B@(disp,GBR),R0
                    || (op & 0xFF00) == 0x8400 //MOV.B@(disp,Rm),R0
                    || (op & 0xFF00) == 0x8500 //MOV.W@(disp,Rm),R0
                    || (op & 0xF000) == 0x5000 //MOV.L@(disp,Rm),R0
            ;

    private static final Predicate<Integer> isNopOpcode = op -> op == 9;
    public static final Predicate<Integer> isLoopOpcode = isNopOpcode.or(isBranchOpcode).
            or(isCmpOpcode).or(isTstOpcode).or(isMovOpcode);
    public static final Predicate<Integer> isIgnoreOpcode =
            op -> (op & 0xF0FF) == 0x4010 //dt
            ;

    private CpuFastDebug[] fastDebug = new CpuFastDebug[2];


    public Sh2Debug(IMemory memory) {
        super(memory);
        LOG.warn("Sh2 cpu: creating debug instance");
        init();
    }

    @Override
    public void init() {
        fastDebug[0] = new CpuFastDebug(this, createContext());
        fastDebug[1] = new CpuFastDebug(this, createContext());
        fastDebug[0].debugMode = DebugMode.NEW_INST_ONLY;
        fastDebug[1].debugMode = DebugMode.NEW_INST_ONLY;
    }

//    @Override
//    public void run(Sh2Context ctx) {
//        try {
//            super.run(ctx);
//        } catch (Exception e) {
//            e.printStackTrace();
//            Util.waitForever();
//        }
//    }

    protected final void printDebug(DebugMode mode, Sh2Context ctx) {
        CpuFastDebug f = fastDebug[ctx.cpuAccess.ordinal()];
        DebugMode prev = f.debugMode;
        f.debugMode = mode;
        f.printDebugMaybe();
        f.debugMode = prev;
    }

    @Override
    protected final void printDebugMaybe(Sh2Context ctx) {
        final int n = ctx.cpuAccess.ordinal();
        ctx.cycles -= fastDebug[n].isBusyLoop(ctx.PC & 0x0FFF_FFFF, ctx.opcode);
        fastDebug[n].printDebugMaybe();
    }

    //00_00_0000 - 00_00_4000 BOOT ROM
    //06_00_0000 - 06_04_0000 RAM
    //02_00_0000 - 02_04_0000 ROM
    //C0_00_0000 - C0_01_0000 CACHE AREA
    public static CpuFastDebug.CpuDebugContext createContext() {
        CpuFastDebug.CpuDebugContext ctx = new CpuFastDebug.CpuDebugContext();
        ctx.pcAreas = new int[]{0, 2, 6, 0xC0};
        ctx.pcAreasNumber = PC_AREAS;
        ctx.pcAreaSize = PC_AREA_SIZE;
        ctx.pcAreaShift = 24;
        ctx.pcMask = PC_AREA_MASK;
        ctx.isLoopOpcode = isLoopOpcode;
        ctx.isIgnoreOpcode = isIgnoreOpcode;
        return ctx;
    }

    @Override
    public String getInstructionOnly(int pc) {
        return Sh2Helper.getInstString(ctx.sh2TypeCode, pc, memory.read16(pc));
    }

    @Override
    public String getInstructionOnly() {
        return Sh2Helper.getInstString(ctx);
    }

    @Override
    public String getCpuState(String head) {
        return Sh2Helper.toDebuggingString(ctx);
    }

    @Override
    public int getPc() {
        return ctx.PC;
    }

    @Override
    public int getOpcode() {
        return ctx.opcode;
    }
}