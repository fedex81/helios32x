package sh2;

import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.system.Genesis;
import omegadrive.ui.DisplayWindow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.vdp.MarsVdp;

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

    static {
        SH2_CYCLES_PER_STEP = 3;
        Sh2.burstCycles = SH2_CYCLES_PER_STEP;
        //3 cycles @ 23Mhz = 1 cycle @ 7.67
        SH2_CYCLE_RATIO = 3; //23.01/7.67 = 3
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
//                MC68000WrapperDebug.verboseInst = true;
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
            nextMSh2Cycle += (masterCtx.cycles_ran + resetCpuDelay()) / SH2_CYCLE_RATIO;
        }
        if (nextSSh2Cycle == counter) {
            setAccessType(SLAVE);
            sh2.run(slaveCtx);
            nextSSh2Cycle += (slaveCtx.cycles_ran + resetCpuDelay()) / SH2_CYCLE_RATIO;
        }
    }

    @Override
    protected GenesisBusProvider createBus() {
        return new S32xBus();
    }

    @Override
    protected void doRendering(int[] data, Optional<String> stats) {
        int mdDataLen = data.length;
        MarsVdp.MarsVdpRenderContext ctx = marsVdp.getMarsVdpRenderContext();
        if (ctx.vdpContext.priority == MarsVdp.VdpPriority.S32X) {
            int[] marsData = ctx.screen;
            if (mdDataLen == marsData.length) {
                //TODO this just overwrites the MD buffer with the 32x buffer
                data = marsData;
            } else {
                //bootstrap, 32x not ready
            }
        }
        renderScreenLinearInternal(data, stats);
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        nextMSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        nextSSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        //NOTE Sh2s will only start at the next vblank, not immediately when aden switches
        nextSSh2Cycle = nextMSh2Cycle = ctx.s32XMMREG.aden & 1;
    }
}
