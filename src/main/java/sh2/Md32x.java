package sh2;

import omegadrive.Device;
import omegadrive.SystemLoader;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.sound.PwmProvider;
import omegadrive.system.Genesis;
import omegadrive.ui.DisplayWindow;
import omegadrive.vdp.md.GenesisVdp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.MarsLauncherHelper.Sh2LaunchContext;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.MarsVdpRenderContext;
import sh2.vdp.debug.DebugVideoRenderContext;

import java.nio.file.Path;
import java.util.Optional;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;

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
    private Md32xRuntimeData rt;
    private static boolean enable_fm = false, enable_pwm = true;

    static {
        SH2_CYCLES_PER_STEP = 3; //64;
        Sh2Context.burstCycles = SH2_CYCLES_PER_STEP;
        //3 cycles @ 23Mhz = 1 cycle @ 7.67
        SH2_CYCLE_RATIO = 3; //23.01/7.67 = 3
//        System.setProperty("68k.debug", "true");
//        System.setProperty("z80.debug", "true");
//        System.setProperty("sh2.master.debug", "true");
//        System.setProperty("sh2.slave.debug", "true");
//        enable_fm = true;
    }

    private int nextMSh2Cycle = 0, nextSSh2Cycle = 0;

    private Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;
    private MarsVdp marsVdp;

    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
    }

    @Override
    protected void initAfterRomLoad() {
        BiosHolder biosHolder = MarsLauncherHelper.initBios();
        ctx = MarsLauncherHelper.setupRom((S32xBus) bus, this.romFile);
        masterCtx = ctx.masterCtx;
        slaveCtx = ctx.slaveCtx;
        sh2 = ctx.sh2;
        marsVdp = ctx.marsVdp;
        //aden 0 -> cycle = 0 = not running
        nextSSh2Cycle = nextMSh2Cycle = ctx.s32XMMREG.aden & 1;
        marsVdp.updateDebugView(((GenesisVdp) vdp).getDebugViewer());
        super.initAfterRomLoad(); //needs to be last
        //TODO super inits the soundProvider
        ctx.pwm.setPwmProvider(enable_pwm ? sound.getPwm() : PwmProvider.NO_SOUND);
        sound.setEnabled(sound.getFm(), enable_fm);
    }

    @Override
    protected void loop() {
        LOG.info("Starting game loop");
        updateVideoMode(true);
        int cnt;

        try {
            do {
                cnt = counter;
                run68k(cnt);
                runZ80(cnt);
                runFM(cnt);
                runVdp(cnt);
                runSh2(cnt);
                runDevices();
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
            rt.setAccessType(MASTER);
            sh2.run(masterCtx);
            nextMSh2Cycle += Math.max(1, ((masterCtx.cycles_ran + rt.resetCpuDelay()) * 5) >> 5); //5/16 ~= 1/3
        }
        if (nextSSh2Cycle == counter) {
            rt.setAccessType(SLAVE);
            sh2.run(slaveCtx);
            nextSSh2Cycle += Math.max(1, ((slaveCtx.cycles_ran + rt.resetCpuDelay()) * 5) >> 5);
        }
    }

    private void runDevices() {
        ctx.pwm.step(SH2_CYCLE_RATIO);
        ctx.mDevCtx.sh2MMREG.deviceStepSh2Rate(SH2_CYCLE_RATIO);
        ctx.sDevCtx.sh2MMREG.deviceStepSh2Rate(SH2_CYCLE_RATIO);
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
        int[] fg = marsVdp.doCompositeRendering(data, ctx);
        renderScreenLinearInternal(fg, stats);
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        nextMSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        nextSSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        //NOTE Sh2s will only start at the next vblank, not immediately when aden switches
        nextSSh2Cycle = nextMSh2Cycle = ctx.s32XMMREG.aden & 1;
        ctx.pwm.newFrame();
        ctx.mDevCtx.sh2MMREG.newFrame();
        ctx.sDevCtx.sh2MMREG.newFrame();
    }

    @Override
    protected void handleCloseRom() {
        super.handleCloseRom();
        Optional.ofNullable(marsVdp).ifPresent(Device::reset);
        ctx.pwm.reset();
        Md32xRuntimeData.releaseInstance();
    }

    @Override
    public void handleNewRom(Path file) {
        super.handleNewRom(file);
        rt = Md32xRuntimeData.newInstance();
    }

    @Override
    public SystemLoader.SystemType getSystemType() {
        return SystemLoader.SystemType.S32X;
    }
}
