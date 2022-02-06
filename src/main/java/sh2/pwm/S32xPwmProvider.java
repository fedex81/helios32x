package sh2.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.sound.fm.ExternalAudioProvider;
import omegadrive.sound.javasound.AbstractSoundManager;
import omegadrive.util.RegionDetector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private int cycle;
    private int fps;

    private boolean shouldPlay;

    public S32xPwmProvider(RegionDetector.Region region) {
        super(RegionDetector.Region.USA, AbstractSoundManager.audioFormat);
        this.fps = region.getFps();
        this.sh2ClockMhz = region == RegionDetector.Region.EUROPE ? PAL_SH2CLOCK_MHZ : NTSC_SH2CLOCK_MHZ;
    }

    @Override
    public void updatePwmCycle(int cycle) {
        float c = cycle;
        int pwmSamplesPerFrame = (int) (sh2ClockMhz / (fps * cycle));
        scale = (Short.MAX_VALUE << 1) / c;
        int byteSamplesPerFrame = pwmSamplesPerFrame << 2;
        shouldPlay = cycle >= CYCLE_LIMIT;
        this.cycle = cycle;
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
        int vleft = (int) ((left - (cycle >> 1)) * scale);
        int vright = (int) ((right - (cycle >> 1)) * scale);
        short sleft = (short) vleft;
        short sright = (short) vright;
        if (sleft != vleft || sright != vright) {
            float sc = scale;
            scale -= 1;
            LOG.warn("PWM value out of range (16 bit signed), L/R: {}/{}, scale: {}, " +
                    "pwmVal: {}/{}", th(sleft), th(sright), sc, left, right);
            LOG.warn("Reducing scale: {} -> {}", sc, scale);
            sleft = (short) Math.min(Math.max(sleft, Short.MIN_VALUE), Short.MAX_VALUE);
            sright = (short) Math.min(Math.max(sright, Short.MIN_VALUE), Short.MAX_VALUE);
        }
        short mono = (short) ((sleft >> 1) + (sright >> 1));
        addMonoSample(mono);
        addMonoSample(mono);
    }

    @Override
    public SoundDeviceType getType() {
        return SoundDeviceType.PWM;
    }

    int[] preFilter = new int[0];
    int[] prev = new int[2];
    double alpha = 0.995;

    @Override
    public int updateStereo16(int[] buf_lr, int offset, int count) {
        if (preFilter.length < buf_lr.length) {
            preFilter = buf_lr.clone();
        }
        int actualStereo = super.updateStereo16(preFilter, offset, count);
        dcBlocker(preFilter, buf_lr, buf_lr.length);
        return actualStereo;
    }

    /**
     * DC blocker filter
     */
    public void dcBlocker(int[] in, int[] out, int len) {
        out[0] = prev[0];
        out[1] = prev[1];
        for (int i = 2; i < len; i += 2) {
            out[i] = (int) (in[i] - in[i - 2] + out[i - 2] * alpha); //left
            out[i + 1] = (int) (in[i + 1] - in[i - 1] + out[i - 1] * alpha); //right
        }
        prev[0] = out[len - 2];
        prev[1] = out[len - 1];
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
