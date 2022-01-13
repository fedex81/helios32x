package sh2.pwm;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32XMMREG;
import sh2.dict.S32xDict;
import sh2.sh2.device.IntControl;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Pwm implements StepDevice {

    private static final Logger LOG = LogManager.getLogger(Pwm.class.getSimpleName());

    private ByteBuffer sysRegsMd, sysRegsSh2;
    private IntControl[] intControls;

    public Pwm(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
        this.intControls = s32XMMREG.interruptControls;
        reset();
    }

    public void write(CpuDeviceAccess cpu, S32xDict.RegSpecS32x regSpec, int value, Size size) {
        LOG.info("{} PWM write {}: {} {}", cpu, regSpec.name, th(value), size);
        switch (regSpec) {
            case PWM_CTRL:
                break;
            case PWM_CYCLE:
                break;
            case PWM_LCH_PW:
            case PWM_RCH_PW:
            case PWM_MONO:
                break;
        }
    }

    @Override
    public void reset() {

    }
}
