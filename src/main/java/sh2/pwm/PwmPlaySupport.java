package sh2.pwm;

import omegadrive.sound.javasound.AbstractSoundManager;
import omegadrive.util.PriorityThreadFactory;
import omegadrive.util.SoundUtil;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * TODO PAL
 */
public class PwmPlaySupport {

    private static final Logger LOG = LogManager.getLogger(PwmPlaySupport.class.getSimpleName());

    private static final boolean ENABLE = true;

    private final static float SH2_CLK_HZ = 23_100_000;
    private final static int fps = 60;
    private final static int NUM_BUFFERS = 5;

    private static final int expMonoSamples44 = (int) (44100.0 / fps);
    private static final int expStereoSamples44 = expMonoSamples44 << 1;

    //NOTE: it's not a whole number of samples per frame
    private int[] data = new int[expStereoSamples44 + 2];
    private byte[][] bytes = new byte[NUM_BUFFERS][data.length << 1]; //4 bytes per frame

    private int bufferIndex = 0, pwmSamplesPerFrame = 0, pwmSampleIndex = 0, sampleIndex44 = 0;
    private float scale = 0;

    private volatile boolean close = false;
    //index starts at 0, playIndex starts at 0-1
    private final AtomicInteger playIndex = new AtomicInteger(NUM_BUFFERS - 1);

    private final SourceDataLine dataLine;
    private final ExecutorService executorService;
    private final CyclicBarrier barrier;

    public PwmPlaySupport() {
        executorService = Executors.newSingleThreadExecutor(new PriorityThreadFactory("pwm"));
        barrier = new CyclicBarrier(2);
        dataLine = SoundUtil.createDataLine(AbstractSoundManager.audioFormat);
        executorService.submit(createRunnable(dataLine, bytes, barrier));
    }

    private Runnable createRunnable(final SourceDataLine line, final byte[][] buffers, final CyclicBarrier barr) {
        return () -> {
            Util.sleep(50);
            barr.reset();
            close = false;
            do {
                Util.waitOnBarrier(barr);
                final int idx = playIndex.get();
                SoundUtil.writeBufferInternal(line, buffers[idx], 0, buffers[idx].length);
            } while (!close);
            LOG.info("PWM thread exiting");
        };
    }

    //TODO this essentially only supports 22khz playback
    public void updatePwmCycle(int cycle) {
        float c = cycle;
        pwmSamplesPerFrame = (int) (SH2_CLK_HZ / (fps * cycle));
        scale = (Short.MAX_VALUE << 1) / c;
        int byteSamplesPerFrame = pwmSamplesPerFrame << 2;
        if (byteSamplesPerFrame > data.length) {
            LOG.error("Unsupported cycle setting: {}, pwmSamplesPerFrame: {}", cycle, pwmSamplesPerFrame);
            data = new int[byteSamplesPerFrame];
            for (int i = 0; i < NUM_BUFFERS; i++) {
                bytes[i] = new byte[data.length << 1]; //4 bytes per frame
            }
        }
    }

    public void playSample(int left, int right) {
        int val = (int) (((left + right) >> 1) * scale - Short.MAX_VALUE);
        if (val > Short.MAX_VALUE || val < Short.MIN_VALUE) {
            LOG.error("Oops: {}", val);
        }
        short sval = (short) val;
        data[sampleIndex44++] = sval;
        data[sampleIndex44++] = sval;
        data[sampleIndex44++] = sval;
        data[sampleIndex44++] = sval;
        pwmSampleIndex++;
        if (pwmSampleIndex == pwmSamplesPerFrame) {
            if (sampleIndex44 < expStereoSamples44) {
                Arrays.fill(data, sampleIndex44, data.length, data[sampleIndex44 - 2]);
            }
            pwmSampleIndex = sampleIndex44 = 0;
            SoundUtil.intStereo16ToByteStereo16Mix(data, bytes[bufferIndex], data.length);
            int prev = (bufferIndex + NUM_BUFFERS - 1) % NUM_BUFFERS;
            boolean res = playIndex.compareAndSet(prev, bufferIndex);
            if (!res) {
                prev = playIndex.getAndSet(bufferIndex);
                LOG.warn("playIndex prev: {}, now: {}", prev, bufferIndex);
            }
            bufferIndex = (bufferIndex + 1) % NUM_BUFFERS;
            if (ENABLE && !close) {
                Util.waitOnBarrier(barrier);
            }
        }
    }

    public void close() {
        close = true;
        if (executorService != null) {
            executorService.shutdown();
        }
        SoundUtil.close(dataLine);
    }
}
