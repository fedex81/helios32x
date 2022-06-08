package sh2;

import com.google.common.collect.Maps;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.sh2.cache.Sh2Cache;
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
    public static final int SH2_REG_SIZE = 0x200;
    public static final int SH2_REG_MASK = SH2_REG_SIZE - 1;

    private ByteBuffer regs = ByteBuffer.allocateDirect(SH2_REG_SIZE);
    private final Map<Integer, Integer> dramModeRegs = Maps.newHashMap(dramModeRegsSpec);

    private SerialCommInterface sci;
    private DivUnit divUnit;
    private DmaC dmaC;
    public IntControl intC;
    private WatchdogTimer wdt;
    private FreeRunningTimer frt;
    private Sh2Cache cache;

    private CpuDeviceAccess cpu;
    private int ticksPerFrame, sh2TicksPerFrame;
    private static final boolean verbose = false;

    public Sh2MMREG(CpuDeviceAccess sh2Access, Sh2Cache sh2Cache) {
        this.cpu = sh2Access;
        this.cache = sh2Cache;
    }

    public void init(Sh2DeviceHelper.Sh2DeviceContext ctx) {
        this.dmaC = ctx.dmaC;
        this.divUnit = ctx.divUnit;
        this.sci = ctx.sci;
        this.intC = ctx.intC;
        this.wdt = ctx.wdt;
        this.frt = ctx.frt;
        reset();
    }

    public void write(int reg, int value, Size size) {
        if (verbose) {
            logAccess("write", reg, value, size);
        }
        checkName(reg);
        regWrite(reg, value, size);
    }

    private void regWrite(int reg, int value, Size size) {
        final int pos = reg & SH2_REG_MASK;
        RegSpec regSpec = sh2RegMapping[pos];
        if (regSpec == null) {
            LOG.error("{} unknown reg write {}: {} {}", cpu, th(reg), th(value), size);
            tryWriteBuffer(reg, value, size);
            return;
        }
        switch (sh2RegDeviceMapping[pos]) {
            case DIV:
                divUnit.write(regSpec, pos, value, size);
                break;
            case DMA:
                dmaC.write(regSpec, pos, value, size);
                break;
            case SCI:
                sci.write(regSpec, pos, value, size);
                break;
            case INTC:
                intC.write(regSpec, pos, value, size);
                break;
            case WDT:
                wdt.write(regSpec, pos, value, size);
                break;
            case FRT:
                frt.write(regSpec, pos, value, size);
                break;
            case BSC:
                handleWriteBSC(regSpec, pos, value, size);
                return;
            case UBC:
            case NONE:
            default:
                //logAccess("write", reg, value, size);
                if (regSpec == RegSpec.NONE_CCR) {
                    value = handleWriteCCR(regSpec, pos, value, size);
                }
                writeBuffer(regs, pos, value, size);
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
        final int pos = reg & SH2_REG_MASK;
        RegSpec regSpec = sh2RegMapping[pos];
        int res = 0;
        if (regSpec != null) {
            switch (sh2RegDeviceMapping[reg & SH2_REG_MASK]) {
                case WDT:
                    res = wdt.read(regSpec, pos, size);
                    break;
                case SCI:
                    res = sci.read(regSpec, pos, size);
                    break;
                case DIV:
                    res = divUnit.read(regSpec, pos, size);
                    break;
                case FRT:
                    res = frt.read(regSpec, pos, size);
                    break;
                case BSC:
                    assert size != Size.BYTE;
                    res = readBuffer(regs, pos, size);
                    LOG.info("{} BSC reg {} read: {} {}", cpu, regSpec, th(res), size);
                    break;
                default:
                    res = readBuffer(regs, pos, size);
                    break;
            }
        }
        if (verbose) {
            logAccess("read", reg, res, size);
        }
        return res;
    }

    private void handleWriteBSC(RegSpec regSpec, int value, Size size) {
        handleWriteBSC(regSpec, regSpec.addr, value, size);
    }

    private void handleWriteBSC(RegSpec regSpec, int pos, int value, Size size) {
        assert pos == regSpec.addr : th(pos) + ", " + th(regSpec.addr);
        if (verbose) LOG.info("{} BSC reg {} write: {} {}", cpu, regSpec, th(value), size);
        if (size != Size.LONG || (value & 0xFFFF_0000) != BSC_LONG_WRITE_MASK) {
            LOG.error("Invalid BSC reg {} write: {} {}", cpu, regSpec, th(value), size);
            return;
        }
        value &= 0xFFFF;
        if (regSpec == RegSpec.BSC_BCR1) {
            value |= (cpu.ordinal() & 1) << 15;
        }
        writeRegBuffer(regSpec, regs, value, size);
    }

    private int handleWriteCCR(RegSpec r, int pos, int v, Size size) {
        assert pos == r.addr : th(pos) + ", " + th(r.addr);
        int prev = readBuffer(regs, r.addr, size);
        if (prev != v) {
            Sh2Cache.CacheContext ctx = cache.updateState(v);
            //purge always reverts to 0
            v = ctx.ccr;
        }
        return v;
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

    public void reset() {
        //from picodrive
        IntStream.range(0, regs.capacity()).forEach(i -> regs.put(i, (byte) 0));
        sci.reset();
        divUnit.reset();
        wdt.reset();
        frt.reset();
        dmaC.reset();
        intC.reset();
        writeBuffer(regs, RegSpec.FRT_TIER.addr, 0x1, Size.BYTE);
        writeBuffer(regs, RegSpec.FRT_TOCR.addr, 0xE0, Size.BYTE);
        writeBuffer(regs, RegSpec.FRT_OCRAB_H.addr, 0xFF, Size.BYTE);
        writeBuffer(regs, RegSpec.FRT_OCRAB_L.addr, 0xFF, Size.BYTE);
        handleWriteBSC(RegSpec.BSC_BCR1, BSC_LONG_WRITE_MASK | 0x3f0, Size.LONG);
        handleWriteBSC(RegSpec.BSC_BCR2, BSC_LONG_WRITE_MASK | 0xFC, Size.LONG);
        handleWriteBSC(RegSpec.BSC_WCR, BSC_LONG_WRITE_MASK | 0xAAFF, Size.LONG);
    }

    public void deviceStep() {
        dmaC.step(0);
        sci.step(0);
        if (verbose) ticksPerFrame++;
    }

    //23 Mhz
    public void deviceStepSh2Rate(int cycles) {
        wdt.step(cycles);
        if (FreeRunningTimer.SH2_ENABLE_FRT) {
            frt.step(cycles);
        }
        if (verbose) sh2TicksPerFrame += cycles;
    }
}
