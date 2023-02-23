package s32x.util.blipbuffer;

import static s32x.pwm.PwmUtil.setSigned16LE;

/**
 * Federico Berti
 * <p>
 * Copyright 2023
 */
public class BlipBufferHelper {

    /**
     * BlipBuffer stores 16 bit mono samples, convert to byte[] format: 16 bit stereo
     */
    public static int readSamples16bitStereo(BlipBuffer blipBuffer, byte[] out, int pos, int countMono) {
        final int availMonoSamples = blipBuffer.samplesAvail();
        if (countMono > availMonoSamples)
            countMono = availMonoSamples;

        if (countMono > 0) {
            final int[] deltaBuf = blipBuffer.buf;
            // Integrate
            int accum = blipBuffer.accum.get();
            int i = 0;
            do {
                accum += deltaBuf[i] - (accum >> 9);
                int s = accum >> 15;

                // clamp to 16 bits
                if ((short) s != s)
                    s = clampToShort(s);

                setSigned16LE((short) s, out, pos);
                setSigned16LE((short) s, out, pos + 2);
                pos += 4;
            }
            while (++i < countMono);
            blipBuffer.accum.set(accum);
            blipBuffer.removeSamples(countMono);
        }
        return countMono;
    }

    public static int readSamples16bitStereo(BlipBuffer blipBuffer, int[] out, int pos, int countMono) {
        final int availMonoSamples = blipBuffer.samplesAvail();
        if (countMono > availMonoSamples)
            countMono = availMonoSamples;

        if (countMono > 0) {
            // Integrate
            final int[] buf = blipBuffer.buf;
            int accum = blipBuffer.accum.get();
            int i = 0;
            do {
                accum += buf[i] - (accum >> 9);
                int s = accum >> 15;

                // clamp to 16 bits
                if ((short) s != s)
                    s = clampToShort(s);

                out[pos] = s; //left
                out[pos + 1] = s; //right
                pos += 2;
            }
            while (++i < countMono);
            blipBuffer.accum.set(accum);

            blipBuffer.removeSamples(countMono);
        }
        return countMono;
    }

    public static int readSamples8bit(BlipBuffer blipBuffer, byte[] out, int pos, int count) {
        final int avail = blipBuffer.samplesAvail();
        if (count > avail)
            count = avail;

        if (count > 0) {
            // Integrate
            final int[] buf = blipBuffer.buf;
            int accum = blipBuffer.accum.get();
            pos <<= 1;
            int i = 0;
            do {
                accum += buf[i] - (accum >> 9);
                int s = accum >> 15;
                if ((byte) s != s) {
                    s = clampToByte(s);
//                    System.out.println(s + "->" + val);
                }
                out[pos++] = (byte) s;
            }
            while (++i < count);
            blipBuffer.accum.set(accum);

            blipBuffer.removeSamples(count);
        }
        return count;
    }

    // Reads at most count samples into out at offset pos*2 (2 bytes per sample)
    // and returns number of samples actually read.
    public int readSamples(BlipBuffer blipBuffer, byte[] out, int pos, int count) {
        final int avail = blipBuffer.samplesAvail();
        if (count > avail)
            count = avail;

        if (count > 0) {
            // Integrate
            final int[] buf = blipBuffer.buf;
            int accum = blipBuffer.accum.get();
            pos <<= 1;
            int i = 0;
            do {
                accum += buf[i] - (accum >> 9);
                int s = accum >> 15;

                // clamp to 16 bits
                if ((short) s != s)
                    s = clampToShort(s);

                // write as little-endian
                out[pos] = (byte) (s >> 8);
                out[pos + 1] = (byte) s;
                pos += 2;
            }
            while (++i < count);
            blipBuffer.accum.set(accum);

            blipBuffer.removeSamples(count);
        }
        return count;
    }

    public static int clampToShort(int value) {
        return (value >> 31) ^ Short.MAX_VALUE;
    }

    public static int clampToByte(int value) {
        return (value >> 31) ^ Byte.MAX_VALUE;
    }

    public static void main(String[] args) {
        for (int i = Integer.MIN_VALUE; i < Integer.MAX_VALUE; i++) {
            if ((short) i != i) {
                int s = clampToShort(i);
//                System.out.println(th(i) + ", " + th(s));
                assert i < 0 ? s == Short.MIN_VALUE : s == Short.MAX_VALUE;
            }
            if ((byte) i != i) {
                int s = clampToByte(i);
                assert i < 0 ? s == Byte.MIN_VALUE : s == Byte.MAX_VALUE;
            }
        }
    }
}
