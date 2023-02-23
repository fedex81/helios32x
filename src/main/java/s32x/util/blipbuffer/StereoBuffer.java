package s32x.util.blipbuffer;

import static s32x.pwm.PwmUtil.setSigned16LE;

/**
 * StereoBuffer
 *
 * @Copyright Shay Greeen
 * @Copyright Federico Berti
 */
public final class StereoBuffer {
    private final BlipBuffer[] bufs = new BlipBuffer[3];
    private final String name;
    private static final int CENTER = 2, LEFT = 0, RIGHT = 1;


    // Same behavior as in BlipBuffer unless noted

    public StereoBuffer(String name) {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i] = new BlipBuffer();
        }
        this.name = name;
    }

    public void setSampleRate(int rate, int msec) {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i].setSampleRate(rate, msec);
        }
    }

    public void setClockRate(int rate) {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i].setClockRate(rate);
        }
    }

    public int clockRate() {
        assert bufs[0].clockRate() == bufs[1].clockRate();
        return bufs[0].clockRate();
    }

    public int countSamples(int time) {
        return bufs[0].countSamples(time);
    }

    public void clear() {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i].clear();
        }
    }

    public void setVolume(double v) {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i].setVolume(v);
        }
    }

    // The three channels that are mixed together
    // left output  = left  + center
    // right output = right + center
    public BlipBuffer center() {
        return bufs[CENTER];
    }

    public BlipBuffer left() {
        return bufs[LEFT];
    }

    public BlipBuffer right() {
        return bufs[RIGHT];
    }

    public void endFrame(int time) {
        for (int i = bufs.length; --i >= 0; ) {
            bufs[i].endFrame(time);
        }
    }

    public int samplesAvail() {
        return bufs[2].samplesAvail() << 1;
    }

    // Output is in stereo, so count must always be a multiple of 2
    //a 16 bit stereo sample requires 4 bytes
    public int readSamples(byte[] out, int start, int countStereo) {
        assert (countStereo & 1) == 0;

        final int avail = samplesAvail();
        if (countStereo > avail)
            countStereo = avail;

        if ((countStereo >>= 1) > 0) {
            // TODO: optimize for mono case

            // calculate center in place
            final int[] mono = bufs[CENTER].buf;
            {
                int accum = bufs[CENTER].accum.get();
                int i = 0;
                do {
                    mono[i] = (accum += mono[i] - (accum >> 9));
                }
                while (++i < countStereo);
                bufs[CENTER].accum.set(accum);
            }

            int pos = 0;
            // calculate left and right
            for (int ch = 2; --ch >= 0; ) {
                // add right and output
                final int[] buf = bufs[ch].buf;
                int accum = bufs[ch].accum.get();
                pos = (start + ch) << 1;
                int i = 0;
                do {
                    accum += buf[i] - (accum >> 9);
                    int ival = (accum + mono[i]) >> 15;
                    short sval = (short) ival;

                    // clamp to 16 bits
                    if (sval != ival)
                        sval = (short) BlipBufferHelper.clampToShort(ival);
                    setSigned16LE(sval, out, pos);
                    pos += 4;
                }
                while (++i < countStereo);
                bufs[ch].accum.set(accum);
            }
//            BlipHelper.printStereoData(name, out, start*2, pos, false);
            for (int i = bufs.length; --i >= 0; ) {
                bufs[i].removeSamples(countStereo);
            }
        }
        return countStereo << 1;
    }

    public int readSamples16Bit(int[] out, int start, int countStereo) {
        assert (countStereo & 1) == 0;

        final int avail = samplesAvail();
        if (countStereo > avail)
            countStereo = avail;

        if ((countStereo >>= 1) > 0) {
            // TODO: optimize for mono case

            // calculate center in place
            final int[] mono = bufs[CENTER].buf;
            {
                int accum = bufs[CENTER].accum.get();
                int i = 0;
                do {
                    mono[i] = (accum += mono[i] - (accum >> 9));
                }
                while (++i < countStereo);
                bufs[CENTER].accum.set(accum);
            }

            int pos = 0;
            // calculate left and right
            for (int ch = RIGHT; --ch >= 0; ) {
                // add right and output
                final int[] buf = bufs[ch].buf;
                int accum = bufs[ch].accum.get();
                pos = (start + ch) << 1;
                int i = 0;
                do {
                    accum += buf[i] - (accum >> 9);
                    int ival = (accum + mono[i]) >> 15;
                    short sval = (short) ival;

                    // clamp to 16 bits
                    if (sval != ival)
                        sval = (short) BlipBufferHelper.clampToShort(ival);
                    out[pos] = sval;
                    pos += 2;
                }
                while (++i < countStereo);
                bufs[ch].accum.set(accum);
            }
            for (int i = bufs.length; --i >= 0; ) {
                bufs[i].removeSamples(countStereo);
            }
        }
        return countStereo;
    }
}
