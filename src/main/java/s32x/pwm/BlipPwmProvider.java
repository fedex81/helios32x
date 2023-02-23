package s32x.pwm;

import omegadrive.sound.PwmProvider;
import omegadrive.util.LogHelper;
import omegadrive.util.RegionDetector;
import omegadrive.util.SoundUtil;
import org.slf4j.Logger;
import s32x.util.blipbuffer.BlipBuffer;

import javax.sound.sampled.SourceDataLine;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static omegadrive.util.Util.th;
import static s32x.pwm.PwmUtil.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * <p>
 */
public class BlipPwmProvider implements PwmProvider {

    private static final Logger LOG = LogHelper.getLogger(BlipPwmProvider.class.getSimpleName());

    private static final int BUF_SIZE_MS = 50;

    private AtomicReference<BlipBufferContext> ref = new AtomicReference<>();

    //TODO use stereo buffer
    static class BlipBufferContext {
        BlipBuffer monoBuffer;
        byte[] lineBuffer;
        int cycle, inputClocksForInterval;
        float scale;

        @Override
        public String toString() {
            return new StringJoiner(", ", BlipBufferContext.class.getSimpleName() + "[", "]")
                    .add("cycle=" + cycle)
                    .add("inputClocksForInterval=" + inputClocksForInterval)
                    .add("scale=" + scale)
                    .toString();
        }
    }

    private float sh2ClockMhz;
    private double frameIntervalMs;
    private double deltaTime;

    private int prevSample;

    int[] preFilter = new int[0];
    int[] pfPrev = new int[2];
    static final double pfAlpha = 0.995;

    private SourceDataLine dataLine;
    private Warmup warmup = NO_WARMUP;

    private ExecutorService exec = Executors.newSingleThreadExecutor();

    public BlipPwmProvider(RegionDetector.Region region) {
        ref.set(new BlipBufferContext());
        frameIntervalMs = region.getFrameIntervalMs();
        dataLine = SoundUtil.createDataLine(pwmAudioFormat);
        sh2ClockMhz = region == RegionDetector.Region.EUROPE ? PAL_SH2CLOCK_MHZ : NTSC_SH2CLOCK_MHZ;
        updatePwmCycle(1042);
    }

    @Override
    public void updatePwmCycle(int cycle) {
        BlipBufferContext context = ref.get();
        if (cycle == context.cycle) {
            return;
        }
        BlipBuffer monoBuffer = new BlipBuffer();
        monoBuffer.setSampleRate((int) pwmAudioFormat.getSampleRate(), BUF_SIZE_MS);
        monoBuffer.setClockRate((int) (sh2ClockMhz / cycle));

        BlipBufferContext bbc = new BlipBufferContext();
        bbc.cycle = cycle;
        bbc.scale = (Short.MAX_VALUE << 1) / (float) cycle;
        bbc.inputClocksForInterval = (int) (1.0 * monoBuffer.clockRate() * frameIntervalMs / 1000.0);

        int outSamplesPerInterval = (int) (pwmAudioFormat.getSampleRate() * BUF_SIZE_MS / 1000.0);
        preFilter = new int[outSamplesPerInterval];
        bbc.lineBuffer = new byte[0];
        bbc.monoBuffer = monoBuffer;
        ref.set(bbc);
        warmup = WARMUP;
        warmup.reset();
        warmup.isWarmup = true;
        logInfo(bbc);
        LOG.info("PWM warmup start");
    }

    @Override
    public void playSample(int left, int right) {
        int sample = scalePwmSample(warmup.doWarmup((left + right) >> 1));
        if (sample != prevSample) {
            ref.get().monoBuffer.addDelta((int) deltaTime, sample - prevSample);
            prevSample = sample;
        }
        deltaTime++;
    }

    private int scalePwmSample(int sample) {
        int vsample = (int) ((sample - (ref.get().cycle >> 1)) * ref.get().scale);
        short scaled = (short) vsample;
        if (scaled != vsample) {
            float scale = ref.get().scale;
            scale -= 1;
            LOG.warn("PWM value out of range (16 bit signed): {}, scale: {}, " +
                    "pwmVal: {}", th(scaled), ref.get().scale, sample);
            LOG.warn("Reducing scale: {} -> {}", ref.get().scale, scale);
            scaled = (short) Math.min(Math.max(vsample, Short.MIN_VALUE), Short.MAX_VALUE);
        }
        return scaled;
    }

    @Override
    public int updateStereo16(int[] buf_lr, int offset, int countMono) {
        return countMono << 1;
    }


    private int prevSampleAvail = 0;

    @Override
    public void newFrame() {
        BlipBufferContext context = ref.get();
        BlipBuffer monoBuffer = context.monoBuffer;
        monoBuffer.endFrame(context.inputClocksForInterval);
        deltaTime = 0;
        int availMonoSamples = monoBuffer.samplesAvail();
        if (availMonoSamples + 5 < prevSampleAvail) {
            LOG.info("Audio underrun : {} -> {} samples", prevSampleAvail, availMonoSamples);
        }
        if (context.lineBuffer.length < availMonoSamples << 2) {
            LOG.info("Audio buffer size: {} -> {} bytes", context.lineBuffer.length, availMonoSamples << 2);
            context.lineBuffer = new byte[availMonoSamples << 2];
        }
        int stereoBytes = monoBuffer.readSamples16bitStereo(context.lineBuffer, 0, availMonoSamples) << 2;
        if (stereoBytes > 0) {
            exec.submit(() -> {
                SoundUtil.writeBufferInternal(dataLine, context.lineBuffer, 0, stereoBytes);
            });
        }
        prevSampleAvail = availMonoSamples;
    }

    private void logInfo(BlipBufferContext ctx) {
        int outSamplesPerInterval = (int) (pwmAudioFormat.getSampleRate() * BUF_SIZE_MS / 1000.0);
        int inSamplesPerInterval = (int) (ctx.monoBuffer.clockRate() * BUF_SIZE_MS / 1000.0);
        LOG.info("{}\nOutput sampleRate: {}, Input sampleRate: {}, outputBufLenMs: {}, outputBufLenSamples: {}" +
                        ", inputBufLenSamples: {}", ctx, pwmAudioFormat.getSampleRate(), ctx.monoBuffer.clockRate(), BUF_SIZE_MS,
                outSamplesPerInterval, inSamplesPerInterval);
    }

    @Override
    public void reset() {
        SoundUtil.close(dataLine);
    }
}