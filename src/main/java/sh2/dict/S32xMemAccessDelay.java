package sh2.dict;

import sh2.Md32x;

import static sh2.S32xUtil.CpuDeviceAccess.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xMemAccessDelay {

    public static final int ROM = 0;
    public static final int FRAME_BUFFER = 1;
    public static final int PALETTE = 2;
    public static final int VDP_REG = 3;
    public static final int SYS_REG = 4;
    public static final int BOOT_ROM = 5;
    public static final int SDRAM = 6;

    public static final int[][] readDelays, writeDelays;

    static {
        readDelays = new int[vals.length][7];
        writeDelays = new int[vals.length][7];

        readDelays[M68K.ordinal()][ROM] = 3; //[0,5]
        readDelays[M68K.ordinal()][FRAME_BUFFER] = 3; //[2,4]
        readDelays[M68K.ordinal()][PALETTE] = 2; //min
        readDelays[M68K.ordinal()][VDP_REG] = 2; //const
        readDelays[M68K.ordinal()][SYS_REG] = 0; //const
        readDelays[M68K.ordinal()][BOOT_ROM] = 0; //n/a
        readDelays[M68K.ordinal()][SDRAM] = 0; //n/a

        writeDelays[M68K.ordinal()][ROM] = 3; //[0,5]
        writeDelays[M68K.ordinal()][FRAME_BUFFER] = 0; //const
        writeDelays[M68K.ordinal()][PALETTE] = 3; //min
        writeDelays[M68K.ordinal()][VDP_REG] = 0; //const
        writeDelays[M68K.ordinal()][SYS_REG] = 0; //const
        writeDelays[M68K.ordinal()][BOOT_ROM] = 0; //n/a
        writeDelays[M68K.ordinal()][SDRAM] = 0; //n/a

        readDelays[MASTER.ordinal()][ROM] = 11; //[6,15]
        readDelays[MASTER.ordinal()][FRAME_BUFFER] = 9; //[5,12]
        readDelays[MASTER.ordinal()][PALETTE] = 5; //min
        readDelays[MASTER.ordinal()][VDP_REG] = 5; //const
        readDelays[MASTER.ordinal()][SYS_REG] = 1; //const
        readDelays[MASTER.ordinal()][BOOT_ROM] = 1; //const
        readDelays[MASTER.ordinal()][SDRAM] = 6; //12 clock for 8 words burst

        writeDelays[MASTER.ordinal()][ROM] = 11; //[6,15]
        writeDelays[MASTER.ordinal()][FRAME_BUFFER] = 2; //[1,3]
        writeDelays[MASTER.ordinal()][PALETTE] = 5; //min
        writeDelays[MASTER.ordinal()][VDP_REG] = 5; //const
        writeDelays[MASTER.ordinal()][SYS_REG] = 1; //const
        writeDelays[MASTER.ordinal()][BOOT_ROM] = 1; //const
        writeDelays[MASTER.ordinal()][SDRAM] = 2; //2 clock for 1 word

        System.arraycopy(readDelays[MASTER.ordinal()], 0, readDelays[SLAVE.ordinal()], 0,
                readDelays[MASTER.ordinal()].length);
        System.arraycopy(writeDelays[MASTER.ordinal()], 0, writeDelays[SLAVE.ordinal()], 0,
                writeDelays[MASTER.ordinal()].length);
    }

    public static void addReadCpuDelay(int deviceType) {
        Md32x.addCpuDelay(readDelays[Md32x.getAccessType().ordinal()][deviceType]);
    }

    public static void addWriteCpuDelay(int deviceType) {
        Md32x.addCpuDelay(readDelays[Md32x.getAccessType().ordinal()][deviceType]);
    }
}
