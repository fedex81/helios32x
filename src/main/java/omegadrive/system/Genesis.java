/*
 * Genesis
 * Copyright (c) 2018-2019 Federico Berti
 * Last modified: 26/10/19 15:29
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package omegadrive.system;

import omegadrive.SystemLoader;
import omegadrive.bus.md.GenesisBus;
import omegadrive.bus.md.SvpMapper;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.cart.MdCartInfoProvider;
import omegadrive.cpu.m68k.M68kProvider;
import omegadrive.cpu.m68k.MC68000Wrapper;
import omegadrive.cpu.ssp16.Ssp16;
import omegadrive.cpu.z80.Z80CoreWrapper;
import omegadrive.cpu.z80.Z80Provider;
import omegadrive.input.InputProvider;
import omegadrive.joypad.GenesisJoypad;
import omegadrive.memory.IMemoryProvider;
import omegadrive.memory.MemoryProvider;
import omegadrive.savestate.BaseStateHandler;
import omegadrive.sound.SoundProvider;
import omegadrive.ui.DisplayWindow;
import omegadrive.util.LogHelper;
import omegadrive.util.RegionDetector;
import omegadrive.util.Util;
import omegadrive.vdp.model.BaseVdpProvider;
import omegadrive.vdp.model.GenesisVdpProvider;
import omegadrive.vdp.util.MemView;
import omegadrive.vdp.util.UpdatableViewer;
import org.slf4j.Logger;
import s32x.util.Md32xRuntimeData;

import java.nio.file.Path;
import java.util.Optional;

import static s32x.util.S32xUtil.CpuDeviceAccess.M68K;
import static s32x.util.S32xUtil.CpuDeviceAccess.Z80;

/**
 * Genesis emulator main class
 * <p>
 * MEMORY MAP:	https://en.wikibooks.org/wiki/Genesis_Programming
 */
public class Genesis extends BaseSystem<GenesisBusProvider> {

    public final static boolean verbose = false;
    public final static int NTSC_MCLOCK_MHZ = 53693175;
    public final static int PAL_MCLOCK_MHZ = 53203424;
    //the emulation runs at MCLOCK_MHZ/MCLK_DIVIDER
    protected final static int MCLK_DIVIDER = 7;
    protected final static double VDP_RATIO = 4.0 / MCLK_DIVIDER;  //16 -> MCLK/4, 20 -> MCLK/5
    protected final static int M68K_DIVIDER = 7 / MCLK_DIVIDER;
    final static double[] vdpVals = {VDP_RATIO * BaseVdpProvider.MCLK_DIVIDER_FAST_VDP, VDP_RATIO * BaseVdpProvider.MCLK_DIVIDER_SLOW_VDP};
    protected final static int Z80_DIVIDER = 14 / MCLK_DIVIDER;
    protected final static int FM_DIVIDER = 42 / MCLK_DIVIDER;
    private final static Logger LOG = LogHelper.getLogger(Genesis.class.getSimpleName());

    protected Z80Provider z80;
    protected M68kProvider cpu;
    protected Ssp16 ssp16 = Ssp16.NO_SVP;
    protected UpdatableViewer memView;
    protected boolean hasSvp = ssp16 != Ssp16.NO_SVP;
    protected double nextVdpCycle = vdpVals[0];
    protected int next68kCycle = M68K_DIVIDER;
    protected int nextZ80Cycle = Z80_DIVIDER;

    protected Genesis(DisplayWindow emuFrame) {
        super(emuFrame);
        systemType = SystemLoader.SystemType.GENESIS;
    }

    public static SystemProvider createNewInstance(DisplayWindow emuFrame) {
        return new Genesis(emuFrame);
    }

    @Override
    public void init() {
        stateHandler = BaseStateHandler.EMPTY_STATE;
        joypad = GenesisJoypad.create(this);
        inputProvider = InputProvider.createInstance(joypad);

        memory = MemoryProvider.createGenesisInstance();
        bus = createBus();
        vdp = GenesisVdpProvider.createVdp(bus);
        cpu = MC68000Wrapper.createInstance(bus);
        z80 = Z80CoreWrapper.createInstance(getSystemType(), bus);
        //sound attached later
        sound = SoundProvider.NO_SOUND;

        bus.attachDevice(this).attachDevice(memory).attachDevice(joypad).attachDevice(vdp).
                attachDevice(cpu).attachDevice(z80);
        reloadWindowState();
        createAndAddVdpEventListener();
    }

    static final int SVP_CYCLES = 128;
    static final int SVP_CYCLES_MASK = SVP_CYCLES - 1;
    static final int SVP_RUN_CYCLES = (int) (SVP_CYCLES * 1.5);


    protected void loop() {
        updateVideoMode(true);
        do {
            run68k();
            runZ80();
            runFM();
            if (hasSvp && (cycleCounter & SVP_CYCLES_MASK) == 0) {
                ssp16.ssp1601_run(SVP_RUN_CYCLES);
            }
            //this should be last as it could change the counter
            runVdp();
            cycleCounter++;
        } while (!futureDoneFlag);
    }

    protected final void runVdp() {
        if (cycleCounter >= nextVdpCycle) {
            int vdpMclk = vdp.runSlot();
            nextVdpCycle += vdpVals[vdpMclk - 4];
        }
    }

    protected final void run68k() {
        if (cycleCounter == next68kCycle) {
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
        if (cycleCounter == nextZ80Cycle) {
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
            assert Md32xRuntimeData.resetCpuDelayExt() == 0;
        }
    }

    protected final void runFM() {
        if ((cycleCounter & 1) == 0 && (cycleCounter % FM_DIVIDER) == 0) { //perf, avoid some divs
            bus.getFm().tick();
        }
    }

    protected GenesisBusProvider createBus() {
        return new GenesisBus();
    }

    @Override
    protected void updateVideoMode(boolean force) {
        if (force || videoMode != vdp.getVideoMode()) {
            videoMode = vdp.getVideoMode();
            double microsPerTick = getMicrosPerTick();
            sound.getFm().setMicrosPerTick(microsPerTick);
            targetNs = (long) (getRegion().getFrameIntervalMs() * Util.MILLI_IN_NS);
            LOG.info("Video mode changed: {}, microsPerTick: {}", videoMode, microsPerTick);
        }
    }

    private double getMicrosPerTick() {
        double mclkhz = videoMode.isPal() ? Util.GEN_PAL_MCLOCK_MHZ : Util.GEN_NTSC_MCLOCK_MHZ;
        return 1_000_000.0 / (mclkhz / (FM_DIVIDER * MCLK_DIVIDER));
    }

    @Override
    protected RegionDetector.Region getRegionInternal(IMemoryProvider memory, String regionOvr) {
        RegionDetector.Region romRegion = RegionDetector.detectRegion(memory);
        RegionDetector.Region ovrRegion = RegionDetector.getRegion(regionOvr);
        if (ovrRegion != null && ovrRegion != romRegion) {
            LOG.info("Setting region override from: {} to {}", romRegion, ovrRegion);
            romRegion = ovrRegion;
        }
        return romRegion;
    }

    @Override
    protected RomContext createRomContext(Path rom) {
        RomContext rc = new RomContext();
        rc.romPath = rom;
        MdCartInfoProvider mcip = MdCartInfoProvider.createInstance(memory, rc.romPath);
        rc.cartridgeInfoProvider = mcip;
        String regionOverride = Optional.ofNullable(mcip.getEntry().forceRegion).
                orElse(emuFrame.getRegionOverride());
        rc.region = getRegionInternal(memory, regionOverride);
        return rc;
    }

    @Override
    public void newFrame() {
        checkSvp();
        memView.update();
        super.newFrame();
    }

    private void checkSvp() {
        ssp16 = SvpMapper.ssp16;
        hasSvp = ssp16 != Ssp16.NO_SVP;
    }

    protected UpdatableViewer createMemView() {
        return MemView.createInstance(bus, vdp.getVdpMemory());
    }

    /**
     * Counters can go negative when the video mode changes
     */
    @Override
    protected void resetCycleCounters(int counter) {
        nextZ80Cycle = Math.max(1, nextZ80Cycle - counter);
        next68kCycle = Math.max(1, next68kCycle - counter);
        nextVdpCycle = Math.max(1, nextVdpCycle - counter);
    }

    @Override
    protected void initAfterRomLoad() {
        super.initAfterRomLoad();
        bus.attachDevice(sound);
        vdp.addVdpEventListener(sound);
        resetAfterRomLoad();
        memView = createMemView();
    }

    @Override
    protected void resetAfterRomLoad() {
        super.resetAfterRomLoad();
        cpu.reset();
        z80.reset(); //TODO confirm this is needed
    }

    @Override
    protected void handleSoftReset() {
        if (softReset) {
            cpu.softReset();
        }
        super.handleSoftReset();
    }
}
