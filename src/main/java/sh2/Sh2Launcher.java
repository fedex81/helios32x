package sh2;

import omegadrive.SystemLoader;
import omegadrive.util.FileLoader;
import omegadrive.util.Util;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.device.DmaC;
import sh2.sh2.device.IntC;
import sh2.vdp.MarsVdp;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import static sh2.S32xUtil.CpuDeviceAccess.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Launcher {

    static String biosBasePath = "res/bios/";
    static String masterBiosName = "32x_bios_m.bin";
    static String slaveBiosName = "32x_bios_s.bin";
    static String mdBiosName = "32x_bios_g.bin";

//    static String masterBiosName = "32x_hbrew_bios_m.bin";
//    static String slaveBiosName = "32x_hbrew_bios_s.bin";
//    static String mdBiosName = "32x_hbrew_bios_g.bin";

    /**
     * rom06: DVDNT 32/32 div
     * testc2: SCI and DMA
     * rom36: check div
     * <p>
     * testc1, manual test
     * if(currentPC == 0x00880982){
     * m68k.setPC(0x00880b32);
     * }
     */
    static String romName = "res/roms/testc1.32x";

    private static void initProps() {
        System.setProperty("helios.headless", "false");
        System.setProperty("helios.fullSpeed", "false");
        System.setProperty("helios.enable.sound", "false");
        System.setProperty("68k.debug", "true");
        System.setProperty("32x.show.vdp.debug.viewer", "false");
    }

    public static void main(String[] args) {
        initProps();
        launch32x();
    }

    private static void launch32x() {
        Path romPath = Paths.get(romName);
        Md32x sp = (Md32x) SystemLoader.getInstance().handleNewRomFile(romPath);
        Util.waitForever();
    }

    public static BiosHolder initBios() {
        Path biosMasterPath = Paths.get(biosBasePath, masterBiosName);
        Path biosSlavePath = Paths.get(biosBasePath, slaveBiosName);
        Path biosM68kPath = Paths.get(biosBasePath, mdBiosName);
        BiosHolder biosHolder = new BiosHolder(biosMasterPath, biosSlavePath, biosM68kPath);
        return biosHolder;
    }

    public static Sh2LaunchContext setupRom(S32xBus bus, Path romFile) {
        Sh2LaunchContext ctx = new Sh2LaunchContext();
        ctx.masterCtx = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        ctx.slaveCtx = new Sh2Context(SLAVE);
        ctx.biosHolder = initBios();
        ctx.bus = bus;
        ctx.rom = ByteBuffer.wrap(FileLoader.readBinaryFile(romFile, ".32x"));
        ctx.s32XMMREG = new S32XMMREG();
        ctx.memory = new Sh2Memory(ctx.s32XMMREG, ctx.rom);
        ctx.memory.bios[MASTER.ordinal()] = ctx.biosHolder.getBiosData(MASTER);
        ctx.memory.bios[SLAVE.ordinal()] = ctx.biosHolder.getBiosData(SLAVE);
        ctx.intc = new IntC();
        ctx.dmac = new DmaC(ctx.bus, ctx.s32XMMREG);
        ctx.sh2 = (ctx.masterCtx.debug || ctx.slaveCtx.debug) ?
                new Sh2(ctx.memory, ctx.intc) : new Sh2Debug(ctx.memory, ctx.intc);
        ctx.initContext();
        return ctx;
    }

    static class Sh2LaunchContext {
        public Sh2Context masterCtx, slaveCtx;
        public S32xBus bus;
        public BiosHolder biosHolder;
        public Sh2Memory memory;
        public Sh2 sh2;
        public IntC intc;
        public DmaC dmac;
        public S32XMMREG s32XMMREG;
        public ByteBuffer rom;
        public MarsVdp marsVdp;

        public void initContext() {
            bus.attachDevice(sh2).attachDevice(s32XMMREG);
            s32XMMREG.setInterruptControl(intc);
            s32XMMREG.setDmaControl(dmac);
            bus.setBios68k(biosHolder.getBiosData(M68K));
            bus.setRom(rom);
            bus.masterCtx = masterCtx;
            bus.slaveCtx = slaveCtx;
            sh2.reset(masterCtx);
            sh2.reset(slaveCtx);
            marsVdp = bus.getMarsVdp();
        }
    }
}
