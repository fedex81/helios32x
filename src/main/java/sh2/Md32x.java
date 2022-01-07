package sh2;

import omegadrive.Device;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.system.Genesis;
import omegadrive.ui.DisplayWindow;
import omegadrive.vdp.md.GenesisVdp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.MarsVdpRenderContext;
import sh2.vdp.MarsVdp.VdpPriority;
import sh2.vdp.debug.DebugVideoRenderContext;

import java.nio.file.Path;
import java.util.Optional;

import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.vdp.MarsVdp.VdpPriority.S32X;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis {

    private static final Logger LOG = LogManager.getLogger(Md32x.class.getSimpleName());

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    protected final static int SH2_CYCLE_RATIO;
    private static TLData tlData;

    static {
        SH2_CYCLES_PER_STEP = 128; //24;
        Sh2.burstCycles = SH2_CYCLES_PER_STEP;
        //3 cycles @ 23Mhz = 1 cycle @ 7.67
        SH2_CYCLE_RATIO = 3; //23.01/7.67 = 3
//        System.setProperty("68k.debug", "true");
    }

    static class TLData {
        S32xUtil.CpuDeviceAccess accessType = MASTER;
        int accType = accessType.ordinal();
        int[] cpuDelay = new int[vals.length];

        public final void addCpuDelay(int delay) {
            cpuDelay[accType] += delay;
        }

        public final int resetCpuDelay() {
            int res = cpuDelay[accType];
            cpuDelay[accType] = 0;
            return res;
        }

        protected void setAccessType(S32xUtil.CpuDeviceAccess accessType) {
            this.accessType = accessType;
            accType = accessType.ordinal();
        }

        protected S32xUtil.CpuDeviceAccess getAccessType() {
            return accessType;
        }
    }

    public static void addCpuDelay(int delay) {
        tlData.addCpuDelay(delay);
    }

    public static int resetCpuDelay() {
        return tlData.resetCpuDelay();
    }

    public static void setAccessType(S32xUtil.CpuDeviceAccess access) {
        tlData.setAccessType(access);
    }

    public static S32xUtil.CpuDeviceAccess getAccessType() {
        return tlData.getAccessType();
    }

    //test only
    public static void initTlData() {
        tlData = new TLData();
    }

    private int nextMSh2Cycle = 0, nextSSh2Cycle = 0;

    private Sh2Launcher.Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;
    private MarsVdp marsVdp;

    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
    }

    @Override
    protected void initAfterRomLoad() {
        BiosHolder biosHolder = Sh2Launcher.initBios();
        ctx = Sh2Launcher.setupRom((S32xBus) bus, this.romFile);
        masterCtx = ctx.masterCtx;
        slaveCtx = ctx.slaveCtx;
        sh2 = ctx.sh2;
        marsVdp = ctx.marsVdp;
        //aden 0 -> cycle = 0 = not running
        nextSSh2Cycle = nextMSh2Cycle = ctx.s32XMMREG.aden & 1;
        marsVdp.updateDebugView(((GenesisVdp) vdp).getDebugViewer());
        super.initAfterRomLoad(); //needs to be last
    }

    @Override
    protected void loop() {
        LOG.info("Starting game loop");
        updateVideoMode(true);
        int cnt;

        try {
            do {
                cnt = counter;
//                slaveCtx.debug = true;
//                masterCtx.debug = true;
                run68k(cnt);
                runZ80(cnt);
                runFM(cnt);
                runVdp(cnt);
                runSh2(cnt);
                counter++;
            } while (!futureDoneFlag);
        } catch (Exception e) {
            LOG.error("Error main cycle", e);
        }
        LOG.info("Exiting rom thread loop");
    }

    //53/7*burstCycles = if burstCycles = 3 -> 23.01Mhz
    protected final void runSh2(int counter) {
        if (nextMSh2Cycle == counter) {
            setAccessType(MASTER);
            sh2.run(masterCtx);
            nextMSh2Cycle += ((masterCtx.cycles_ran + resetCpuDelay()) * 5) >> 4; //5/16 ~= 1/3
        }
        if (nextSSh2Cycle == counter) {
            setAccessType(SLAVE);
            sh2.run(slaveCtx);
            nextSSh2Cycle += ((slaveCtx.cycles_ran + resetCpuDelay()) * 5) >> 4;
        }
    }

    @Override
    protected GenesisBusProvider createBus() {
        return new S32xBus();
    }

    @Override
    protected void doRendering(int[] data, Optional<String> stats) {
        MarsVdpRenderContext ctx = marsVdp.getMarsVdpRenderContext();
        boolean dumpComposite = false, dumpMars = false;
        if (dumpComposite) {
            DebugVideoRenderContext.dumpCompositeData(ctx, data);
        }
        if (dumpMars) {
            marsVdp.dumpMarsData();
        }
        int[] fg = doRendering(data, ctx);
        renderScreenLinearInternal(fg, stats);
    }

    public static int[] doRendering(int[] data, MarsVdpRenderContext ctx) {
        int mdDataLen = data.length;
        int[] marsData = Optional.ofNullable(ctx.screen).orElse(new int[0]);
        int[] fg = data;
        boolean dump = false;
        if (mdDataLen == marsData.length) {
            VdpPriority p = ctx.vdpContext.priority;
            fg = p == S32X ? marsData : data;
            int[] bg = p == S32X ? data : marsData;
            for (int i = 0; i < fg.length; i++) {
                boolean throughBit = (marsData[i] & 1) > 0;
                boolean bgBlanking = p == S32X ? (data[i] & 1) > 0 : (marsData[i] >> 1) == 0;
                boolean fgBlanking = p == S32X ? (marsData[i] >> 1) == 0 : (data[i] & 1) > 0;
//                if(dump) {
//                    String s = i + "," + p + "," + (throughBit ? 1 : 0) + "," + (fgBlanking ? 1 : 0) + (bgBlanking ? 1 : 0) + ","
//                            + "," + data[i] + "," + marsData[i];
//                    fg[i] = fgBlanking || (throughBit && !bgBlanking) ? bg[i] : fg[i];
//                    System.out.println(s + "," + fg[i]);
//                } else {
                fg[i] = fgBlanking || (throughBit && !bgBlanking) ? bg[i] : fg[i];
//                }
            }
        }
        return fg;
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        nextMSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        nextSSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        //NOTE Sh2s will only start at the next vblank, not immediately when aden switches
        nextSSh2Cycle = nextMSh2Cycle = ctx.s32XMMREG.aden & 1;
    }

    @Override
    protected void handleCloseRom() {
        super.handleCloseRom();
        Optional.ofNullable(marsVdp).ifPresent(Device::reset);
        tlData = null;
    }

    @Override
    public void handleNewRom(Path file) {
        super.handleNewRom(file);
        initTlData();
    }
}
