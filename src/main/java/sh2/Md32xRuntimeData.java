package sh2;

import omegadrive.util.LogHelper;
import org.slf4j.Logger;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.cdaValues;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Md32xRuntimeData {

    private static final Logger LOG = LogHelper.getLogger(Md32x.class.getSimpleName());

    private S32xUtil.CpuDeviceAccess accessType = MASTER;
    private int accType = accessType.ordinal();
    private final int[] cpuDelay = new int[cdaValues.length];

    private static Md32xRuntimeData rt;

    private Md32xRuntimeData() {
    }

    public static Md32xRuntimeData newInstance() {
        if (rt != null) {
            LOG.error("Previous instance has not been released! {}", rt);
        }
        Md32xRuntimeData mrt = new Md32xRuntimeData();
        rt = mrt;
        return mrt;
    }

    public static Md32xRuntimeData releaseInstance() {
        Md32xRuntimeData m = rt;
        rt = null;
        return m;
    }

    public final void addCpuDelay(int delay) {
        cpuDelay[accType] += delay;
    }

    public final int resetCpuDelay() {
        int res = cpuDelay[accType];
        cpuDelay[accType] = 0;
        return res;
    }

    public void setAccessType(S32xUtil.CpuDeviceAccess accessType) {
        this.accessType = accessType;
        accType = accessType.ordinal();
    }

    protected S32xUtil.CpuDeviceAccess getAccessType() {
        return accessType;
    }

    public static void addCpuDelayExt(int delay) {
        rt.addCpuDelay(delay);
    }

    public static void addCpuDelayExt(int[][] delays, int deviceType) {
        rt.addCpuDelay(delays[rt.accType][deviceType]);
    }

    public static void setAccessTypeExt(S32xUtil.CpuDeviceAccess accessType) {
        rt.accessType = accessType;
        rt.accType = accessType.ordinal();
    }

    public static int resetCpuDelayExt(int value) {
        int res = rt.cpuDelay[rt.accType];
        rt.cpuDelay[rt.accType] = value;
        return res;
    }

    public static int resetCpuDelayExt() {
        return resetCpuDelayExt(0);
    }

    public static int getCpuDelayExt() {
        return rt.cpuDelay[rt.accType];
    }


    public static S32xUtil.CpuDeviceAccess getAccessTypeExt() {
        return rt.accessType;
    }
}
