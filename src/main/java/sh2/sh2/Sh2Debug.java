package sh2.sh2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.S32xUtil;
import sh2.S32xUtil.DebugMode;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Debug extends Sh2 {

    private static final Logger LOG = LogManager.getLogger(Sh2Debug.class.getSimpleName());

    private static final int PC_AREAS = 0x100;
    private static final int PC_AREA_SIZE = 0x4_0000;
    private static final int PC_AREA_MASK = PC_AREA_SIZE - 1;

    private DebugMode debugMode = DebugMode.INST_ONLY;

    //TODO RAM could change
    //00_00_0000 - 00_00_4000 BOOT ROM
    //06_00_0000 - 06_04_0000 RAM
    //02_00_0000 - 02_04_0000 ROM
    //C0_00_0000 - C0_01_0000 CACHE AREA
    private int[][] pcVisitedMaster = new int[PC_AREAS][];
    private int[][] pcVisitedSlave = new int[PC_AREAS][];

    public Sh2Debug(IMemory memory) {
        super(memory);
        LOG.warn("Sh2 cpu: creating debug instance");
        init();
    }

    @Override
    public void init() {
        pcVisitedMaster[0x0] = new int[PC_AREA_SIZE];
        pcVisitedMaster[0x2] = new int[PC_AREA_SIZE];
        pcVisitedMaster[0x6] = new int[PC_AREA_SIZE];
        pcVisitedMaster[0xC0] = new int[PC_AREA_SIZE];
        pcVisitedSlave[0x0] = new int[PC_AREA_SIZE];
        pcVisitedSlave[0x2] = new int[PC_AREA_SIZE];
        pcVisitedSlave[0x6] = new int[PC_AREA_SIZE];
        pcVisitedSlave[0xC0] = new int[PC_AREA_SIZE];
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
        final int c = ctx.cpuAccess.ordinal();
        int[][] pcv = ctx.cpuAccess == S32xUtil.CpuDeviceAccess.MASTER ? pcVisitedMaster : pcVisitedSlave;
        if (pcv[ctx.PC >> 24][ctx.PC & PC_AREA_MASK]++ == 0) {
            String s = Sh2Helper.getInstString(ctx, opcode);
            System.out.println(s + " [NEW]");
        }
    }
}
