package sh2.sh2;

import com.google.common.collect.ImmutableSet;
import omegadrive.cpu.CpuFastDebug.DebugMode;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.S32xUtil;

import java.util.Arrays;
import java.util.Set;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Debug extends Sh2Impl {

    private static final Logger LOG = LogManager.getLogger(Sh2Debug.class.getSimpleName());

    private static final int PC_AREAS = 0x100;
    private static final int PC_AREA_SIZE = 0x4_0000;
    private static final int PC_AREA_MASK = PC_AREA_SIZE - 1;

    private DebugMode debugMode = DebugMode.NEW_INST_ONLY;

    //00_00_0000 - 00_00_4000 BOOT ROM
    //06_00_0000 - 06_04_0000 RAM
    //02_00_0000 - 02_04_0000 ROM
    //C0_00_0000 - C0_01_0000 CACHE AREA
    static final int[] areas = {0, 2, 6, 0xC0};
    private int[][] pcVisitedMaster = new int[PC_AREAS][];
    private int[][] pcVisitedSlave = new int[PC_AREAS][];
    private int[][] opcodesMaster = new int[PC_AREAS][];
    private int[][] opcodesSlave = new int[PC_AREAS][];
    private Set<Object> arraySet = ImmutableSet.of(pcVisitedMaster, pcVisitedSlave, opcodesMaster, opcodesSlave);

    public Sh2Debug(IMemory memory) {
        super(memory);
        LOG.warn("Sh2 cpu: creating debug instance");
        init();
    }

    @Override
    public void init() {
        for (Object o : arraySet) {
            Arrays.stream(areas).forEach(idx -> ((int[][]) o)[idx] = new int[PC_AREA_SIZE]);
        }
    }

    @Override
    public void run(Sh2Context ctx) {
        try {
            super.run(ctx);
        } catch (Exception e) {
            e.printStackTrace();
            Util.waitForever();
        }
    }

    protected void printDebugMaybe(Sh2Context ctx, int opcode) {
        if (ctx.debug) {
            switch (debugMode) {
                case STATE:
                    Sh2Helper.printState(ctx, opcode);
                    break;
                case INST_ONLY:
                    Sh2Helper.printInst(ctx, opcode);
                    break;
                case NEW_INST_ONLY:
                    printNewInst(ctx, opcode);
                    break;
                default:
                    break;
            }
        }
    }

    private void printNewInst(Sh2Context ctx, int opcode) {
        final int pc = ctx.PC;
        final int pcArea = (pc >> 24) & 0xFF;
        final int c = ctx.cpuAccess.ordinal();
        int[][] pcv1 = ctx.cpuAccess == S32xUtil.CpuDeviceAccess.MASTER ? pcVisitedMaster : pcVisitedSlave;
        int[][] opv1 = ctx.cpuAccess == S32xUtil.CpuDeviceAccess.MASTER ? opcodesMaster : opcodesSlave;
        final int[] pcv = pcv1[pcArea];
        final int[] opc = opv1[pcArea];
        final int prevOpcode = opc[pc & PC_AREA_MASK];

        if (prevOpcode == 0 || prevOpcode != opcode) {
            opc[pc & PC_AREA_MASK] = opcode;
            pcv[pc & PC_AREA_MASK] = 1;
            String val = prevOpcode == 0 ? " [NEW]" : " [NEW-R]";
            logNewInst(Sh2Helper.getInstString(ctx, opcode), val);
        }
    }

    private void logNewInst(String s1, String s2) {
        LOG.info("{}{}", s1, s2);
//        System.out.println(s1 + s2);
    }
}