package sh2.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.sound.fm.ExternalAudioProvider;
import omegadrive.sound.javasound.AbstractSoundManager;
import omegadrive.util.RegionDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.pwm.Pwm.CYCLE_LIMIT;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class S32xPwmProvider extends ExternalAudioProvider implements PwmProvider {

    private static final Logger LOG = LogManager.getLogger(S32xPwmProvider.class.getSimpleName());

    private static final boolean ENABLE = true;
    private float sh2ClockMhz, scale = 0;
    private int fps;

    private boolean shouldPlay;

    public S32xPwmProvider(RegionDetector.Region region) {
        super(RegionDetector.Region.USA, AbstractSoundManager.audioFormat);
        this.fps = region.getFps();
        this.sh2ClockMhz = region == RegionDetector.Region.EUROPE ? PAL_SH2CLOCK_MHZ : NTSC_SH2CLOCK_MHZ;
    }

    //TODO this essentially only supports 22khz playback
    @Override
    public void updatePwmCycle(int cycle) {
        float c = cycle;
        int pwmSamplesPerFrame = (int) (sh2ClockMhz / (fps * cycle));
        scale = (Short.MAX_VALUE << 1) / c;
        int byteSamplesPerFrame = pwmSamplesPerFrame << 2;
        shouldPlay = cycle >= CYCLE_LIMIT;
        start();
        if (!shouldPlay) {
            LOG.error("Unsupported cycle setting: {}, limit: {}, pwmSamplesPerFrame: {}",
                    cycle, CYCLE_LIMIT, pwmSamplesPerFrame);
            stop();
        }
    }

    @Override
    public void playSample(int left, int right) {
        if (!shouldPlay) {
            return;
        }
        int val = (int) (((left + right) >> 1) * scale - Short.MAX_VALUE);
        short sval = (short) val;
        if (sval != val) {
            float sc = scale;
            scale -= 1;
            LOG.warn("PWM value out of range (16 bit signed): {}, scale: {}, pwmVal: {}", th(val), sc, left);
            LOG.warn("Reducing scale: {} -> {}", sc, scale);
            sval = (short) Math.min(Math.max(val, Short.MIN_VALUE), Short.MAX_VALUE);
        }
        addMonoSample(sval);
        addMonoSample(sval);
    }

    @Override
    public SoundDeviceType getType() {
        return SoundDeviceType.PWM;
    }

    @Override
    public int updateStereo16(int[] buf_lr, int offset, int count) {
        int actualStereo = super.updateStereo16(buf_lr, offset, count);
        if (actualStereo > 0 && (actualStereo >> 1) < count) {
            offset <<= 1;
            int end = (count << 1) + offset;
            Arrays.fill(buf_lr, actualStereo, end, buf_lr[actualStereo - 1]);
            actualStereo = end;
        }
        return actualStereo;
    }

    @Override
    public void newFrame() {
        int len = queueLen.get();
        if (len > 5000) {
            LOG.warn("Pwm qLen: {}", len);
        }
    }

    @Override
    public void reset() {
        shouldPlay = false;
        super.reset();
    }
}
