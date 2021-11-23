package sh2;

import omegadrive.SystemLoader;
import omegadrive.util.FileLoader;
import omegadrive.util.Util;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import static sh2.Sh2Util.Sh2Access.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Launcher {

    static final int burstCycles = 1;

    static String biosBasePath = "res/bios/";
    static String masterBiosName = "32x_bios_m.bin";
    static String slaveBiosName = "32x_bios_s.bin";
    static String mdBiosName = "32x_bios_g.bin";

//    static String masterBiosName = "32x_hbrew_bios_m.bin";
//    static String slaveBiosName = "32x_hbrew_bios_s.bin";
//    static String mdBiosName = "32x_hbrew_bios_g.bin";

    /**
     * test2 - master: waiting for INT
     * slave: looping on BF
     */
    static String romName = "res/roms/test.bin";

    private static void initProps() {
        System.setProperty("helios.headless", "true");
        System.setProperty("helios.fullSpeed", "false");
        System.setProperty("helios.enable.sound", "false");
        System.setProperty("68k.debug", "false");
        System.setProperty("32x.show.vdp.debug.viewer", "true");
    }

    public static void main(String[] args) {
        initProps();
        launch32x();
    }

    private static void launch32x() {
        Path romPath = Paths.get(romName);
        Sh2LaunchContext ctx = setupRom(romPath);
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

    public static Sh2LaunchContext setupRom(Path romFile) {
        Sh2LaunchContext ctx = new Sh2LaunchContext();
        ctx.biosHolder = initBios();
        ByteBuffer rom = ByteBuffer.wrap(FileLoader.readFileSafe(romFile));
        ctx.memory = new Sh2Memory(rom);

        MdBus.setRom(rom);
        ctx.memory.bios[MASTER.ordinal()] = ctx.biosHolder.getBiosData(MASTER);
        ctx.memory.bios[SLAVE.ordinal()] = ctx.biosHolder.getBiosData(SLAVE);
        MdBus.bios = ctx.biosHolder.getBiosData(M68K);

        ctx.sh2 = new Sh2(ctx.memory);
        return ctx;
    }

    static class Sh2LaunchContext {
        public BiosHolder biosHolder;
        public Sh2Memory memory;
        public Sh2 sh2;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
