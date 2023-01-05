package sh2.util;

import omegadrive.system.SystemProvider;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32xUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static omegadrive.system.SystemProvider.NO_CLOCK;
import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.S32xUtil.writeBuffer;
import static sh2.dict.S32xDict.SH2_SDRAM_MASK;
import static sh2.dict.S32xDict.SH2_SDRAM_SIZE;

/**
 * Federico Berti
 * <p>
 * Copyright 2023
 * TODO sync tester, extend to check other areas: framebuffer, cache data array (vr), ...
 */
public class SdramSyncTester {
    private static final Logger LOG = LogHelper.getLogger(SdramSyncTester.class.getSimpleName());
    private static final int MAST_READ = 1;
    private static final int MAST_WRITE = 2;
    private static final int SLAVE_READ = 4;
    private static final int SLAVE_WRITE = 8;

    private static final String[] names = {"EMPTY", "MAST_READ", "MAST_WRITE", "EMPTY", "SLAVE_READ", "EMPTY", "EMPTY",
            "EMPTY", "SLAVE_WRITE"};

    public static final SdramSyncTester NO_OP = new SdramSyncTester(null);

    static class SdramSync {
        int accessMask;
        int[] cycleAccess = new int[SLAVE_WRITE + 1];
    }

    private final SdramSync[] sdramAccess = new SdramSync[SH2_SDRAM_SIZE];
    private Runnable valToWrite;
    private int clockDiffMin = 100;
    private ByteBuffer sdram;

    public SdramSyncTester(ByteBuffer sdram) {
        for (int i = 0; i < sdramAccess.length; i++) {
            sdramAccess[i] = new SdramSync();
        }
        this.sdram = sdram;
    }

    public void readSyncCheck(S32xUtil.CpuDeviceAccess cpuAccess, int address, Size size) {
        if (valToWrite != null && cpuAccess == MASTER) {
            LOG.info("Writing after reading: {} {}", th(address), size);
            valToWrite.run();
            valToWrite = null;
        }
        //TODO fix this
        SystemProvider.SystemClock clock = NO_CLOCK; //Genesis.clock;
        int readType = cpuAccess == MASTER ? MAST_READ : SLAVE_READ;
        sdramAccess[address & SH2_SDRAM_MASK].accessMask |= readType;
        sdramAccess[address & SH2_SDRAM_MASK].cycleAccess[readType] = clock.getCycleCounter();
    }

    public void writeSyncCheck(S32xUtil.CpuDeviceAccess cpuAccess, int address, int val, Size size) {
        //TODO fix this
        SystemProvider.SystemClock clock = NO_CLOCK; //Genesis.clock;
        boolean check = cpuAccess == SLAVE &&
                (address & SH2_SDRAM_MASK) == 0x4c20 && clock.getCycleCounter() == 111787;
        if (check) {
            final int v = val;
            valToWrite = () -> {
                writeBuffer(sdram, address & SH2_SDRAM_MASK, v, size);
            };
        } else {
            writeBuffer(sdram, address & SH2_SDRAM_MASK, val, size);
        }
        int writeType = cpuAccess == MASTER ? MAST_WRITE : SLAVE_WRITE;
        sdramAccess[address & SH2_SDRAM_MASK].accessMask |= writeType;
        sdramAccess[address & SH2_SDRAM_MASK].cycleAccess[writeType] = clock.getCycleCounter();
    }

    public void newFrameSync() {
        for (int i = 0; i < sdramAccess.length; i++) {
            if (sdramAccess[i].accessMask > 2) {
                SdramSync entry = sdramAccess[i];
                int val = entry.accessMask;
                int check = (int) ((val & (MAST_READ | SLAVE_WRITE)) | (val & (MAST_WRITE | SLAVE_READ)));
                boolean ok = val != (MAST_READ | MAST_WRITE) && val != (SLAVE_READ | SLAVE_WRITE) && val != (MAST_READ | SLAVE_READ)
                        && val != (MAST_WRITE | SLAVE_WRITE);
                if (check > 0 && ok) {
                    String s = "";
                    int localMin = Integer.MAX_VALUE;
                    int localMax = Integer.MIN_VALUE;
                    int cnt = 0;
                    for (int j = 0; j < entry.cycleAccess.length; j++) {
                        if (entry.cycleAccess[j] > 0) {
                            cnt++;
                            s += names[j] + ": " + entry.cycleAccess[j] + ",";
                            localMax = Math.max(entry.cycleAccess[j], localMax);
                            localMin = Math.min(entry.cycleAccess[j], localMin);
                        }
                    }
                    int diff = Math.abs(localMax - localMin);
                    if (cnt > 1 && diff < clockDiffMin) {
                        clockDiffMin = Math.max(100, diff);
                        LOG.info("Sync on: {}, {}, clockDiff: {}\n{}", th(i), th(val), diff, s);
                    }
                }
                sdramAccess[i].accessMask = 0;
                Arrays.fill(sdramAccess[i].cycleAccess, 0);
            }
        }
    }
}
