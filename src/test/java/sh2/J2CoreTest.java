package sh2;

import omegadrive.util.FileUtil;
import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Impl;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;
import sh2.sh2.device.Sh2DeviceHelper;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.readBuffer;
import static sh2.S32xUtil.writeBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * Binary file generated, with modifications, from:
 * https://github.com/j-core/jcore-cpu/tree/master/testrom
 * <p>
 * License: see j2tests.lic
 * <p>
 */
public class J2CoreTest {

    static final int FAIL_VALUE = 0x8888_8888;
    final static int ramSize = 0x8000;
    static String binName = "j2tests.bin";
    static ByteBuffer rom;
    private static boolean done = false;
    private static boolean sh2Debug = false;

    public static Path baseDataFolder = Paths.get(new File(".").getAbsolutePath(),
            "src", "test", "resources");

    private Sh2 sh2;
    private Sh2Context ctx;

    @BeforeAll
    public static void beforeAll() {
        System.out.println(new File(".").getAbsolutePath());
        Path binPath = Paths.get(baseDataFolder.toAbsolutePath().toString(), "j2tests.bin");
        System.out.println("Bin file: " + binPath.toAbsolutePath());
        rom = ByteBuffer.wrap(FileUtil.loadBiosFile(binPath));
    }

    @BeforeEach
    public void before() {
        IMemory memory = getMemory(rom);
        sh2 = sh2Debug ? new Sh2Debug(memory) : new Sh2Impl(memory);
        ctx = createContext(S32xUtil.CpuDeviceAccess.MASTER, memory);
        sh2.reset(ctx);
        System.out.println("Reset, PC: " + ctx.PC + ", SP: " + ctx.registers[15]);
    }

    @Test
    public void testJ2() {
        do {
            sh2.run(ctx);
            checkFail(ctx);
        } while (!done);
        System.out.println("All tests done: success");
        System.out.println(ctx.toString());
    }

    public static Sh2Context createContext(S32xUtil.CpuDeviceAccess cpu, IMemory memory) {
        Sh2Cache cache = new Sh2CacheImpl(cpu, memory);
        Sh2MMREG sh2MMREG = new Sh2MMREG(cpu, cache);
        S32XMMREG s32XMMREG = new S32XMMREG();
        Sh2Context context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        context.debug = sh2Debug;
        context.devices = Sh2DeviceHelper.createDevices(cpu, memory, new DmaFifo68k(s32XMMREG.regContext), sh2MMREG);
        sh2MMREG.init(context.devices);
        Md32xRuntimeData.newInstance();
        return context;
    }

    protected static void checkDone(ByteBuffer ram, int addr) {
        char c1 = (char) ram.get(addr & ~1);
        char c2 = (char) ram.get((addr & ~1) + 1);
        if (c1 == 'O' && c2 == 'K') {
            done = true;
        }
    }

    protected static void checkFail(Sh2Context ctx) {
        if (ctx.registers[0] == FAIL_VALUE && ctx.registers[0] == ctx.registers[1]) {
            Assertions.fail(ctx.toString());
        }
    }


    public static IMemory getMemory(final ByteBuffer rom) {
        final int romSize = rom.capacity();
        final ByteBuffer ram = ByteBuffer.allocateDirect(ramSize);
        return new IMemory() {
            @Override
            public void write8i(int reg, byte val) {
                write(reg, val, Size.BYTE);
            }

            @Override
            public void write16i(int reg, int val) {
                write(reg, val, Size.WORD);
            }

            @Override
            public void write32i(int reg, int val) {
                write(reg, val, Size.LONG);
            }

            @Override
            public void write(int address, int value, Size size) {
                long lreg = address & 0xFFFF_FFFFL;
                if (lreg < romSize) {
                    writeBuffer(rom, (int) lreg, value, size);
                } else if (lreg < ramSize) {
                    writeBuffer(ram, (int) lreg, value, size);
                    checkDone(ram, address);
                } else if (lreg == 0xABCD0000L) {
                    System.out.println("Test success: " + th(value));
                } else {
                    System.out.println("write: " + th(address) + " " + th(value) + " " + size);
                }
            }

            @Override
            public int read(int address, Size size) {
                long laddr = address & 0xFFFF_FFFFL;
                if (laddr < romSize) {
                    return readBuffer(rom, (int) laddr, size);
                } else if (laddr < ramSize) {
                    return readBuffer(ram, (int) laddr, size);
                } else {
                    System.out.println("read32: " + th(address));
                }
                return (int) size.getMask();
            }

            @Override
            public int read8i(int addr) {
                return read(addr, Size.BYTE);
            }

            @Override
            public int read16i(int addr) {
                return read(addr, Size.WORD);
            }

            @Override
            public int read32i(int addr) {
                return read(addr, Size.LONG);
            }

            @Override
            public void prefetch(int pc, S32xUtil.CpuDeviceAccess cpu) {

            }

            @Override
            public void resetSh2() {
            }
        };
    }
}
