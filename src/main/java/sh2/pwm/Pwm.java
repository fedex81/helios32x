package sh2.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.util.Fifo;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;
import sh2.dict.S32xDict.RegSpecS32x;
import sh2.sh2.device.DmaC;
import sh2.sh2.device.IntControl;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.pwm.Pwm.PwmChannelSetup.OFF;
import static sh2.sh2.device.IntControl.Sh2Interrupt.PWM_6;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Pwm implements StepDevice {

    private static final Logger LOG = LogHelper.getLogger(Pwm.class.getSimpleName());

    enum PwmChannelSetup {
        OFF, SAME, FLIP, INVALID;

        public boolean isValid() {
            return this == SAME || this == FLIP;
        }
    }

    private static final int PWM_DMA_CHANNEL = 1;
    private static final int chLeft = 0, chRight = 1;
    private static final int PWM_FIFO_SIZE = 3;
    private static final int PWM_FIFO_FULL_BIT_POS = 15;
    private static final int PWM_FIFO_EMPTY_BIT_POS = 14;
    public static final int CYCLE_LIMIT = 400; //57khz @ 60hz
    public static final int CYCLE_22khz = 1042;


    private static final PwmChannelSetup[] chanVals = PwmChannelSetup.values();

    private ByteBuffer sysRegsMd, sysRegsSh2;
    private IntControl[] intControls;
    private DmaC[] dmac;

    private PwmChannelSetup[] channelMap = {OFF, OFF};
    private boolean pwmEnable, dreqEn;
    private int cycle = 0, interruptInterval;
    private int sh2TicksToNextPwmSample, sh2ticksToNextPwmInterrupt, sh2TicksToNext22khzSample = CYCLE_22khz;
    private int pwmSamplesPerFrame = 0, stepsPerFrame = 0, dreqPerFrame = 0;

    private Fifo<Integer> fifoLeft, fifoRight;
    private AtomicInteger latestPwmValueLeft = new AtomicInteger(0),
            latestPwmValueRight = new AtomicInteger(0);
    private static final boolean verbose = false;
    private PwmProvider playSupport = PwmProvider.NO_SOUND;

    public Pwm(S32XMMREG.RegContext regContext) {
        this.sysRegsMd = regContext.sysRegsMd;
        this.sysRegsSh2 = regContext.sysRegsSh2;
        this.fifoLeft = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        this.fifoRight = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        init();
    }

    public int read(CpuDeviceAccess cpu, RegSpecS32x regSpec, int address, Size size) {
        int res = readBuffer(sysRegsMd, address, size);
        assert res == readBuffer(sysRegsSh2, address, size);
        if (verbose) LOG.info("{} PWM read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }

    public void write(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        if (verbose) LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
        switch (size) {
            case BYTE:
                writeByte(cpu, regSpec, reg, value);
                break;
            case WORD:
                writeWord(cpu, regSpec, reg, value);
                break;
            case LONG:
                writeWord(cpu, regSpec, reg, value >> 16);
                writeWord(cpu, S32xDict.getRegSpec(cpu, regSpec.addr + 2), reg + 2, value & 0xFFFF);
                break;
        }
    }

    public void writeByte(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value) {
        switch (regSpec) {
            case PWM_CTRL:
                handlePwmControl(cpu, reg, value, Size.BYTE);
                break;
            case PWM_CYCLE: {
                assert cpu == Z80 || cpu == M68K : regSpec;
                handlePartialByteWrite(reg, value);
                if (regSpec == PWM_CYCLE) {
                    int val = readBuffer(sysRegsMd, regSpec.addr, Size.WORD);
                    handlePwmCycleWord(cpu, val);
                }
            }
            break;
            case PWM_RCH_PW:
            case PWM_LCH_PW:
            case PWM_MONO:
                //Mars check test1
                //NOTE: z80 writes MSB then LSB, we trigger a wordWrite when setting the LSB
                handlePartialByteWrite(reg, value);
                if ((reg & 1) == 1) {
                    int val = readBuffer(sysRegsMd, regSpec.addr, Size.WORD);
                    writeWord(cpu, regSpec, regSpec.addr, val);
                }
                break;
            default:
                LOG.error("{} PWM write {} {}: {} {}", cpu, regSpec.name, th(reg), th(value), Size.BYTE);
                break;
        }
    }

    private void handlePartialByteWrite(int reg, int value) {
        boolean even = (reg & 1) == 0;
        if (even) {
            writeBuffers(sysRegsMd, sysRegsSh2, reg, value & 0xF, Size.BYTE);
            return;
        }
        writeBuffers(sysRegsMd, sysRegsSh2, reg, value & 0xFF, Size.BYTE);
    }

    private void writeWord(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value) {
        switch (regSpec) {
            case PWM_CTRL -> handlePwmControl(cpu, reg, value, Size.WORD);
            case PWM_CYCLE -> handlePwmCycleWord(cpu, value);
            case PWM_MONO -> writeMono(value);
            case PWM_LCH_PW -> writeFifo(fifoLeft, value);
            case PWM_RCH_PW -> writeFifo(fifoRight, value);
            default -> writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, Size.WORD);
        }
    }

    private void handlePwmCycleWord(CpuDeviceAccess cpu, int value) {
        value &= 0xFFF;
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_CYCLE.addr, value, Size.WORD);
        int prevCycle = cycle;
        cycle = (value - 1) & 0xFFF;
        if (cycle < CYCLE_LIMIT) {
            LOG.warn("PWM cycle not supported: {}, limit: {}", cycle, CYCLE_LIMIT);
        }
        if (prevCycle != cycle) {
            handlePwmEnable(true);
        }
    }

    private void handlePwmControl(CpuDeviceAccess cpu, int reg, int value, Size size) {
        switch (cpu) {
            case M68K:
            case Z80:
                handlePwmControlMd(cpu, reg, value, size);
                break;
            default:
                handlePwmControlSh2(cpu, reg, value, size);
                break;
        }
        handlePwmEnable(false);
    }

    private void handlePwmControlMd(CpuDeviceAccess cpu, int reg, int value, Size size) {
        assert size != Size.LONG;
        if (size == Size.BYTE && ((reg & 1) == 0)) {
            LOG.warn("{} ignored write to {}: {} {}", cpu, PWM_CTRL, th(value), size);
            return;
        }
        int val = readBuffer(sysRegsMd, PWM_CTRL.addr, Size.WORD) & 0xFFF0;
        val |= value & 0xF;
        writeBuffers(sysRegsMd, sysRegsSh2, reg, val, size);

        value = readBuffer(sysRegsMd, PWM_CTRL.addr, Size.WORD);
        channelMap[chLeft] = chanVals[value & 3];
        channelMap[chRight] = chanVals[(value >> 2) & 3];
    }

    private void handlePwmControlSh2(CpuDeviceAccess cpu, int reg, int val, Size size) {
        int mask =
                switch (size) {
                    case WORD -> 0xF8F;
                    case BYTE -> (reg & 1) == 1 ? 0x8F : 0xF;
                    default -> 0;
                };
        //Primal Rage, Sh2 write bytes
        assert size != Size.LONG;
        writeBuffers(sysRegsMd, sysRegsSh2, reg, val & mask, size);
        int value = readBuffer(sysRegsMd, PWM_CTRL.addr, Size.WORD);
        dreqEn = ((value >> 7) & 1) > 0;
        int ival = (value >> 8) & 0xF;
        interruptInterval = ival == 0 ? 0x10 : ival;
        channelMap[chLeft] = chanVals[value & 3];
        channelMap[chRight] = chanVals[(value >> 2) & 3];
    }

    private void handlePwmEnable(boolean cycleChanged) {
        boolean wasEnabled = pwmEnable;
        pwmEnable = cycle > 0 && (channelMap[chLeft].isValid() || channelMap[chRight].isValid());
        if (!wasEnabled && pwmEnable || cycleChanged) {
            assert interruptInterval > 0;
            sh2TicksToNextPwmSample = cycle;
            sh2ticksToNextPwmInterrupt = interruptInterval;
//            latestPwmValue = cycle >> 1; //TODO check
            resetFifo();
            updateFifoRegs();
            playSupport.updatePwmCycle(cycle);
        }
    }

    private void resetFifo() {
        fifoRight.clear();
        fifoLeft.clear();
    }

    private void writeFifo(Fifo<Integer> fifo, int value) {
        if (fifo.isFull()) {
            fifo.pop();
            if (verbose) LOG.warn("PWM FIFO push when fifo full: {} {}", th(value));
            return;
        }
        fifo.push((value - 1) & 0xFFF);
        updateFifoRegs();
    }

    //TEST only
    public int readFifoMono(RegSpecS32x regSpec) {
        return switch (regSpec) {
            case PWM_LCH_PW -> readFifo(fifoLeft, latestPwmValueLeft);
            case PWM_RCH_PW -> readFifo(fifoRight, latestPwmValueRight);
            case PWM_MONO -> readMono();
            default -> {
                assert false : regSpec;
                yield 0;
            }
        };
    }

    private int readMono() {
        return (readFifo(fifoLeft, latestPwmValueLeft) + readFifo(fifoRight, latestPwmValueRight)) >> 1;
    }

    private int readFifo(Fifo<Integer> fifo, AtomicInteger latestPwmValue) {
        if (fifo.isEmpty()) {
            if (verbose) LOG.warn("PWM FIFO pop when fifo empty: {}", th(latestPwmValue.get()));
            return latestPwmValue.get();
        }
        int res = fifo.pop();
        latestPwmValue.set(res);
        updateFifoRegs();
        return res;
    }

    //TODO update on read instead??
    //TODO keep the existing value instead of replacing it
    private void updateFifoRegs() {
        int regValue = (fifoLeft.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoLeft.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, regValue, Size.WORD);
        regValue = (fifoRight.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoRight.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, regValue, Size.WORD);
        updateMono();
    }

    private void updateMono() {
        int fifoFull = ((fifoLeft.isFullBit() | fifoRight.isFullBit()) << PWM_FIFO_FULL_BIT_POS);
        int fifoEmpty = ((fifoLeft.isEmptyBit() & fifoRight.isEmptyBit()) << PWM_FIFO_EMPTY_BIT_POS);
        int regValue = fifoFull | fifoEmpty;
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, regValue, Size.WORD);
    }

    public void writeMono(int value) {
        writeFifo(fifoLeft, value);
        writeFifo(fifoRight, value);
    }

    public void newFrame() {
        if (verbose)
            LOG.info("Samples per frame: {}, stepsPerFrame: {}, dreqPerFrame: {}",
                    pwmSamplesPerFrame, stepsPerFrame, dreqPerFrame);
        pwmSamplesPerFrame = 0;
        stepsPerFrame = 0;
        dreqPerFrame = 0;
        playSupport.newFrame();
    }

    @Override
    public void step(int cycles) {
        if (verbose) stepsPerFrame += cycles;
        while (pwmEnable && cycles-- > 0) {
            stepOne();
        }
    }

    private int rs, ls;

    private void stepOne() {
        if (--sh2TicksToNextPwmSample == 0) {
            sh2TicksToNextPwmSample = cycle;
            pwmSamplesPerFrame++;
            ls = Math.min(cycle, readFifo(fifoLeft, latestPwmValueLeft));
            rs = Math.min(cycle, readFifo(fifoRight, latestPwmValueRight));
            if (--sh2ticksToNextPwmInterrupt == 0) {
                intControls[MASTER.ordinal()].setIntPending(PWM_6, true);
                intControls[SLAVE.ordinal()].setIntPending(PWM_6, true);
                sh2ticksToNextPwmInterrupt = interruptInterval;
                dreq();
            }
        }
        if (--sh2TicksToNext22khzSample == 0) {
            playSupport.playSample(ls, rs);
            sh2TicksToNext22khzSample = CYCLE_22khz;
        }
    }

    private void dreq() {
        if (dreqEn) {
            //NOTE this should trigger on channel one for BOTH sh2s
            dmac[MASTER.ordinal()].dmaReqTriggerPwm(PWM_DMA_CHANNEL, true);
            dmac[SLAVE.ordinal()].dmaReqTriggerPwm(PWM_DMA_CHANNEL, true);
            dreqPerFrame++;
        }
    }

    public void setIntControls(IntControl... intControls) {
        this.intControls = intControls;
    }

    public void setDmac(DmaC... dmac) {
        this.dmac = dmac;
    }

    public void setPwmProvider(PwmProvider p) {
        this.playSupport = p;
        playSupport.updatePwmCycle(cycle);
    }

    @Override
    public void init() {
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        handlePwmControlSh2(MASTER, PWM_CTRL.addr, 0, Size.WORD); //init interruptInterval
    }

    @Override
    public void reset() {
        init();
        playSupport.reset();
        LOG.info("PWM reset done");
    }
}
