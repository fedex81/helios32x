package sh2;

import omegadrive.SystemLoader;
import omegadrive.util.Util;

import java.nio.file.Path;
import java.nio.file.Paths;

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

    static String romName = "res/roms/test2.32x";

    private static void initProps() {
        System.setProperty("helios.headless", "false");
        System.setProperty("helios.fullSpeed", "false");
        System.setProperty("helios.enable.sound", "false");
        System.setProperty("68k.debug", "false");
        System.setProperty("md.show.vdp.debug.viewer", "false");
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
}