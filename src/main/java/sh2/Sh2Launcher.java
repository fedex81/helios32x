package sh2;

import memory.IMemory;
import omegadrive.SystemLoader;
import omegadrive.util.FileLoader;
import sh4.Sh4Context;
import sh4.Sh4Disassembler;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import static sh2.Sh2Emu.Sh2Access.MASTER;
import static sh2.Sh2Emu.Sh2Access.SLAVE;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Launcher {

    static final long sh2_clock_speed = 26_000_000; //26Mhz
    static final long clocksPerFrame = sh2_clock_speed / 60; //assuming 60fps
    static final int burstCycles = 1;

    static String biosBasePath = "res/bios/";
    static String masterBiosName = "32x_bios_m.bin";
    static String slaveBiosName = "32x_bios_s.bin";
    static String mdBiosName = "32x_bios_g.bin";

//    static String masterBiosName = "32x_hbrew_bios_m.bin";
//    static String slaveBiosName = "32x_hbrew_bios_s.bin";
//    static String mdBiosName = "32x_hbrew_bios_g.bin";

    static String romName = "res/roms/test13.bin";

    private static IMemory memory;

    static {
        System.setProperty("helios.headless", "true");
        System.setProperty("helios.fullSpeed", "false");
        System.setProperty("helios.enable.sound", "false");
        System.setProperty("68k.debug", "false");
    }

    public static void main(String[] args) {
        launch32x();
    }

    private static void launch32x() {
        Path biosMasterPath = Paths.get(biosBasePath,  masterBiosName);
        Path biosSlavePath = Paths.get(biosBasePath, slaveBiosName);
        Path biosM68kPath = Paths.get(biosBasePath, mdBiosName);
        Path romPath = Paths.get(romName);

        ByteBuffer bm = ByteBuffer.wrap(FileLoader.readFileSafe(biosMasterPath));
        ByteBuffer bs = ByteBuffer.wrap(FileLoader.readFileSafe(biosSlavePath));
        ByteBuffer mb = ByteBuffer.wrap(FileLoader.readFileSafe(biosM68kPath));


        ByteBuffer rom = ByteBuffer.wrap(FileLoader.readFileSafe(romPath));
        memory = new Sh2Memory(rom);

        MdBus.setRom(rom);
        ((Sh2Memory) memory).bios[MASTER.ordinal()] = bm;
        ((Sh2Memory) memory).bios[SLAVE.ordinal()] = bs;
        MdBus.bios = mb;
        run();
    }

    static void run() {
        Sh4Context.burstCycles = burstCycles;

        long secondsElapsed = 0;
        Sh2Emu master = new Sh2Emu(MASTER, memory);
        Sh2Emu slave = new Sh2Emu(SLAVE, memory);
        launchMdNew(master, slave);
    }

    static void launchMdNew(Sh2Emu master, Sh2Emu slave) {
        Path p = Paths.get(romName);
        Md32x sp = (Md32x) SystemLoader.getInstance().handleNewRomFile(p);
        sp.master = master;
        sp.slave = slave;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
