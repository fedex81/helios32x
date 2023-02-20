package s32x;

import omegadrive.Device;
import omegadrive.SystemLoader;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.sound.PwmProvider;
import omegadrive.system.Genesis;
import omegadrive.system.SystemProvider;
import omegadrive.ui.DisplayWindow;
import omegadrive.util.LogHelper;
import omegadrive.util.Sleeper;
import omegadrive.util.VideoMode;
import omegadrive.vdp.md.GenesisVdp;
import omegadrive.vdp.util.UpdatableViewer;
import org.slf4j.Logger;
import s32x.bus.S32xBus;
import s32x.event.PollSysEventManager;
import s32x.sh2.Sh2;
import s32x.sh2.Sh2Context;
import s32x.sh2.Sh2Helper;
import s32x.sh2.drc.Ow2DrcOptimizer;
import s32x.util.MarsLauncherHelper;
import s32x.util.MarsLauncherHelper.Sh2LaunchContext;
import s32x.util.Md32xRuntimeData;
import s32x.util.S32xMemView;
import s32x.util.S32xUtil;
import s32x.vdp.MarsVdp;
import s32x.vdp.MarsVdp.MarsVdpRenderContext;
import s32x.vdp.debug.DebugVideoRenderContext;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis implements PollSysEventManager.SysEventListener {

    private static final Logger LOG = LogHelper.getLogger(Md32x.class.getSimpleName());

    private static final boolean ENABLE_FM, ENABLE_PWM;
    public static final boolean SH2_DEBUG_DRC;

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    //3 cycles @ 23Mhz = 1 cycle @ 7.67, 23.01/7.67 = 3
    protected final static int SH2_CYCLE_RATIO = 3;
    private static final double SH2_CYCLE_DIV = 1 / Double.parseDouble(System.getProperty("helios.32x.sh2.cycle.div", "3.0"));
    private static final int CYCLE_TABLE_LEN_MASK = 0xFF;
    private final static int[] sh2CycleTable = new int[CYCLE_TABLE_LEN_MASK + 1];
    private final static Sh2.Sh2Config sh2Config;

    public static final int SH2_SLEEP_VALUE = -10000;

    //NOTE vr helios.32x.sh2.cycles = 32
    //TODO chaotix,break with poll1, see startPollingMaybe
    //TODO fifa, cycles=18 poll0 or cycles 12 poll1
    static {
        boolean prefEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
        boolean drcEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.drc", "true"));
        boolean pollEn = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.poll.detect", "true"));
        boolean ignoreDelays = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.ignore.delays", "false"));
        sh2Config = new Sh2.Sh2Config(prefEn, drcEn, pollEn, ignoreDelays);

        SH2_DEBUG_DRC = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.drc.debug", "false"));
        ENABLE_FM = Boolean.parseBoolean(System.getProperty("helios.32x.fm.enable", "true"));
        ENABLE_PWM = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.enable", "true"));
        SH2_CYCLES_PER_STEP = Integer.parseInt(System.getProperty("helios.32x.sh2.cycles", "32")); //32
        Sh2Context.burstCycles = SH2_CYCLES_PER_STEP;
//        System.setProperty("68k.debug", "true");
//        System.setProperty("helios.68k.debug.mode", "2");
//        System.setProperty("z80.debug", "true");
//        System.setProperty("sh2.master.debug", "true");
//        System.setProperty("sh2.slave.debug", "true");
        LOG.info("Enable FM: {}, Enable PWM: {}, Sh2Cycles: {}", ENABLE_FM, ENABLE_PWM, SH2_CYCLES_PER_STEP);
        for (int i = 0; i < sh2CycleTable.length; i++) {
            sh2CycleTable[i] = Math.max(1, (int) Math.round(i * SH2_CYCLE_DIV));
        }
        S32xUtil.assertPowerOf2Minus1("CYCLE_TABLE_LEN_MASK", CYCLE_TABLE_LEN_MASK);
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
        PollSysEventManager.instance.reset();
        PollSysEventManager.instance.addSysEventListener(getClass().getSimpleName(), this);
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
        assert cycleCounter == 1;
        do {
            run68k();
            runZ80();
            runFM();
            runSh2();
            runDevices();
            //this should be last as it could change the counter
            runVdp();
            cycleCounter++;
        } while (!futureDoneFlag);
    }

    //PAL: 1/3.0 gives ~ 450k per frame, 22.8Mhz. but the games are too slow!!!
    //53/7*burstCycles = if burstCycles = 3 -> 23.01Mhz
    protected final void runSh2() {
        if (nextMSh2Cycle == cycleCounter) {
            assert !PollSysEventManager.currentPollers[0].isPollingActive() : PollSysEventManager.currentPollers[0];
            rt.setAccessType(S32xUtil.CpuDeviceAccess.MASTER);
            sh2.run(masterCtx);
            assert (masterCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == masterCtx.cycles_ran : masterCtx.cycles_ran;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            nextMSh2Cycle += sh2CycleTable[masterCtx.cycles_ran];
        }
        if (nextSSh2Cycle == cycleCounter) {
            assert !PollSysEventManager.currentPollers[1].isPollingActive() : PollSysEventManager.currentPollers[1];
            rt.setAccessType(S32xUtil.CpuDeviceAccess.SLAVE);
            sh2.run(slaveCtx);
            assert (slaveCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == slaveCtx.cycles_ran : slaveCtx.cycles_ran;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            nextSSh2Cycle += sh2CycleTable[slaveCtx.cycles_ran];
        }
    }

    private void runDevices() {
        assert Md32xRuntimeData.getCpuDelayExt() == 0;
        //NOTE if Pwm triggers dreq, the cpuDelay should be assigned to the DMA engine, not to the CPU itself
        ctx.pwm.step(SH2_CYCLE_RATIO);
        ctx.mDevCtx.sh2MMREG.deviceStepSh2Rate(SH2_CYCLE_RATIO);
        ctx.sDevCtx.sh2MMREG.deviceStepSh2Rate(SH2_CYCLE_RATIO);
        assert Md32xRuntimeData.getCpuDelayExt() == 0;
    }

    @Override
    protected GenesisBusProvider createBus() {
        return new S32xBus();
    }

    @Override
    protected void doRendering(VideoMode mdVideoMode, int[] data, Optional<String> stats) {
        MarsVdpRenderContext ctx = marsVdp.getMarsVdpRenderContext();
        boolean dumpComposite = false, dumpMars = false;
        if (dumpComposite) {
            DebugVideoRenderContext.dumpCompositeData(ctx, data, mdVideoMode);
        }
        if (dumpMars) {
            marsVdp.dumpMarsData();
        }
        int[] fg = marsVdp.doCompositeRendering(mdVideoMode, data, ctx);
        super.doRendering(ctx.vdpContext.videoMode, fg, stats);
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        assert counter >= 0;
        if (nextMSh2Cycle >= 0) {
            //NOTE Sh2s will only start at the next vblank, not immediately when aden switches
            nextMSh2Cycle = Math.max(ctx.s32XMMREG.aden & 1, nextMSh2Cycle - counter);
        }
        if (nextSSh2Cycle >= 0) {
            nextSSh2Cycle = Math.max(ctx.s32XMMREG.aden & 1, nextSSh2Cycle - counter);
        }
        ctx.pwm.newFrame();
        ctx.mDevCtx.sh2MMREG.newFrame();
        ctx.sDevCtx.sh2MMREG.newFrame();
        ctx.memory.newFrame();
        if (verbose) LOG.info("New frame: {}", telemetry.getFrameCounter());
        if (telemetry.getFrameCounter() % getRegion().getFps() == 0) { //reset every second
            slowFramesAcc = 0;
        }
    }

    private long slowFramesAcc;

    @Override
    protected long syncCycle(long startCycle) {
        long now = System.nanoTime();
        if (fullThrottle) {
            return now;
        }
        long baseRemainingNs = startCycle + targetNs;
        long remainingNs = baseRemainingNs - now;
        slowFramesAcc += remainingNs;
        if (slowFramesAcc > 0) {
            remainingNs = slowFramesAcc;
            slowFramesAcc = 0;
        }
        if (remainingNs > 0) { //too fast
            Sleeper.parkFuzzy(remainingNs);
            remainingNs = baseRemainingNs - System.nanoTime();
        }
        return System.nanoTime();
    }

    protected UpdatableViewer createMemView() {
        return S32xMemView.createInstance(bus, ctx.memory, vdp.getVdpMemory());
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
        Sh2Helper.clear();
    }

    @Override
    public void onSysEvent(S32xUtil.CpuDeviceAccess cpu, PollSysEventManager.SysEvent event) {
        switch (event) {
            case START_POLLING -> {
                final Ow2DrcOptimizer.PollerCtx pc = PollSysEventManager.instance.getPoller(cpu);
                assert pc.isPollingActive() : event + "," + pc;
                setNextCycle(cpu, SH2_SLEEP_VALUE);
                Md32xRuntimeData.resetCpuDelayExt(cpu, 0);
                if (verbose) LOG.info("{} {} {}: {}", cpu, event, cycleCounter, pc);
            }
            case SH2_RESET_ON -> {
                setNextCycle(S32xUtil.CpuDeviceAccess.MASTER, SH2_SLEEP_VALUE);
                setNextCycle(S32xUtil.CpuDeviceAccess.SLAVE, SH2_SLEEP_VALUE);
            }
            case SH2_RESET_OFF -> {
                setNextCycle(S32xUtil.CpuDeviceAccess.MASTER, cycleCounter + 1);
                setNextCycle(S32xUtil.CpuDeviceAccess.SLAVE, cycleCounter + 2);
                Md32xRuntimeData.resetCpuDelayExt(S32xUtil.CpuDeviceAccess.MASTER, 0);
                Md32xRuntimeData.resetCpuDelayExt(S32xUtil.CpuDeviceAccess.SLAVE, 0);
            }
            default -> { //stop polling
                final Ow2DrcOptimizer.PollerCtx pc = PollSysEventManager.instance.getPoller(cpu);
                stopPolling(cpu, event, pc);
            }
        }
    }

    private void stopPolling(S32xUtil.CpuDeviceAccess cpu, PollSysEventManager.SysEvent event, Ow2DrcOptimizer.PollerCtx pctx) {
//        assert event == SysEventManager.SysEvent.INT ? pc.isPollingBusyLoop() : true;
        boolean stopOk = event == pctx.event || event == PollSysEventManager.SysEvent.INT;
        if (stopOk) {
            if (verbose) LOG.info("{} stop polling {} {}: {}", cpu, event, cycleCounter, pctx);
            setNextCycle(cpu, cycleCounter + 1);
            assert PollSysEventManager.pollValueCheck(cpu, event, pctx);
            PollSysEventManager.instance.resetPoller(cpu);
        } else {
            LOG.warn("{} {} ignore stop polling: {}", cpu, event, pctx);
        }
    }

    private void setNextCycle(S32xUtil.CpuDeviceAccess cpu, int value) {
        if (verbose) LOG.info("{} {} sleeping, nextCycle: {}", cpu, value < 0 ? "START" : "STOP", value);
        if (cpu == S32xUtil.CpuDeviceAccess.MASTER) {
            nextMSh2Cycle = value;
        } else {
            nextSSh2Cycle = value;
        }
    }
}