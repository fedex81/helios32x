package sh2;

import omegadrive.system.Genesis;
import omegadrive.ui.DisplayWindow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Md32x extends Genesis {

    private static final Logger LOG = LogManager.getLogger(Md32x.class.getSimpleName());

    protected final static int SH2_DIVIDER = (int) (3d / MCLK_DIVIDER); //23.01Mhz NTSC

    private int nextSh2Cycle = SH2_DIVIDER;

    private Sh2Launcher.Sh2LaunchContext ctx;
    private Sh2 sh2;
    private Sh2Context masterCtx, slaveCtx;

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
//                master.sh2cpu.debugging = true;
//                MC68000WrapperDebug.verboseInst = false;
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

    int masterDelay = 1, slaveDelay = 1;

    protected final void runSh2(int counter) {
        if (counter % 3 == 0) {
            if (--masterDelay == 0) {
                sh2.run(masterCtx);
                masterDelay = masterCtx.cycles_ran;
            }
            if (--slaveDelay == 0) {
                sh2.run(slaveCtx);
                slaveDelay = slaveCtx.cycles_ran;
            }
        }
    }

    @Override
    public void newFrame() {
        super.newFrame();
//        Util.sleep(1000);
    }
}
