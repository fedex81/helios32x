package sh2.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.sound.SoundProvider;
import omegadrive.sound.fm.GenericAudioProvider;
import omegadrive.util.LogHelper;
import omegadrive.util.RegionDetector;
import org.slf4j.Logger;

import javax.sound.sampled.AudioFormat;
import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.pwm.Pwm.CYCLE_LIMIT;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class S32xPwmProvider extends GenericAudioProvider implements PwmProvider {

    private static final Logger LOG = LogHelper.getLogger(S32xPwmProvider.class.getSimpleName());
    private static final Warmup NO_WARMUP = new Warmup();
    private static final Warmup WARMUP = new Warmup();
    public static AudioFormat pwmAudioFormat = new AudioFormat(SoundProvider.SAMPLE_RATE_HZ,
            16, 2, true, false);
    private static final boolean printStats = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.stats", "false"));

    private float sh2ClockMhz, scale = 0;
    private int cycle;
    private int fps;

    private boolean shouldPlay;
    private Warmup warmup = NO_WARMUP;
    private PwmStats stats;

    static class Warmup {
        static final int stepSamples = 5_000;
        static final double stepFactor = 0.02;
        boolean isWarmup;
        int currentSamples;
        double currentFactor = 0.0;

        public void reset() {
            isWarmup = false;
            currentSamples = 0;
            currentFactor = 0.0;
        }
    }

    static class PwmStats {
        public int monoSamplesFiller = 0, monoSamplesPull = 0, monoSamplesPush = 0, monoSamplesDiscard = 0,
                monoSamplesDiscardHalf = 0;

        public void print(int monoLen) {
            if (!printStats) {
                return;
            }
            LOG.info("Pwm frame monoSamples, push: {} (discard: {}, discardHalf: {}), pop: {}, filler: {}, " +
                            "tot: {}, monoQLen: {}",
                    monoSamplesPush, monoSamplesDiscard, monoSamplesDiscardHalf, monoSamplesPull, monoSamplesFiller,
                    monoSamplesPull + monoSamplesFiller, monoLen);
        }

        public void reset() {
            monoSamplesFiller = monoSamplesPull = monoSamplesPush = monoSamplesDiscard = monoSamplesDiscardHalf = 0;
        }
    }

    public S32xPwmProvider(RegionDetector.Region region) {
        super(pwmAudioFormat);
        this.fps = region.getFps();
        this.sh2ClockMhz = region == RegionDetector.Region.EUROPE ? PAL_SH2CLOCK_MHZ : NTSC_SH2CLOCK_MHZ;
        this.stats = new PwmStats();
    }

    @Override
    public void updatePwmCycle(int cycle) {
        float c = cycle;
        int pwmSamplesPerFrame = (int) (sh2ClockMhz / (fps * cycle));
        scale = (Short.MAX_VALUE << 1) / c;
        shouldPlay = cycle >= CYCLE_LIMIT;
        this.cycle = cycle;
        start();
        if (!shouldPlay) {
            LOG.error("Unsupported cycle setting: {}, limit: {}, pwmSamplesPerFrame: {}",
                    cycle, CYCLE_LIMIT, pwmSamplesPerFrame);
            stop();
        } else {
            LOG.info("PWM cycle setting: {}, limit: {}, pwmSamplesPerFrame: {}",
                    cycle, CYCLE_LIMIT, pwmSamplesPerFrame);
            warmup = WARMUP;
            warmup.reset();
            warmup.isWarmup = true;
            LOG.info("PWM warmup start");
        }
    }

    //NOTE source is running ~22khz
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
        final int len = stereoQueueLen.get();
        if (len < 2000) {
            addStereoSample(sleft, sright);
            addStereoSample(sleft, sright);
            stats.monoSamplesPush++;
        } else if (len < 3000) {
            addStereoSample(sleft, sright);
            stats.monoSamplesDiscardHalf++;
        } else {
            //drop both samples
            stats.monoSamplesDiscard++;
        }
    }

    @Override
    public SoundDeviceType getType() {
        return SoundDeviceType.PWM;
    }

    int[] preFilter = new int[0];
    int[] prev = new int[2];
    static final double alpha = 0.995;

    @Override
    public int updateStereo16(int[] buf_lr, int offset, int countMono) {
        int stereoSamples = countMono << 1;
        if (countMono == 0 || !running) {
            return stereoSamples;
        }
        if (preFilter.length < buf_lr.length) {
            preFilter = buf_lr.clone();
        }
        int actualStereo = super.updateStereo16(preFilter, offset, countMono);
        stats.monoSamplesPull += actualStereo >> 1;
        if (actualStereo == 0) {
            stats.monoSamplesFiller += stereoSamples >> 1;
            Arrays.fill(buf_lr, 0, stereoSamples, 0);
            return stereoSamples;
        }
        if (actualStereo < stereoSamples) {
//            LOG.info("Sample requested {}, available: {}", stereoSamples >> 1, actualStereo >> 1);
            for (int i = actualStereo; i < stereoSamples; i += 2) {
                preFilter[i] = preFilter[actualStereo - 2];
                preFilter[i + 1] = preFilter[actualStereo - 1];
            }
            stats.monoSamplesFiller += (stereoSamples - actualStereo) >> 1;
        }
        dcBlockerLpf(preFilter, buf_lr, prev, stereoSamples);
        doWarmup(buf_lr, stereoSamples);
        return stereoSamples;
    }

    /**
     * DC blocker + low pass filter
     */
    public static void dcBlockerLpf(int[] in, int[] out, int[] prevLR, int len) {
        out[0] = prevLR[0];
        out[1] = prevLR[1];
        for (int i = 2; i < len; i += 2) {
            out[i] = (int) (in[i] - in[i - 2] + out[i - 2] * alpha); //left
            out[i + 1] = (int) (in[i + 1] - in[i - 1] + out[i - 1] * alpha); //right
            out[i] = (out[i] + out[i - 2]) >> 1; //lpf
            out[i + 1] = (out[i + 1] + out[i - 1]) >> 1;
        }
        prevLR[0] = out[len - 2];
        prevLR[1] = out[len - 1];
    }

    private void doWarmup(int[] out, int len) {
        if (warmup.isWarmup) {
            int start = warmup.currentSamples % Warmup.stepSamples;
            for (int i = 2; i < len; i += 2) {
                out[i] *= warmup.currentFactor;
                out[i + 1] *= warmup.currentFactor;
            }
            warmup.currentSamples += len;
            if (warmup.currentSamples % Warmup.stepSamples < start) {
                warmup.currentFactor += Warmup.stepFactor;
                if (warmup.currentFactor >= 1.0) {
                    warmup = NO_WARMUP;
                    LOG.info("PWM warmup done");
                }
            }
        }
    }

    @Override
    public void newFrame() {
        int monoLen = stereoQueueLen.get() >> 1;
        stats.print(monoLen);
        stats.reset();
        if (monoLen > 5000) {
            LOG.warn("Pwm monoQLen: {}", monoLen);
            sampleQueue.clear();
            stereoQueueLen.set(0);
        }
    }

    @Override
    public void reset() {
        shouldPlay = false;
        WARMUP.reset();
        super.reset();
    }
}
