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
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.vdp.MarsVdp;
import sh2.vdp.MarsVdp.MarsVdpRenderContext;
import sh2.vdp.debug.DebugVideoRenderContext;

import java.nio.file.Path;
import java.util.Optional;

import static sh2.S32xUtil.CpuDeviceAccess.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis {

    private static final Logger LOG = LogHelper.getLogger(Md32x.class.getSimpleName());

    //TODO T-Mek needs {cache on, prefetch off, ignoreDelays false} or
    // {cache on, prefetch on, ignoreDelays true}
    //TODO vr needs
    // - SH2_CYCLE_DIV = 8.0 or
    // - SH2_IGNORE_DELAYS = true, SH2_CYCLES_PER_STEP = 32
    public static final boolean ENABLE_FM, ENABLE_PWM, SH2_ENABLE_PREFETCH, SH2_ENABLE_CACHE, SH2_IGNORE_DELAYS;

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    //3 cycles @ 23Mhz = 1 cycle @ 7.67, 23.01/7.67 = 3
    protected final static int SH2_CYCLE_RATIO = 3;
    private Md32xRuntimeData rt;
    private static final double SH2_CYCLE_DIV = 1 / Double.parseDouble(System.getProperty("helios.32x.sh2.cycle.div", "3.0"));
    private static final int CYCLE_TABLE_LEN_MASK = 0xFF;
    private final static int[] sh2CycleTable = new int[CYCLE_TABLE_LEN_MASK + 1];

    static {
        SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));
        SH2_ENABLE_CACHE = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.cache", "false"));
        ENABLE_FM = Boolean.parseBoolean(System.getProperty("helios.32x.fm.enable", "true"));
        ENABLE_PWM = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.enable", "true"));
        SH2_IGNORE_DELAYS = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.ignore.delays", "false"));
        //NOTE: for max compat use 1
        SH2_CYCLES_PER_STEP = Integer.parseInt(System.getProperty("helios.32x.sh2.cycles", "32")); //32;
        Sh2Context.burstCycles = SH2_CYCLES_PER_STEP;
//        System.setProperty("68k.debug", "false");
//        System.setProperty("helios.68k.busy.loop", "false");
//        if(System.getProperty("68k.debug").startsWith("t")) {
//            System.setProperty("helios.68k.debug.mode", "0"); //0-NONE, 1-INST_ONLY, 2-NEW_INST_ONLY, 3-STATE
//        }
//        System.setProperty("z80.debug", "true");
//        System.setProperty("helios.z80.debug.mode", "2");
//        System.setProperty("sh2.master.debug", "true");
//        System.setProperty("sh2.slave.debug", "true");
        LOG.info("Enable FM: {}, Enable PWM: {}, Sh2Cycles: {}", ENABLE_FM, ENABLE_PWM, SH2_CYCLES_PER_STEP);
        for (int i = 0; i < sh2CycleTable.length; i++) {
            sh2CycleTable[i] = Math.max(1, (int) Math.round(i * SH2_CYCLE_DIV));
        }
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

    protected final void runVdp() {
        if (counter >= nextVdpCycle) {
            int vdpMclk = vdp.runSlot();
            nextVdpCycle += vdpVals[vdpMclk - 4];
        }
    }

    protected final void run68k() {
        if (counter == next68kCycle) {
            boolean isRunning = bus.is68kRunning();
            boolean canRun = !cpu.isStopped() && isRunning;
            int cycleDelay = 1;
            if (canRun) {
                Md32xRuntimeData.setAccessTypeExt(M68K);
                cycleDelay = cpu.runInstruction() + Md32xRuntimeData.resetCpuDelayExt();
            }
            //interrupts are processed after the current instruction
            if (isRunning) {
                bus.handleVdpInterrupts68k();
            }
            cycleDelay = Math.max(1, cycleDelay);
            next68kCycle += M68K_DIVIDER * cycleDelay;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
        }
    }

    protected final void runZ80() {
        if (counter == nextZ80Cycle) {
            int cycleDelay = 0;
            boolean running = bus.isZ80Running();
            if (running) {
                Md32xRuntimeData.setAccessTypeExt(Z80);
                cycleDelay = z80.executeInstruction();
                bus.handleVdpInterruptsZ80();
                cycleDelay += Md32xRuntimeData.resetCpuDelayExt();
            }
            cycleDelay = Math.max(1, cycleDelay);
            nextZ80Cycle += Z80_DIVIDER * cycleDelay;
        }
    }

    protected final void runFM() {
        if ((counter & 1) == 0 && (counter % FM_DIVIDER) == 0) { //perf, avoid some divs
            bus.getFm().tick();
        }
    }

    //PAL: 1/3.0 gives ~ 450k per frame, 22.8Mhz. but the games are too slow!!!
    //53/7*burstCycles = if burstCycles = 3 -> 23.01Mhz
    protected final void runSh2() {
        if (nextMSh2Cycle == counter) {
            rt.setAccessType(MASTER);
            sh2.run(masterCtx);
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            assert (masterCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == masterCtx.cycles_ran : masterCtx.cycles_ran;
            nextMSh2Cycle += sh2CycleTable[masterCtx.cycles_ran & CYCLE_TABLE_LEN_MASK];
        }
        if (nextSSh2Cycle == counter) {
            rt.setAccessType(SLAVE);
            sh2.run(slaveCtx);
            assert (slaveCtx.cycles_ran & CYCLE_TABLE_LEN_MASK) == slaveCtx.cycles_ran : slaveCtx.cycles_ran;
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
            nextSSh2Cycle += sh2CycleTable[slaveCtx.cycles_ran & CYCLE_TABLE_LEN_MASK];
        }
        if (S32XMMREG.resetSh2) {
            nextMSh2Cycle = nextSSh2Cycle = Integer.MAX_VALUE;
            return;
        }
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
}
