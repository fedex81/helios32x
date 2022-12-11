package sh2.pwm;

import omegadrive.sound.SoundProvider;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.S32xUtil;

import javax.sound.sampled.AudioFormat;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class PwmUtil {

    private static final Logger LOG = LogHelper.getLogger(PwmUtil.class.getSimpleName());

    public static final Warmup NO_WARMUP = new Warmup();
    public static final Warmup WARMUP = new Warmup();

    public static AudioFormat pwmAudioFormat = new AudioFormat(SoundProvider.SAMPLE_RATE_HZ,
            16, 2, true, false);

    //dc blocker alpha
    private static final double alpha = 0.995;

    public static class Warmup {
        static final int stepSamples = 0x7FF;
        static final double stepFactor = 0.02;

        static {
            S32xUtil.assertPowerOf2Minus1("pwmStepSamples", stepSamples);
        }

        boolean isWarmup;
        int currentSamples;
        double currentFactor = 0.0;

        public int doWarmup(int sample) {
            if (isWarmup) {
                int res = (int) (sample * currentFactor);
                addSampleCount(1);
                return res;
            }
            return sample;
        }

        private void addSampleCount(int add) {
            int start = currentSamples & Warmup.stepSamples;
            currentSamples += add;
            if ((currentSamples & Warmup.stepSamples) < start) {
                currentFactor += Warmup.stepFactor;
                if (currentFactor >= 1.0) {
                    reset();
                    LOG.info("PWM warmup done");
                }
            }
        }

        public void doWarmup(int[] out, int len) {
            if (isWarmup) {
                int start = currentSamples & Warmup.stepSamples;
                for (int i = 2; i < len; i += 2) {
                    out[i] *= currentFactor;
                    out[i + 1] *= currentFactor;
                }
                addSampleCount(len); //TODO check this
            }
        }

        public void reset() {
            isWarmup = false;
            currentSamples = 0;
            currentFactor = 0.0;
        }
    }

    public static void setSigned16LE(short value, byte[] data, int startIndex) {
        data[startIndex + 1] = (byte) (value >> 8);
        data[startIndex] = (byte) value;
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

    static class PwmStats {

        public static final PwmStats NO_STATS = new PwmStats();
        public int monoSamplesFiller = 0, monoSamplesPull = 0, monoSamplesPush = 0, monoSamplesDiscard = 0,
                monoSamplesDiscardHalf = 0;

        public void print(int monoLen) {
            LOG.info("Pwm frame monoSamples, push: {} (discard: {}, discardHalf: {}), pop: {}, filler: {}, " +
                            "tot: {}, monoQLen: {}",
                    monoSamplesPush, monoSamplesDiscard, monoSamplesDiscardHalf, monoSamplesPull, monoSamplesFiller,
                    monoSamplesPull + monoSamplesFiller, monoLen);
        }

        public void reset() {
            monoSamplesFiller = monoSamplesPull = monoSamplesPush = monoSamplesDiscard = monoSamplesDiscardHalf = 0;
        }
    }
}
