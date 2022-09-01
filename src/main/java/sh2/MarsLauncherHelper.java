package sh2;

import omegadrive.util.LogHelper;
import omegadrive.util.RomHolder;
import omegadrive.util.Util;
import org.slf4j.Logger;
import sh2.pwm.Pwm;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Impl;
import sh2.sh2.device.Sh2DeviceHelper;
import sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceContext;
import sh2.sh2.prefetch.Sh2Prefetch;
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
public class MarsLauncherHelper {

    private static final Logger LOG = LogHelper.getLogger(MarsLauncherHelper.class.getSimpleName());

    static final boolean masterDebug = Boolean.parseBoolean(System.getProperty("sh2.master.debug", "false"));
    static final boolean slaveDebug = Boolean.parseBoolean(System.getProperty("sh2.slave.debug", "false"));
    static final boolean homebrewBios = Boolean.parseBoolean(System.getProperty("32x.use.homebrew.bios", "false"));

    static String biosBasePath = "res/bios/";

    static String masterBiosName = "32x_bios_m.bin";
    static String slaveBiosName = "32x_bios_s.bin";
    static String mdBiosName = "32x_bios_g.bin";
    static String hb_masterBiosName = "32x_hbrew_bios_m.bin";
    static String hb_slaveBiosName = "32x_hbrew_bios_s.bin";
    static String hb_mdBiosName = "32x_hbrew_bios_g.bin";


    public static BiosHolder initBios() {
        if (homebrewBios) {
            LOG.warn("Using homebrew bioses: {}, {}, {}", hb_masterBiosName, hb_slaveBiosName, hb_mdBiosName);
            masterBiosName = hb_masterBiosName;
            slaveBiosName = hb_slaveBiosName;
            mdBiosName = hb_mdBiosName;
        }
        Path biosMasterPath = Paths.get(biosBasePath, masterBiosName);
        Path biosSlavePath = Paths.get(biosBasePath, slaveBiosName);
        Path biosM68kPath = Paths.get(biosBasePath, mdBiosName);
        BiosHolder biosHolder = new BiosHolder(biosMasterPath, biosSlavePath, biosM68kPath);
        return biosHolder;
    }

    public static Sh2LaunchContext setupRom(S32xBus bus, RomHolder romHolder) {
        return setupRom(bus, romHolder, initBios());
    }

    public static Sh2LaunchContext setupRom(S32xBus bus, RomHolder romHolder, BiosHolder biosHolder) {
        Sh2LaunchContext ctx = new Sh2LaunchContext();
        ctx.masterCtx = new Sh2Context(MASTER, masterDebug);
        ctx.slaveCtx = new Sh2Context(SLAVE, slaveDebug);
        ctx.biosHolder = biosHolder;
        ctx.bus = bus;
        ctx.rom = ByteBuffer.wrap(Util.unsignedToByteArray(romHolder.data));
        ctx.s32XMMREG = new S32XMMREG();
        ctx.dmaFifo68k = new DmaFifo68k(ctx.s32XMMREG.regContext);
        Sh2Prefetch.Sh2DrcContext mDrcCtx = new Sh2Prefetch.Sh2DrcContext();
        Sh2Prefetch.Sh2DrcContext sDrcCtx = new Sh2Prefetch.Sh2DrcContext();
        mDrcCtx.sh2Ctx = ctx.masterCtx;
        sDrcCtx.sh2Ctx = ctx.slaveCtx;
        mDrcCtx.cpu = ctx.masterCtx.cpuAccess;
        sDrcCtx.cpu = ctx.slaveCtx.cpuAccess;

        ctx.memory = new Sh2Memory(ctx.s32XMMREG, ctx.rom, biosHolder, mDrcCtx, sDrcCtx);
        ctx.mDevCtx = Sh2DeviceHelper.createDevices(MASTER, ctx);
        ctx.sDevCtx = Sh2DeviceHelper.createDevices(SLAVE, ctx);
        ctx.sh2 = (ctx.masterCtx.debug || ctx.slaveCtx.debug) ?
                new Sh2Debug(ctx.memory) : new Sh2Impl(ctx.memory);
        mDrcCtx.sh2 = sDrcCtx.sh2 = (Sh2Impl) ctx.sh2;
        mDrcCtx.memory = sDrcCtx.memory = ctx.memory;
        ctx.pwm = new Pwm(ctx.s32XMMREG.regContext);
        ctx.masterCtx.devices = ctx.mDevCtx;
        ctx.slaveCtx.devices = ctx.sDevCtx;
        ctx.initContext();
        return ctx;
    }

    public static class Sh2LaunchContext {
        public Sh2Context masterCtx, slaveCtx;
        public Sh2DeviceContext mDevCtx, sDevCtx;
        public S32xBus bus;
        public BiosHolder biosHolder;
        public Sh2Memory memory;
        public Sh2 sh2;
        public DmaFifo68k dmaFifo68k;
        public S32XMMREG s32XMMREG;
        public ByteBuffer rom;
        public MarsVdp marsVdp;
        public Pwm pwm;

        public void initContext() {
            bus.attachDevice(sh2).attachDevice(s32XMMREG);
            memory.getSh2MMREGS(MASTER).init(mDevCtx);
            memory.getSh2MMREGS(SLAVE).init(sDevCtx);
            s32XMMREG.setInterruptControl(mDevCtx.intC, sDevCtx.intC);
            s32XMMREG.setDmaControl(dmaFifo68k);
            s32XMMREG.setPwm(pwm);
            s32XMMREG.setBus(bus);
            pwm.setIntControls(mDevCtx.intC, sDevCtx.intC);
            pwm.setDmac(mDevCtx.dmaC, sDevCtx.dmaC);
            dmaFifo68k.setDmac(mDevCtx.dmaC, sDevCtx.dmaC);
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
