package sh2.pwm;

import omegadrive.sound.javasound.AbstractSoundManager;
import omegadrive.util.PriorityThreadFactory;
import omegadrive.util.SoundUtil;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static omegadrive.util.Util.th;
import static sh2.pwm.Pwm.CYCLE_LIMIT;

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
    private final ReentrantLock lock;
    private final Condition dataReady;
    private boolean shouldPlay;

    public PwmPlaySupport() {
        executorService = Executors.newSingleThreadExecutor(new PriorityThreadFactory("pwm"));
        lock = new ReentrantLock();
        dataReady = lock.newCondition();
        dataLine = SoundUtil.createDataLine(AbstractSoundManager.audioFormat);
        executorService.submit(createRunnable(dataLine, bytes));
    }

    private Runnable createRunnable(final SourceDataLine line, final byte[][] buffers) {
        return () -> {
            Util.sleep(50);
            final Lock l = lock;
            final Condition c = dataReady;
            close = false;
            do {
                Util.waitOnCondition(l, c);
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
        shouldPlay = cycle >= CYCLE_LIMIT;
        if (!shouldPlay) {
            LOG.error("Unsupported cycle setting: {}, limit: {}, pwmSamplesPerFrame: {}",
                    cycle, CYCLE_LIMIT, pwmSamplesPerFrame);
        }
    }

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
        if (sampleIndex44 < data.length - 4) {
            data[sampleIndex44++] = sval;
            data[sampleIndex44++] = sval;
            data[sampleIndex44++] = sval;
            data[sampleIndex44++] = sval;
        }
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
            if (ENABLE && shouldPlay && !close) {
                boolean ok = Util.trySignalCondition(lock, dataReady);
                if (!ok) {
                    LOG.error("Pwm thread not ready!");
                    shouldPlay = false;
                }
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
