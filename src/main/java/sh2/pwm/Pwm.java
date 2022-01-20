package sh2.pwm;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;
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

    private static final PwmChannelSetup[] chanVals = PwmChannelSetup.values();

    private ByteBuffer sysRegsMd, sysRegsSh2;
    private IntControl[] intControls;

    private PwmChannelSetup[] channelMap = {OFF, OFF};
    private boolean pwmEnable, dreqEn;
    private int cycle = 0, interruptInterval;
    private int sh2TicksToNextPwmSample, sh2ticksToNextPwmInterrupt;

    @Deprecated
    public static Pwm pwm;

    private int fifoCountLeft = 0, fifoCountRight = 0;
    private static final boolean verbose = false;

    public Pwm(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
        reset();
        pwm = this;
    }

    public int read(CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int address, Size size) {
        int res = readBuffer(sysRegsMd, address, Size.WORD);
        switch (regSpec) {
            case PWM_MONO:
            case PWM_RCH_PW:
                int full = fifoCountRight >= 3 ? 1 : 0;
                int empty = fifoCountRight == 0 ? 1 : 0;
                res |= (full << 15) | (empty << 14);
                res = size == Size.BYTE ? res >> 8 : res;
                break;
            case PWM_LCH_PW:
                int full1 = fifoCountLeft >= 3 ? 1 : 0;
                int empty1 = fifoCountLeft == 0 ? 1 : 0;
                res |= (full1 << 15) | (empty1 << 14);
                res = size == Size.BYTE ? res >> 8 : res;
                break;
        }
        if (verbose) LOG.info("{} PWM read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }

    public void write(CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
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
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_MONO.addr, value, size);
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, value, size);
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, value, size);
                fifoCountLeft = Math.min(3, fifoCountLeft + 1);
                fifoCountRight = Math.min(3, fifoCountRight + 1);
                break;
            case PWM_LCH_PW:
                fifoCountLeft = Math.min(3, fifoCountLeft + 1);
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, size);
                break;
            case PWM_RCH_PW:
                fifoCountRight = Math.min(3, fifoCountRight + 1);
                writeBuffers(sysRegsMd, sysRegsSh2, regSpec.addr, value, size);
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
                }
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
        handlePwmEnable();
    }

    private void handlePwmEnable() {
        pwmEnable = cycle > 0 && (channelMap[chLeft].isValid() || channelMap[chRight].isValid());
        if (pwmEnable) {
            sh2TicksToNextPwmSample = cycle;
            sh2ticksToNextPwmInterrupt = interruptInterval;
        }
    }

    @Override
    public void step() {
        if (pwmEnable) {
            if (--sh2TicksToNextPwmSample == 0) {
                sh2TicksToNextPwmSample = cycle;
                //                playSample();
                fifoCountLeft = Math.max(0, fifoCountLeft - 1);
                fifoCountRight = Math.max(0, fifoCountRight - 1);
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
    public void reset() {
    }
}
