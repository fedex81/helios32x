package sh2.sh2.device;

import sh2.DmaFifo68k;
import sh2.IMemory;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.Sh2Launcher.Sh2LaunchContext;
import sh2.Sh2MMREG;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2DeviceHelper {

    public enum Sh2DeviceType {NONE, UBC, FRT, BSC, DMA, INTC, DIV, SCI, WDT}

    public static class Sh2DeviceContext {
        public CpuDeviceAccess cpu;
        public IntControl intC;
        public DmaC dmaC;
        public SerialCommInterface sci;
        public DivUnit divUnit;
        public WatchdogTimer wdt;
        public Sh2MMREG sh2MMREG;
    }

    public static Sh2DeviceContext createDevices(CpuDeviceAccess cpu, Sh2LaunchContext ctx) {
        return createDevices(cpu, ctx.memory, ctx.dmaFifo68k, ctx.memory.getSh2MMREGS(cpu));
    }

    private static Sh2DeviceContext createDevices(CpuDeviceAccess cpu, IMemory memory,
                                                  DmaFifo68k dmaFifo68k, Sh2MMREG sh2Regs) {
        Sh2DeviceContext ctx = new Sh2DeviceContext();
        ctx.cpu = cpu;
        ctx.sh2MMREG = sh2Regs;
        ctx.intC = new IntControl(cpu, sh2Regs.getRegs());
        ctx.dmaC = new DmaC(cpu, ctx.intC, memory, dmaFifo68k, sh2Regs.getRegs());
        ctx.sci = new SerialCommInterface(cpu, sh2Regs.getRegs());
        ctx.divUnit = new DivUnit(cpu, sh2Regs.getRegs());
        ctx.wdt = new WatchdogTimer(cpu, ctx.intC, sh2Regs.getRegs());
        return ctx;
    }
}
