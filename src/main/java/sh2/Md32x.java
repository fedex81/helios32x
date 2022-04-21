package sh2;

import omegadrive.Device;
import omegadrive.SystemLoader;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.sound.PwmProvider;
import omegadrive.system.Genesis;
import omegadrive.system.SystemProvider;
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

    private static final boolean ENABLE_FM, ENABLE_PWM;

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    //3 cycles @ 23Mhz = 1 cycle @ 7.67, 23.01/7.67 = 3
    protected final static int SH2_CYCLE_RATIO = 3;
    private Md32xRuntimeData rt;

    static {
        ENABLE_FM = Boolean.parseBoolean(System.getProperty("helios.32x.fm.enable", "false"));
        ENABLE_PWM = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.enable", "true"));
        SH2_CYCLES_PER_STEP = Integer.parseInt(System.getProperty("helios.32x.sh2.cycles", "64")); //64
        Sh2Context.burstCycles = SH2_CYCLES_PER_STEP;
//        System.setProperty("68k.debug", "true");
//        System.setProperty("z80.debug", "true");                                              wee
//        System.setProperty("sh2.master.debug", "true");
//        System.setProperty("sh2.slave.debug", "true");
        LOG.info("Enable FM: {}, Enable PWM: {}, Sh2Cycles: {}", ENABLE_FM, ENABLE_PWM, SH2_CYCLES_PER_STEP);
    }

    private int nextMSh2Cycle = 0, nextSSh2Cycle = 0;

    private Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;
    private MarsVdp marsVdp;

    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
        systemType = SystemLoader.SystemType.S32X;
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
        ctx.pwm.setPwmProvider(ENABLE_PWM ? sound.getPwm() : PwmProvider.NO_SOUND);
        sound.setEnabled(sound.getFm(), ENABLE_FM);
    }

    public static SystemProvider createNewInstance32x(DisplayWindow emuFrame, boolean debugPerf) {
        return debugPerf ? null : new Md32x(emuFrame);
    }

    @Override
    protected void loop() {
        updateVideoMode(true);
        do {
            run68k();
            runZ80();
            runFM();
            runSh2();
            runDevices();
            //this should be last as it could change the counter
            runVdp();
            counter++;
        } while (!futureDoneFlag);
    }

    //PAL: 1/3.0 gives ~ 450k per frame, 22.8Mhz. but the games are too slow!!!
    //53/7*burstCycles = if burstCycles = 3 -> 23.01Mhz
    protected final void runSh2() {
        if (nextMSh2Cycle == counter) {
            rt.setAccessType(MASTER);
            sh2.run(masterCtx);
            nextMSh2Cycle += Math.max(1, (masterCtx.cycles_ran * 5) >> 4); //5/16 ~= 1/3
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
        }
        if (nextSSh2Cycle == counter) {
            rt.setAccessType(SLAVE);
            sh2.run(slaveCtx);
            nextSSh2Cycle += Math.max(1, (slaveCtx.cycles_ran * 5) >> 4);
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
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
        super.doRendering(fg, stats);
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        //NOTE Sh2s will only start at the next vblank, not immediately when aden switches
        nextMSh2Cycle = Math.max(ctx.s32XMMREG.aden & 1, nextMSh2Cycle - counter);
        nextSSh2Cycle = Math.max(ctx.s32XMMREG.aden & 1, nextMSh2Cycle - counter);
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
}
