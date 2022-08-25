package sh2;

import omegadrive.Device;
import omegadrive.SystemLoader;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.sound.PwmProvider;
import omegadrive.system.Genesis;
import omegadrive.system.SystemProvider;
import omegadrive.ui.DisplayWindow;
import omegadrive.util.LogHelper;
import omegadrive.vdp.md.GenesisVdp;
import org.slf4j.Logger;
import sh2.MarsLauncherHelper.Sh2LaunchContext;
import sh2.event.SysEventManager;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2.Sh2Config;
import sh2.sh2.Sh2Context;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.MarsVdpRenderContext;
import sh2.vdp.debug.DebugVideoRenderContext;

import java.nio.file.Path;
import java.util.Optional;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis implements SysEventManager.SysEventListener {

    private static final Logger LOG = LogHelper.getLogger(Md32x.class.getSimpleName());

    private static final boolean ENABLE_FM, ENABLE_PWM;

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    //3 cycles @ 23Mhz = 1 cycle @ 7.67, 23.01/7.67 = 3
    protected final static int SH2_CYCLE_RATIO = 3;

    //TODO vr needs ~ 1/6,SH2_CYCLES_PER_STEP=32
    private static final double SH2_CYCLE_DIV = 1 / Double.parseDouble(System.getProperty("helios.32x.sh2.cycle.div", "3.0"));
    private static final int CYCLE_TABLE_LEN_MASK = 0xFF;
    private final static int[] sh2CycleTable = new int[CYCLE_TABLE_LEN_MASK + 1];
    private final static Sh2Config sh2Config;

    static {
        boolean prefEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
        boolean drcEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.drc", "true"));
        boolean cacheEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.cache", "false"));
        //TODO stellar assault, chaotix
        boolean pollEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.poll.detect", "true"));
        boolean ignoreDelays = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.ignore.delays", "false"));
        sh2Config = new Sh2Config(prefEn, cacheEn, drcEn, pollEn, ignoreDelays);

        ENABLE_FM = Boolean.parseBoolean(System.getProperty("helios.32x.fm.enable", "true"));
        ENABLE_PWM = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.enable", "true"));
        SH2_CYCLES_PER_STEP = Integer.parseInt(System.getProperty("helios.32x.sh2.cycles", "32")); //32
        Sh2Context.burstCycles = SH2_CYCLES_PER_STEP;
//        System.setProperty("68k.debug", "true");
//        System.setProperty("z80.debug", "true");
//        System.setProperty("sh2.master.debug", "true");
//        System.setProperty("sh2.slave.debug", "true");
        LOG.info("Enable FM: {}, Enable PWM: {}, Sh2Cycles: {}", ENABLE_FM, ENABLE_PWM, SH2_CYCLES_PER_STEP);
        for (int i = 0; i < sh2CycleTable.length; i++) {
            sh2CycleTable[i] = Math.max(1, (int) Math.round(i * SH2_CYCLE_DIV));
        }

    }

    public int nextMSh2Cycle = 0, nextSSh2Cycle = 0;
    private Md32xRuntimeData rt;
    private Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;
    private MarsVdp marsVdp;

    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
        systemType = SystemLoader.SystemType.S32X;
        SysEventManager.instance.reset();
        SysEventManager.instance.addSysEventListener(getClass().getSimpleName(), this);
    }

    @Override
    protected void initAfterRomLoad() {
        ctx = MarsLauncherHelper.setupRom((S32xBus) bus, memory.getRomHolder());
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
            assert (masterCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == masterCtx.cycles_ran : masterCtx.cycles_ran;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            nextMSh2Cycle += sh2CycleTable[masterCtx.cycles_ran];
        }
        if (nextSSh2Cycle == counter) {
            rt.setAccessType(SLAVE);
            sh2.run(slaveCtx);
            assert (slaveCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == slaveCtx.cycles_ran : slaveCtx.cycles_ran;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            nextSSh2Cycle += sh2CycleTable[slaveCtx.cycles_ran];
        }
        //TODO check
//        if (S32XMMREG.resetSh2) {
//            nextMSh2Cycle = nextSSh2Cycle = Integer.MAX_VALUE;
//            return;
//        }
    }

    private void runDevices() {
        ctx.pwm.step(SH2_CYCLE_RATIO);
        //TODO if Pwm triggers dreq, who owns the cpuDelay?
        //TODO obv. the CPU that has dreq triggered
//        assert Md32xRuntimeData.resetCpuDelayExt() == 0;
        Md32xRuntimeData.resetCpuDelayExt();
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
        ctx.memory.newFrame();
        if (verbose) LOG.info("New frame");
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
    public void onSysEvent(S32xUtil.CpuDeviceAccess cpu, SysEventManager.SysEvent event) {
        final Ow2DrcOptimizer.PollerCtx pc = SysEventManager.currentPollers[cpu.ordinal()];
        final Sh2Context sh2Context = cpu == MASTER ? masterCtx : slaveCtx;
        switch (event) {
            case START_POLLING -> {
                assert !pc.isPollingActive() : event + "," + pc;
                pc.pollState = Ow2DrcOptimizer.PollState.ACTIVE_POLL;
                if (verbose) LOG.info("{} {} {}: {}", cpu, event, counter, pc);
                if (pc.cpu == S32xUtil.CpuDeviceAccess.MASTER) {
                    nextMSh2Cycle = 1;
                } else {
                    nextSSh2Cycle = 1;
                }
                sh2Context.cycles = -1;
                Md32xRuntimeData.resetCpuDelayExt();
            }
            default -> { //stop polling
                if (pc.isPollingActive()) {
                    stopPolling(cpu, event, pc);
                } else {
                    LOG.warn("{} {} but polling inactive: {}", cpu, event, pc);
                }
            }
        }
    }

    private void stopPolling(S32xUtil.CpuDeviceAccess cpu, SysEventManager.SysEvent event, Ow2DrcOptimizer.PollerCtx pc) {
        assert event == SysEventManager.SysEvent.INT ? pc.isPollingBusyLoop() : true;
        boolean stopOk = event == pc.event || event == SysEventManager.SysEvent.INT;
        if (stopOk) {
            pc.stopPolling();
            if (cpu == SLAVE) {
                nextSSh2Cycle = counter + 1;
            } else {
                nextMSh2Cycle = counter + 1;
            }
            if (verbose) LOG.info("{} {} {}: {}", cpu, event, counter, pc);
            SysEventManager.currentPollers[cpu.ordinal()] = NO_POLLER;
        } else {
            LOG.warn("{} {} ignore stop polling: {}", cpu, event, pc);
        }
    }
}
