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
    private Sh2Emu sh2Emu;
    private Sh2Context masterCtx, slaveCtx;

    public Md32x(DisplayWindow emuFrame) {
        super(emuFrame);
    }

    @Override
    protected void initAfterRomLoad() {
        super.initAfterRomLoad();
        BiosHolder biosHolder = Sh2Launcher.initBios();
        ctx = Sh2Launcher.setupRom(this.romFile);
        sh2Emu = ctx.sh2;
        masterCtx = new Sh2Context(Sh2Emu.Sh2Access.MASTER);
        slaveCtx = new Sh2Context(Sh2Emu.Sh2Access.SLAVE);
        sh2Emu.sh2cpu.reset(masterCtx);
        sh2Emu.sh2cpu.reset(slaveCtx);
    }

    @Override
    protected void loop() {
        LOG.info("Starting game loop");
        updateVideoMode(true);
        int cnt;

        try {
            do {
                cnt = counter;
//                slave.sh2cpu.debugging = true;
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

    protected final void runSh2(int counter) {
        int cycleDelay = 0;
        if (counter % 3 == 0) {
            sh2Emu.run(masterCtx);
            sh2Emu.run(slaveCtx);
            cycleDelay = sh2Emu.sh2cpu.cycles_ran;
        }
    }

    @Override
    public void newFrame() {
        super.newFrame();
//        Util.sleep(1000);
    }
}
