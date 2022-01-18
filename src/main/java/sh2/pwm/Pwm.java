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

    public static Pwm pwm;

    public Pwm(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
        reset();
        pwm = this;
    }

    public void write(CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        switch (regSpec) {
            case PWM_CTRL:
                LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
                handlePwmControl(cpu, reg, value, size);
                break;
            case PWM_CYCLE:
                LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
                cycle = (value - 1) & 0xFFF;
                handlePwmEnable();
                break;
            case PWM_LCH_PW:
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, value, size);
                break;
            case PWM_RCH_PW:
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, value, size);
                break;
            case PWM_MONO:
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_RCH_PW.addr, value, size);
                writeBuffers(sysRegsMd, sysRegsSh2, PWM_LCH_PW.addr, value, size);
                break;
        }
    }

    private void handlePwmControl(CpuDeviceAccess cpu, int reg, int value, Size size) {
        int val = readBuffer(sysRegsMd, PWM_CTRL.addr, Size.WORD);
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
