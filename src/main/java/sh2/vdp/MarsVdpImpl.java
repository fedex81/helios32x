package sh2.vdp;

import omegadrive.util.VideoMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static sh2.S32XMMREG.DRAM_SIZE;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsVdpImpl implements MarsVdp {

    private static final Logger LOG = LogManager.getLogger(MarsVdpImpl.class.getSimpleName());

    private int[] buffer;

    private ShortBuffer[] frameBuffersWord = new ShortBuffer[NUM_FB];
    private ShortBuffer colorPaletteWords;
    private short[] fbDataWords = new short[(DRAM_SIZE - LINE_TABLE_BYTES) >> 1];
    private int[] lineTableWords = new int[LINE_TABLE_WORDS];

    private MarsVdpDebugView view;
    private MarsVdpContext latestContext;

    static {
        MarsVdp.initBgrMapper();
    }

    public static MarsVdp createInstance(MarsVdpContext vdpContext, ByteBuffer[] frameBuffers, ByteBuffer colorPalette) {
        MarsVdpImpl v = new MarsVdpImpl();
        v.colorPaletteWords = colorPalette.asShortBuffer();
        v.frameBuffersWord[0] = frameBuffers[0].asShortBuffer();
        v.frameBuffersWord[1] = frameBuffers[1].asShortBuffer();
        v.view = MarsVdpDebugView.createInstance();
        v.latestContext = vdpContext;
        v.updateVideoModeInternal(vdpContext.videoMode);
        return v;
    }

    @Override
    public void draw(MarsVdpContext context) {
        switch (context.bitmapMode) {
            case BLANK:
                Arrays.fill(buffer, 0, buffer.length, 0);
                break;
            case PACKED_PX:
                drawPackedPixel(context);
                break;
            case RUN_LEN:
                drawRunLen(context);
                break;
            case DIRECT_COL:
                drawDirectColor(context);
                break;
        }
        view.update(context, buffer);
        latestContext = context;
    }

    //Mars Sample Program - Pharaoh
    private void drawDirectColor(MarsVdpContext context) {
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        b.position(LINE_TABLE_WORDS);
        b.get(fbDataWords);
        final short[] fb = fbDataWords;

        int last = 0;
        for (int row = 0; row < DIRECT_COLOR_LINES; row++) {
            final int basePos = row * w;
            for (int col = 0; col < w; col++) {
                int color555 = fbDataWords[basePos + col];
                imgData[basePos + col] = bgr5toRgb8Mapper[color555 & 0xFFFF];
                last = basePos + col;
            }
        }
    }

    private void drawRunLen(MarsVdpContext context) {
        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        b.position(LINE_TABLE_WORDS);
        b.get(fbDataWords);

        int nextWord = 0;
        for (int row = 0; row < h; row++) {
            int col = 0;
            final int basePos = row * w;
            if (basePos >= fbDataWords.length) {
                break;
            }
            do {
                int rl = fbDataWords[nextWord++];
                int dotColorIdx = rl & 0xFF;
                int dotLen = ((rl & 0xFF00) >> 8) + 1;
                int nextLimit = col + dotLen;
                int color = bgr5toRgb8Mapper[colorPaletteWords.get(dotColorIdx) & 0xFFFF];
                for (; col < nextLimit; col++) {
                    imgData[basePos + col] = color;
                }
            } while (col < w);
        }
    }

    //32X Sample Program - Celtic - PWM Test
    void drawPackedPixel(MarsVdpContext context) {
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;

        b.position(0);
        for (int i = 0; i < lineTableWords.length; i++) {
            lineTableWords[i] = b.get() & 0xFFFF;
        }

        b.position(LINE_TABLE_WORDS);
        b.get(fbDataWords);

        int last = 0;
        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;

        for (int row = 0; row < h; row++) {
            //TODO why 64???
            final int linePos = lineTableWords[row] + context.screenShift + 64;
            final int basePos = row * w;
            for (int col = 0, wordOffset = 0; col < w; col += 2, wordOffset++) {
                final int palWordIdx1 = (fbDataWords[linePos + wordOffset] >> 8) & 0xFF;
                final int palWordIdx2 = fbDataWords[linePos + wordOffset] & 0xFF;
                imgData[basePos + col] = bgr5toRgb8Mapper[colorPaletteWords.get(palWordIdx1) & 0xFFFF];
                imgData[basePos + col + 1] = bgr5toRgb8Mapper[colorPaletteWords.get(palWordIdx2) & 0xFFFF];
                last = basePos + col + 1;
            }
        }
    }

    @Override
    public void updateVideoMode(VideoMode videoMode) {
        if (videoMode.equals(latestContext.videoMode)) {
            return;
        }
        updateVideoModeInternal(videoMode);
        latestContext.videoMode = videoMode;
    }

    private void updateVideoModeInternal(VideoMode videoMode) {
        this.buffer = new int[videoMode.getDimension().width * videoMode.getDimension().height];
        LOG.info("Updating videoMode, {} -> {}", latestContext.videoMode, videoMode);
    }

    @Override
    public int[] getScreenDataLinear() {
        return buffer;
    }

    @Override
    public boolean isBlank() {
        return latestContext.bitmapMode == BitmapMode.BLANK;
    }
}
