package sh2.pwm;

import omegadrive.util.Fifo;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;
import sh2.dict.S32xDict.RegSpecS32x;
import sh2.sh2.device.DmaC;
import sh2.sh2.device.IntControl;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.pwm.Pwm.PwmChannelSetup.OFF;
import static sh2.sh2.device.IntControl.Sh2Interrupt.PWM_6;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Pwm implements StepDevice {

    private static final Logger LOG = LogManager.getLogger(Pwm.class.getSimpleName());

    enum PwmChannelSetup {
        OFF, SAME, FLIP, INVALID;

        public boolean isValid() {
            return this == SAME || this == FLIP;
        }
    }

    private static final int chLeft = 0, chRight = 1;
    private static final int PWM_FIFO_SIZE = 3;
    private static final int PWM_FIFO_FULL_BIT_POS = 15;
    private static final int PWM_FIFO_EMPTY_BIT_POS = 14;

    private static final PwmChannelSetup[] chanVals = PwmChannelSetup.values();

    private ByteBuffer sysRegsMd, sysRegsSh2;
    private IntControl[] intControls;

    private PwmChannelSetup[] channelMap = {OFF, OFF};
    private boolean pwmEnable, dreqEn;
    private int cycle = 0, interruptInterval;
    private int sh2TicksToNextPwmSample, sh2ticksToNextPwmInterrupt;
    private int pwmSamplesPerFrame = 0, stepsPerFrame = 0;

    @Deprecated
    public static Pwm pwm;

    private Fifo<Integer> fifoLeft, fifoRight;
    private int latestPwmValue = 0;
    private static final boolean verbose = false;
    private PwmPlaySupport playSupport;

    public Pwm(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
        this.fifoLeft = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        this.fifoRight = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        this.playSupport = new PwmPlaySupport();
        pwm = this;
        init();
    }

    public int read(CpuDeviceAccess cpu, RegSpecS32x regSpec, int address, Size size) {
        int res = readBuffer(sysRegsMd, address, size);
        if (verbose) LOG.info("{} PWM read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }

    public void write(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        switch (size) {
            case BYTE:
            case WORD:
                writeWord(cpu, regSpec, reg, value, size);
                break;
            case LONG:
                writeWord(cpu, regSpec, reg, value >> 16, size);
                writeWord(cpu, S32xDict.getRegSpec(cpu, regSpec.addr + 2), reg + 2, value & 0xFFFF, size);
                break;
        }
    }

    private void writeWord(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        if (verbose) LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);

        switch (regSpec) {
            case PWM_CTRL:
                handlePwmControl(cpu, reg, value, size);
                break;
            case PWM_CYCLE:
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, size);
                cycle = (value - 1) & 0xFFF;
                handlePwmEnable();
                break;
            case PWM_MONO:
                writeMono(reg, value, size);
                break;
            case PWM_LCH_PW:
                writeFifo(regSpec, reg, fifoLeft, value, size);
                break;
            case PWM_RCH_PW:
                writeFifo(regSpec, reg, fifoRight, value, size);
                break;
            default:
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, size);
                break;
        }
    }

    private void handlePwmControl(CpuDeviceAccess cpu, int reg, int value, Size size) {
        switch (cpu) {
            case M68K:
                if ((reg & 1) == 0 && size == Size.BYTE) {
                    LOG.warn("ignore");
                    return;
                }
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_CTRL.addr, value & 0xF, size);
                channelMap[chLeft] = chanVals[value & 3];
                channelMap[chRight] = chanVals[(value >> 2) & 3];
                break;
            default:
                if ((reg & 1) == 0 && size == Size.BYTE) {
                    LOG.error("unhandled");
                }
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_CTRL.addr, value, size);
                dreqEn = ((value >> 7) & 1) > 0;
                int ival = (value >> 8) & 0xF;
                interruptInterval = ival == 0 ? 0x10 : ival;
                channelMap[chLeft] = chanVals[value & 3];
                channelMap[chRight] = chanVals[(value >> 2) & 3];
                break;
        }
        handlePwmEnable();
    }

    private void handlePwmEnable() {
        boolean wasEnabled = pwmEnable;
        pwmEnable = cycle > 0 && (channelMap[chLeft].isValid() || channelMap[chRight].isValid());
        if (pwmEnable) {
            sh2TicksToNextPwmSample = cycle;
            sh2ticksToNextPwmInterrupt = interruptInterval;
            latestPwmValue = cycle >> 1;
            resetFifo();
            updateFifoRegs();
            playSupport.updatePwmCycle(cycle);
        }
    }

    private void resetFifo() {
        fifoRight.clear();
        fifoLeft.clear();
        do {
            fifoLeft.push(latestPwmValue);
            fifoRight.push(latestPwmValue);
        } while (!fifoLeft.isFull());
    }

    private void writeFifo(RegSpecS32x regSpec, int reg, Fifo<Integer> fifo, int value, Size size) {
        if (size == Size.BYTE) {
            LOG.error("{} unexpected PWM write {}, reg: {}: {} {}", regSpec.name, th(reg), th(value), size);
        }
        if (fifo.isFull()) {
            //TODO replace oldest
            LOG.error("PWM FIFO push when fifo full");
            return;
        }
        fifo.push(value & 0xFFF);
        updateFifoRegs();
    }

    public int readFifo(RegSpecS32x regSpec, Fifo<Integer> fifo) {
        if (fifo.isEmpty()) {
            return latestPwmValue;
        }
        latestPwmValue = fifo.pop();
        updateFifoRegs();
        return latestPwmValue;
    }

    private void updateFifoRegs() {
        int regValue = (fifoLeft.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoLeft.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, regValue, Size.WORD);
        regValue = (fifoRight.isFullBit() << PWM_FIFO_FULL_BIT_POS) | (fifoRight.isEmptyBit() << PWM_FIFO_EMPTY_BIT_POS);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, regValue, Size.WORD);
        updateMono(PWM_RCH_PW);
    }

    private void updateMono(RegSpecS32x regSpec) {
        int regValue = ((fifoLeft.isFullBit() | fifoRight.isFullBit()) << PWM_FIFO_FULL_BIT_POS) |
                ((fifoLeft.isEmptyBit() & fifoRight.isEmptyBit()) << PWM_FIFO_EMPTY_BIT_POS);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, regValue, Size.WORD);
    }

    public void writeMono(int reg, int value, Size size) {
        writeFifo(PWM_LCH_PW, reg, fifoLeft, value, size);
        writeFifo(PWM_RCH_PW, reg, fifoRight, value, size);
    }

    public void newFrame() {
//        LOG.info("Samples per frame: {}, stepsPerFrame: {}", samplesProducedFrame ,stepsPerFrame);
        pwmSamplesPerFrame = 0;
        stepsPerFrame = 0;
    }

    @Override
    public void step() {
        stepsPerFrame++;
        if (pwmEnable) {
            if (--sh2TicksToNextPwmSample == 0) {
                sh2TicksToNextPwmSample = cycle;
                pwmSamplesPerFrame++;
                playSupport.playSample(readFifo(PWM_LCH_PW, fifoLeft), readFifo(PWM_RCH_PW, fifoRight));
                if (--sh2ticksToNextPwmInterrupt == 0) {
                    intControls[0].setIntPending(PWM_6, true);
                    intControls[1].setIntPending(PWM_6, true);
                    if (dreqEn) {
                        //NOTE this should trigger dmaStep on channel one for BOTH sh2s
                        DmaC.dmaC[0].dmaReqTrigger(1, true);
                        DmaC.dmaC[1].dmaReqTrigger(1, true);
                    }
                    sh2ticksToNextPwmInterrupt = interruptInterval;
                }
            }
        }
    }

    public void setIntControls(IntControl... intControls) {
        this.intControls = intControls;
    }

    @Override
    public void init() {
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, (1 << PWM_FIFO_EMPTY_BIT_POS), Size.WORD);
    }

    @Override
    public void reset() {
        init();
        playSupport.close();
        LOG.info("PWM reset done");
    }
}
