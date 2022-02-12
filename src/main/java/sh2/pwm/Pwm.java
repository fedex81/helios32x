package sh2.pwm;

import omegadrive.sound.PwmProvider;
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

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
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

    private static final Logger LOG = LogManager.getLogger(Pwm.class.getSimpleName());

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
    private int latestPwmValue = 0;
    private static final boolean verbose = false;
    private PwmProvider playSupport;

    public Pwm(S32XMMREG.RegContext regContext) {
        this.sysRegsMd = regContext.sysRegsMd;
        this.sysRegsSh2 = regContext.sysRegsSh2;
        this.fifoLeft = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
        this.fifoRight = Fifo.createIntegerFixedSizeFifo(PWM_FIFO_SIZE);
//        this.playSupport = new PwmPlaySupportImpl();
        init();
    }

    public int read(CpuDeviceAccess cpu, RegSpecS32x regSpec, int address, Size size) {
        int res = readBuffer(sysRegsMd, address, size);
        if (verbose) LOG.info("{} PWM read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }

    public void write(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        if (verbose) LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
        switch (size) {
            case BYTE:
                writeByte(cpu, regSpec, reg, value, size);
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

    public void writeByte(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value, Size size) {
        LOG.warn("{} PWM write {}, byte {}: {} {}", cpu, regSpec.name, reg & 1, th(value), size);
        //NOTE: z80 writes MSB then LSB, we trigger the fifo push when writing to LSB
        if ((reg & 1) == 0) {
            writeBuffers(sysRegsMd, sysRegsSh2, reg, value, size);
            return;
        }
        int val = readBuffer(sysRegsMd, regSpec.addr, Size.WORD);
        boolean even = (reg & 1) == 0;
        val = (val & (even ? 0xFF : 0xFF00)) | (even ? value << 8 : value);
        writeWord(cpu, regSpec, reg, val);
    }

    private void writeWord(CpuDeviceAccess cpu, RegSpecS32x regSpec, int reg, int value) {
        if (verbose) LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), Size.WORD);
        switch (regSpec) {
            case PWM_CTRL:
                handlePwmControl(cpu, reg, value, Size.WORD);
                break;
            case PWM_CYCLE:
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, Size.WORD);
                int prevCycle = cycle;
                cycle = (value - 1) & 0xFFF;
                if (cycle < CYCLE_LIMIT) {
                    LOG.warn("PWM cycle not supported: {}, limit: {}", cycle, CYCLE_LIMIT);
                }
                if (prevCycle != cycle) {
                    handlePwmEnable(true);
                }
                break;
            case PWM_MONO:
                writeMono(reg, value, Size.WORD);
                break;
            case PWM_LCH_PW:
                writeFifo(regSpec, reg, fifoLeft, value, Size.WORD);
                break;
            case PWM_RCH_PW:
                writeFifo(regSpec, reg, fifoRight, value, Size.WORD);
                break;
            default:
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, Size.WORD);
                break;
        }
    }

    private void handlePwmControl(CpuDeviceAccess cpu, int reg, int value, Size size) {
        switch (cpu) {
            case M68K:
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_CTRL.addr, value & 0xF, size);
                channelMap[chLeft] = chanVals[value & 3];
                channelMap[chRight] = chanVals[(value >> 2) & 3];
                break;
            default:
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_CTRL.addr, value, size);
                dreqEn = ((value >> 7) & 1) > 0;
                int ival = (value >> 8) & 0xF;
                interruptInterval = ival == 0 ? 0x10 : ival;
                channelMap[chLeft] = chanVals[value & 3];
                channelMap[chRight] = chanVals[(value >> 2) & 3];
                break;
        }
        handlePwmEnable(false);
    }

    private void handlePwmEnable(boolean cycleChanged) {
        boolean wasEnabled = pwmEnable;
        pwmEnable = cycle > 0 && (channelMap[chLeft].isValid() || channelMap[chRight].isValid());
        if (!wasEnabled && pwmEnable || cycleChanged) {
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
    }

    private void writeFifo(RegSpecS32x regSpec, int reg, Fifo<Integer> fifo, int value, Size size) {
        if (fifo.isFull()) {
            //TODO replace oldest
//            LOG.warn("PWM FIFO push when fifo full");
//            dreq();
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
        int fifoFull = ((fifoLeft.isFullBit() | fifoRight.isFullBit()) << PWM_FIFO_FULL_BIT_POS);
        int fifoEmpty = ((fifoLeft.isEmptyBit() & fifoRight.isEmptyBit()) << PWM_FIFO_EMPTY_BIT_POS);
        int regValue = fifoFull | fifoEmpty;
        writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, regValue, Size.WORD);
    }

    public void writeMono(int reg, int value, Size size) {
        writeFifo(PWM_LCH_PW, reg, fifoLeft, value, size);
        writeFifo(PWM_RCH_PW, reg, fifoRight, value, size);
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
        stepsPerFrame += cycles;
        if (pwmEnable) {
            stepOne();
            stepOne();
            stepOne();
        }
    }

    private int rs, ls;

    private void stepOne() {
        if (--sh2TicksToNextPwmSample == 0) {
            sh2TicksToNextPwmSample = cycle;
            pwmSamplesPerFrame++;
            ls = Math.min(cycle, readFifo(PWM_LCH_PW, fifoLeft));
            rs = Math.min(cycle, readFifo(PWM_RCH_PW, fifoRight));
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
    }

    @Override
    public void reset() {
        init();
        playSupport.reset();
        LOG.info("PWM reset done");
    }
}
