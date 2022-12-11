package sh2.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.sound.fm.GenericAudioProvider;
import omegadrive.util.LogHelper;
import omegadrive.util.RegionDetector;
import org.slf4j.Logger;
import sh2.pwm.PwmUtil.*;

import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.pwm.Pwm.CYCLE_LIMIT;
import static sh2.pwm.PwmUtil.*;
import static sh2.pwm.PwmUtil.PwmStats.NO_STATS;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 * TODO: anything not using 22khz sounds bad (ie. Bad Apple 32x, cycle 719*60 -> 43khz)
 * TODO: other problematic stuff: Mars Test #2 (should be mute?), OutRom.bin
 */
public class S32xPwmProvider extends GenericAudioProvider implements PwmProvider {

    private static final Logger LOG = LogHelper.getLogger(S32xPwmProvider.class.getSimpleName());
    private static final boolean collectStats = Boolean.parseBoolean(System.getProperty("helios.32x.pwm.stats", "false"));

    private float sh2ClockMhz, scale = 0;
    private int cycle;
    private int fps;

    private boolean shouldPlay;
    private Warmup warmup = NO_WARMUP;
    private PwmStats stats = NO_STATS;

    public S32xPwmProvider(RegionDetector.Region region) {
        super(pwmAudioFormat);
        this.fps = region.getFps();
        this.sh2ClockMhz = region == RegionDetector.Region.EUROPE ? PAL_SH2CLOCK_MHZ : NTSC_SH2CLOCK_MHZ;
        if (collectStats) this.stats = new PwmStats();
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
        //very crude adaptive rate control, will break for anything not 22khz
        if (len < 2000) {
            addStereoSample(sleft, sright);
            addStereoSample(sleft, sright);
            if (collectStats) stats.monoSamplesPush++;
        } else if (len < 3000) {
            addStereoSample(sleft, sright);
            if (collectStats) stats.monoSamplesDiscardHalf++;
        } else {
            //drop both samples
            if (collectStats) stats.monoSamplesDiscard++;
        }
    }

    @Override
    public SoundDeviceType getType() {
        return SoundDeviceType.PWM;
    }

    int[] preFilter = new int[0];
    int[] prev = new int[2];

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
        if (collectStats) stats.monoSamplesPull += actualStereo >> 1;
        if (actualStereo == 0) {
            if (collectStats) stats.monoSamplesFiller += stereoSamples >> 1;
            Arrays.fill(buf_lr, 0, stereoSamples, 0); //TODO this is bad
            return stereoSamples;
        }
        if (actualStereo < stereoSamples) {
//            LOG.info("Sample requested {}, available: {}", stereoSamples >> 1, actualStereo >> 1);
            for (int i = actualStereo; i < stereoSamples; i += 2) {
                preFilter[i] = preFilter[actualStereo - 2];
                preFilter[i + 1] = preFilter[actualStereo - 1];
            }
            if (collectStats) stats.monoSamplesFiller += (stereoSamples - actualStereo) >> 1;
        }
        dcBlockerLpf(preFilter, buf_lr, prev, stereoSamples);
        warmup.doWarmup(buf_lr, stereoSamples);
        return stereoSamples;
    }

    @Override
    public void newFrame() {
        int monoLen = stereoQueueLen.get() >> 1;
        if (collectStats) {
            stats.print(monoLen);
            stats.reset();
        }
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
