package s32x.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.util.Fifo;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.slf4j.Logger;
import s32x.S32XMMREG;
import s32x.dict.S32xDict;
import s32x.sh2.device.DmaC;
import s32x.sh2.device.IntControl;
import s32x.util.S32xUtil;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static s32x.pwm.Pwm.PwmChannel.LEFT;
import static s32x.pwm.Pwm.PwmChannel.RIGHT;
import static s32x.pwm.Pwm.PwmChannelSetup.OFF;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class Pwm implements S32xUtil.StepDevice {

    private static final Logger LOG = LogHelper.getLogger(Pwm.class.getSimpleName());

    enum PwmChannelSetup {
        OFF, SAME, FLIP, INVALID;

        public boolean isValid() {
            return this == SAME || this == FLIP;
        }
    }

    enum PwmChannel {LEFT, RIGHT}

    private static final int PWM_DMA_CHANNEL = 1;
    private static final int chLeft = 0, chRight = 1;
    private static final int PWM_FIFO_SIZE = 3;
    private static final int PWM_FIFO_FULL_BIT_POS = 15;
    private static final int PWM_FIFO_EMPTY_BIT_POS = 14;
    public static final int CYCLE_LIMIT = 400; //57khz @ 60hz
    public static final int CYCLE_22khz = 1042;

    //used to clamp samples [0+sld, cycle-sld]
    public static final int SAMPLE_LIMIT_DELTA = 5;


    private static final PwmChannelSetup[] chanVals = PwmChannelSetup.values();

    private final ByteBuffer sysRegsMd;
    private final ByteBuffer sysRegsSh2;
    private IntControl[] intControls;
    private DmaC[] dmac;

    private final PwmChannelSetup[] channelMap = {OFF, OFF};
    private boolean pwmEnable, dreqEn;
    private int cycle = 0, interruptInterval;
    private int sh2TicksToNextPwmSample, sh2ticksToNextPwmInterrupt, sh2TicksToNext22khzSample = CYCLE_22khz;
    private int pwmSamplesPerFrame = 0, stepsPerFrame = 0, dreqPerFrame = 0;

    private final Fifo<Integer> fifoLeft;
    private final Fifo<Integer> fifoRight;

    private final int[] latestPwmValue = new int[PwmChannel.values().length];
    private static final boolean verbose = false;
    private PwmProvider playSupport = PwmProvider.NO_SOUND;

    public Pwm(S32XMMREG.RegContext regContext) {
        this.sysRegsMd = regContext.sysRegsMd;
        this.sysRegsSh2 = regContext.sysRegsSh2;
        this.fifoLeft = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        this.fifoRight = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        init();
    }

    public int read(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int address, Size size) {
        int res = S32xUtil.readBuffer(sysRegsMd, address, size);
        assert res == S32xUtil.readBuffer(sysRegsSh2, address, size);
        if (verbose) LOG.info("{} PWM read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }

    public void write(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        if (verbose) LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
        switch (size) {
            case BYTE -> writeByte(cpu, regSpec, reg, value);
            case WORD -> writeWord(cpu, regSpec, reg, value);
            case LONG -> {
                writeWord(cpu, regSpec, reg, (value >> 16) & 0xFFFF);
                writeWord(cpu, S32xDict.getRegSpec(cpu, regSpec.addr + 2), reg + 2, value & 0xFFFF);
            }
        }
    }

    public void writeByte(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value) {
        switch (regSpec) {
            case PWM_CTRL -> handlePwmControl(cpu, reg, value, Size.BYTE);
            case PWM_CYCLE -> {
                assert cpu.regSide == S32xUtil.S32xRegSide.MD : regSpec;
                handlePartialByteWrite(reg, value);
                if (regSpec == S32xDict.RegSpecS32x.PWM_CYCLE) {
                    int val = Util.readBufferWord(sysRegsMd, regSpec.addr);
                    handlePwmCycleWord(cpu, val);
                }
            }
            case PWM_RCH_PW, PWM_LCH_PW, PWM_MONO -> {
                //Mars check test1
                //NOTE: z80 writes MSB then LSB, we trigger a wordWrite when setting the LSB
                handlePartialByteWrite(reg, value);
                if ((reg & 1) == 1) {
                    int val = Util.readBufferWord(sysRegsMd, regSpec.addr);
                    writeWord(cpu, regSpec, regSpec.addr, val);
                }
            }
            default -> LOG.error("{} PWM write {} {}: {} {}", cpu, regSpec.name, th(reg), th(value), Size.BYTE);
        }
    }

    private void handlePartialByteWrite(int reg, int value) {
        boolean even = (reg & 1) == 0;
        if (even) {
            S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, reg, value & 0xF, Size.BYTE);
            return;
        }
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, reg, value & 0xFF, Size.BYTE);
    }

    private void writeWord(S32xUtil.CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value) {
        switch (regSpec) {
            case PWM_CTRL -> handlePwmControl(cpu, reg, value, Size.WORD);
            case PWM_CYCLE -> handlePwmCycleWord(cpu, value);
            case PWM_MONO -> writeMono(value);
            case PWM_LCH_PW -> writeFifo(fifoLeft, value);
            case PWM_RCH_PW -> writeFifo(fifoRight, value);
            default -> S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, Size.WORD);
        }
    }

    private void handlePwmCycleWord(S32xUtil.CpuDeviceAccess cpu, int value) {
        value &= 0xFFF;
        S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.PWM_CYCLE, sysRegsMd, S32xDict.RegSpecS32x.PWM_CYCLE.addr, value, Size.WORD);
        S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.PWM_CYCLE, sysRegsSh2, S32xDict.RegSpecS32x.PWM_CYCLE.addr, value, Size.WORD);
        int prevCycle = cycle;
        cycle = (value - 1) & 0xFFF;
        if (cycle < CYCLE_LIMIT) {
            LOG.warn("PWM cycle not supported: {}, limit: {}", cycle, CYCLE_LIMIT);
        }
        if (prevCycle != cycle) {
            handlePwmEnable(true);
        }
    }

    private void handlePwmControl(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        switch (cpu.regSide) {
            case MD -> handlePwmControlMd(cpu, reg, value, size);
            default -> handlePwmControlSh2(cpu, reg, value, size);
        }
        handlePwmEnable(false);
    }

    private void handlePwmControlMd(S32xUtil.CpuDeviceAccess cpu, int reg, int value, Size size) {
        assert size != Size.LONG;
        if (size == Size.BYTE && ((reg & 1) == 0)) {
            LOG.info("{} ignored write to {} {}, read only byte: val {} {}", cpu, S32xDict.RegSpecS32x.PWM_CTRL, th(reg), th(value), size);
            return;
        }
        int val = Util.readBufferWord(sysRegsMd, S32xDict.RegSpecS32x.PWM_CTRL.addr) & 0xFFF0;
        val |= value & 0xF;
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, reg, val, size);

        value = S32xUtil.readBufferWord(sysRegsMd, S32xDict.RegSpecS32x.PWM_CTRL.addr);
        channelMap[chLeft] = chanVals[value & 3];
        channelMap[chRight] = chanVals[(value >> 2) & 3];
    }

    private void handlePwmControlSh2(S32xUtil.CpuDeviceAccess cpu, int reg, int val, Size size) {
        int mask =
                switch (size) {
                    case WORD -> 0xF8F;
                    case BYTE -> (reg & 1) == 1 ? 0x8F : 0xF;
                    default -> 0;
                };
        //Primal Rage, Sh2 write bytes
        assert size != Size.LONG;
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, reg, val & mask, size);
        int value = S32xUtil.readBufferWord(sysRegsMd, S32xDict.RegSpecS32x.PWM_CTRL.addr);
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
            latestPwmValue[0] = latestPwmValue[1] = cycle >> 1; //TODO check
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
            if (verbose) LOG.warn("PWM FIFO push when fifo full: {}", th(value));
            return;
        }
        fifo.push((value - 1) & 0xFFF);
        updateFifoRegs();
    }

    //TEST only
    public int readFifoMono(S32xDict.RegSpecS32x regSpec) {
        return switch (regSpec) {
            case PWM_LCH_PW -> readFifo(fifoLeft, LEFT);
            case PWM_RCH_PW -> readFifo(fifoRight, RIGHT);
            case PWM_MONO -> readMono();
            default -> {
                assert false : regSpec;
                yield 0;
            }
        };
    }

    private int readMono() {
        return (readFifo(fifoLeft, LEFT) + readFifo(fifoRight, RIGHT)) >> 1;
    }

    private int readFifo(Fifo<Integer> fifo, PwmChannel chan) {
        if (fifo.isEmpty()) {
            if (verbose) LOG.warn("PWM FIFO pop when fifo empty: {}", th(latestPwmValue[chan.ordinal()]));
            return latestPwmValue[chan.ordinal()];
        }
        int res = fifo.pop();
        latestPwmValue[chan.ordinal()] = res;
        updateFifoRegs();
        return res;
    }

    //TODO update on read instead??
    //TODO keep the existing value instead of replacing it
    private void updateFifoRegs() {
        int regValue = (fifoLeft.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoLeft.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_LCH_PW.addr, regValue, Size.WORD);
        regValue = (fifoRight.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoRight.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_RCH_PW.addr, regValue, Size.WORD);
        updateMono();
    }

    private void updateMono() {
        int fifoFull = ((fifoLeft.isFullBit() | fifoRight.isFullBit()) << PWM_FIFO_FULL_BIT_POS);
        int fifoEmpty = ((fifoLeft.isEmptyBit() & fifoRight.isEmptyBit()) << PWM_FIFO_EMPTY_BIT_POS);
        int regValue = fifoFull | fifoEmpty;
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_MONO.addr, regValue, Size.WORD);
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
            //sample range should be [0,cycle], let's clamp to [sld, cycle - sld]
            ls = Math.min(cycle - SAMPLE_LIMIT_DELTA, readFifo(fifoLeft, LEFT) + SAMPLE_LIMIT_DELTA);
            rs = Math.min(cycle - SAMPLE_LIMIT_DELTA, readFifo(fifoRight, RIGHT) + SAMPLE_LIMIT_DELTA);
            assert ls >= SAMPLE_LIMIT_DELTA && rs >= SAMPLE_LIMIT_DELTA;
            if (--sh2ticksToNextPwmInterrupt == 0) {
                intControls[S32xUtil.CpuDeviceAccess.MASTER.ordinal()].setIntPending(IntControl.Sh2Interrupt.PWM_6, true);
                intControls[S32xUtil.CpuDeviceAccess.SLAVE.ordinal()].setIntPending(IntControl.Sh2Interrupt.PWM_6, true);
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
            dmac[S32xUtil.CpuDeviceAccess.MASTER.ordinal()].dmaReqTriggerPwm(PWM_DMA_CHANNEL, true);
            dmac[S32xUtil.CpuDeviceAccess.SLAVE.ordinal()].dmaReqTriggerPwm(PWM_DMA_CHANNEL, true);
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
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_LCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_RCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        S32xUtil.writeBuffers(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.PWM_MONO.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        handlePwmControlSh2(S32xUtil.CpuDeviceAccess.MASTER, S32xDict.RegSpecS32x.PWM_CTRL.addr, 0, Size.WORD); //init interruptInterval
    }

    @Override
    public void reset() {
        init();
        playSupport.reset();
        LOG.info("PWM reset done");
    }
}