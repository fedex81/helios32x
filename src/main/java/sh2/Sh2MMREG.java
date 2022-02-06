package sh2;

import com.google.common.collect.Maps;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.device.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.stream.IntStream;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2MMREG {

    private static final Logger LOG = LogManager.getLogger(Sh2MMREG.class.getSimpleName());

    public static final int DATA_ARRAY_SIZE = 0x1000;
    public static final int DATA_ARRAY_MASK = DATA_ARRAY_SIZE - 1;
    public static final int SH2_REG_SIZE = 0x200;
    public static final int SH2_REG_MASK = SH2_REG_SIZE - 1;

    private ByteBuffer regs = ByteBuffer.allocateDirect(SH2_REG_SIZE);
    private ByteBuffer data_array = ByteBuffer.allocateDirect(DATA_ARRAY_SIZE); // cache (can be used as RAM)

    private final Map<Integer, Integer> dramModeRegs = Maps.newHashMap(dramModeRegsSpec);

    private SerialCommInterface sci;
    private DivUnit divUnit;
    private DmaC dmaC;
    public IntControl intC;
    private WatchdogTimer wdt;

    private CpuDeviceAccess cpu;
    private int ticksPerFrame, sh2TicksPerFrame;
    private static final boolean verbose = false;

    //TODO
    public static CacheContext[] cacheCtx = new CacheContext[2];


    public Sh2MMREG(CpuDeviceAccess sh2Access) {
        this.cpu = sh2Access;
        cacheCtx[cpu.ordinal()] = new CacheContext();
    }

    public void init(Sh2DeviceHelper.Sh2DeviceContext ctx) {
        this.dmaC = ctx.dmaC;
        this.divUnit = ctx.divUnit;
        this.sci = ctx.sci;
        this.intC = ctx.intC;
        this.wdt = ctx.wdt;
        reset();
    }

    public void writeCache(int address, int value, Size size) {
        writeBuffer(data_array, address & DATA_ARRAY_MASK, value, size);
    }

    public int readCache(int address, Size size) {
        return readBuffer(data_array, address & DATA_ARRAY_MASK, size);
    }

    public void write(int reg, int value, Size size) {
        if (verbose) {
            logAccess("write", reg, value, size);
        }
        checkName(reg);
        regWrite(reg, value, size);
    }

    private void regWrite(int reg, int value, Size size) {
        RegSpec regSpec = sh2RegMapping[reg & SH2_REG_MASK];
        if (regSpec == null) {
            LOG.error("{} unknown reg write {}: {} {}", cpu, th(reg), th(value), size);
            //VF writes a LONG to 0xFFFF_FFFF
            tryWriteBuffer(reg, value, size);
            return;
        }
        switch (sh2RegDeviceMapping[reg & SH2_REG_MASK]) {
            case DIV:
                divUnit.write(regSpec, value, size);
                break;
            case DMA:
                dmaC.write(regSpec, value, size);
                break;
            case SCI:
                sci.write(regSpec, value, size);
                break;
            case INTC:
                intC.write(regSpec, value, size);
                break;
            case WDT:
                wdt.write(regSpec, value, size);
                break;
            case FRT:
            case BSC:
            case UBC:
            case NONE:
            default:
                //logAccess("write", reg, value, size);
                if (regSpec == RegSpec.NONE_CCR) {
                    int prev = readBuffer(regs, reg & SH2_REG_MASK, size);
                    if (prev != value) {
                        CacheContext ctx = cacheCtx[cpu.ordinal()];
                        ctx.way = value >> 6;
                        ctx.cachePurge = (value >> 4) & 1;
                        ctx.twoWay = (value >> 3) & 1;
                        ctx.dataReplaceDis = (value >> 2) & 1;
                        ctx.instReplaceDis = (value >> 1) & 1;
                        ctx.cacheEn = value & 1;
//                        ctx.logInfoString(cpu);
                        if (ctx.cachePurge > 0) {
//                            LOG.info("{} trigger cache purge", cpu);
                            ctx.cachePurge = 0; //always reverts to 0
                        }
                    }
                }
                writeBuffer(regs, reg & SH2_REG_MASK, value, size);
                break;
        }
    }

    public int readDramMode(int reg, Size size) {
        int res = dramModeRegs.getOrDefault(reg, -1);
        if (verbose) {
            logAccess("read", reg, res, size);
        }
        if (res < 0) {
            LOG.error("Unexpected dram mode reg read: {} {}", reg, size);
            res = 0;
        }
        return res;
    }

    public void writeDramMode(int reg, int value, Size size) {
        if (verbose) {
            logAccess("write", reg, value, size);
        }
        if (dramModeRegs.containsKey(reg)) {
            dramModeRegs.put(reg, value);
        } else {
            LOG.error("Unexpected dram mode reg write: {}, {} {}", reg, value, size);
        }
    }

    public int read(int reg, Size size) {
        checkName(reg);
        RegSpec regSpec = sh2RegMapping[reg & SH2_REG_MASK];
        int res = 0;
        if (regSpec != null) {
            switch (sh2RegDeviceMapping[reg & SH2_REG_MASK]) {
                case WDT:
                    res = wdt.read(regSpec, reg & SH2_REG_MASK, size);
                    break;
                case SCI:
                    res = sci.read(regSpec, size);
                    break;
                default:
                    res = readBuffer(regs, reg & SH2_REG_MASK, size);
                    break;
            }
        }
        if (verbose) {
            logAccess("read", reg, res, size);
        }
        return res;
    }

    public ByteBuffer getRegs() {
        return regs;
    }

    private void tryWriteBuffer(int reg, int value, Size size) {
        try {
            writeBuffer(regs, reg & SH2_REG_MASK, value, size);
        } catch (Exception e) {
            //do nothing
        }
    }

    public void newFrame() {
        if (verbose)
            LOG.info("{} DMA/SCI ticks per frame: {}, sh2 tpf: {}",
                    cpu, ticksPerFrame, sh2TicksPerFrame);
        ticksPerFrame = sh2TicksPerFrame = 0;
    }

    static class CacheContext {
        public int way;
        public int cachePurge;
        public int twoWay;
        public int dataReplaceDis;
        public int instReplaceDis;
        public int cacheEn;

        public void logInfoString(CpuDeviceAccess cpu) {
            LOG.info("{} CCR write: cacheEn {}, instReplaceDis {}, dataReplaceDis {}, {}, cachePurge {}, " +
                            "way{}",
                    cpu, cacheEn, instReplaceDis, dataReplaceDis, twoWay > 0 ? "twoWay" : "fourWay", cachePurge, way);
        }
    }

    public void reset() {
        //from picodrive
        IntStream.range(0, regs.capacity()).forEach(i -> regs.put(i, (byte) 0));
        sci.reset();
        divUnit.reset();
        wdt.reset();
        writeBuffer(regs, RegSpec.FRT_TIER.addr, 0x11, Size.BYTE);
        writeBuffer(regs, RegSpec.FRT_TOCR.addr, 0x17, Size.BYTE);
    }

    public void deviceStep() {
        dmaC.step(0);
        sci.step(0);
        ticksPerFrame++;
    }

    //23 Mhz
    public void deviceStepSh2Rate(int cycles) {
        wdt.step(cycles);
        sh2TicksPerFrame += cycles;
    }
}
