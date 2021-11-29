package sh2;

import omegadrive.system.Genesis;
import omegadrive.ui.DisplayWindow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis {

    private static final Logger LOG = LogManager.getLogger(Md32x.class.getSimpleName());

    //23.01Mhz NTSC
    protected final static int SH2_CYCLES_PER_STEP;
    protected final static int SH2_DIVIDER;

    //cps = 6, div = 14 -> 53.6/7*3 = 23.01 correct speed
    //cps = 3, div = 7 -> 53.6/7*3 = 23.01 correct speed
    //cps = 1, div = 3 -> 53.6/3 lockstep, underclock
    //cps = 1, div = 2 -> 53.6/2 lockstep, overclock
    static {
        SH2_CYCLES_PER_STEP = 1;
        Sh2.burstCycles = SH2_CYCLES_PER_STEP;
        if (SH2_CYCLES_PER_STEP == 1) {
            SH2_DIVIDER = 3; //2
        } else {
            SH2_DIVIDER = MCLK_DIVIDER * SH2_CYCLES_PER_STEP / 3;
        }
    }

    private int nextMSh2Cycle = 1, nextSSh2Cycle = 1;

    private Sh2Launcher.Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;
    private MarsVdp marsVdp;


    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
    }

    @Override
    protected void initAfterRomLoad() {
        super.initAfterRomLoad();
        BiosHolder biosHolder = Sh2Launcher.initBios();
        ctx = Sh2Launcher.setupRom(this.romFile);
        sh2 = ctx.sh2;
        masterCtx = new Sh2Context(Sh2Util.Sh2Access.MASTER);
        slaveCtx = new Sh2Context(Sh2Util.Sh2Access.SLAVE);
        sh2.reset(masterCtx);
        sh2.reset(slaveCtx);

        marsVdp = S32XMMREG.instance.getVdp();
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
            sh2.run(masterCtx);
            nextMSh2Cycle += masterCtx.cycles_ran * SH2_DIVIDER;
        }
        if (nextSSh2Cycle == counter) {
            sh2.run(slaveCtx);
            nextSSh2Cycle += slaveCtx.cycles_ran * SH2_DIVIDER;
        }
    }

    @Override
    protected void doRendering(int[] data, Optional<String> stats) {
        int mdDataLen = data.length;
        int[] marsData = marsVdp.getScreenDataLinear();
        if (mdDataLen == marsData.length) {
            //TODO this just overwrites the MD buffer with the 32x buffer
            data = marsData;
        } else {
            //bootstrap, 32x not ready
        }
        renderScreenLinearInternal(data, stats);
    }

    @Override
    protected void resetCycleCounters(int counter) {
        super.resetCycleCounters(counter);
        nextMSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
        nextSSh2Cycle = Math.max(1, nextMSh2Cycle - counter);
    }
}
