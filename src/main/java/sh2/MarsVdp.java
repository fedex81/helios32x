package sh2;

import omegadrive.util.VideoMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static sh2.S32XMMREG.DRAM_SIZE;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class MarsVdp {

    private static final Logger LOG = LogManager.getLogger(MarsVdp.class.getSimpleName());

    static final int PANEL_TEXT_HEIGHT = 20;
    static final int PANEL_HEIGHT = 256 + PANEL_TEXT_HEIGHT;
    static final int PANEL_WIDTH = 320;
    static final Dimension layerDim = new Dimension(PANEL_WIDTH, PANEL_HEIGHT);
    static final int DIRECT_COLOR_LINES = 204; // ~(65536 - 256)/320
    static final int LINE_TABLE_WORDS = 256;
    static final int LINE_TABLE_BYTES = LINE_TABLE_WORDS << 1;

    private static final int[] bgr5toRgb8Mapper = new int[0x10000];
    static final int NUM_FB = 2;

    private int[] buffer;

    private ShortBuffer[] frameBuffersWord = new ShortBuffer[NUM_FB];
    private ShortBuffer colorPaletteWords;
    private VideoMode videoMode;
    private short[] fbDataWords = new short[(DRAM_SIZE - LINE_TABLE_BYTES) >> 1];
    private int[] lineTableWords = new int[LINE_TABLE_WORDS];

    private MarsVdpDebugView view;

    public static MarsVdp createInstance(ByteBuffer[] frameBuffers, ByteBuffer colorPalette) {
        MarsVdp v = new MarsVdp();
        v.colorPaletteWords = colorPalette.asShortBuffer();
        v.frameBuffersWord[0] = frameBuffers[0].asShortBuffer();
        v.frameBuffersWord[1] = frameBuffers[1].asShortBuffer();
        v.view = MarsVdpDebugView.createInstance();
        v.updateVideoMode(VideoMode.NTSCJ_H20_V18); //force an update later
        initBgrMapper();
        return v;
    }

    public void draw(VideoMode videoMode, S32XMMREG.BITMAP_MODE bitmap_mode, int num, int screenShift) {
        if (videoMode != this.videoMode) {
            updateVideoMode(videoMode);
        }
        switch (bitmap_mode) {
            case BLANK:
                Arrays.fill(buffer, 0, buffer.length, 0);
                break;
            case PACKED_PX:
                drawPackedPixel(videoMode, num, screenShift);
                break;
            case RUN_LEN:
                drawRunLen(videoMode, num, screenShift);
                break;
            case DIRECT_COL:
                drawDirectColor(videoMode, num, screenShift);
                break;
        }
        view.update(videoMode, num, buffer);
    }

    private void updateVideoMode(VideoMode videoMode) {
        if (videoMode.getDimension().equals(this.videoMode)) {
            return;
        }
        this.buffer = new int[videoMode.getDimension().width * videoMode.getDimension().height];
        LOG.info("Updating videoMode, {} -> {}", this.videoMode, videoMode);
        this.videoMode = videoMode;
    }

    //Mars Sample Program - Pharaoh
    private void drawDirectColor(VideoMode videoMode, int num, int screenShift) {
        final int w = videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[num];
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

    private void drawRunLen(VideoMode videoMode, int num, int screenShift) {
        final int h = videoMode.getDimension().height;
        final int w = videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[num];
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
    void drawPackedPixel(VideoMode videoMode, int num, int screenShift) {
        final ShortBuffer b = frameBuffersWord[num];
        final int[] imgData = buffer;

        b.position(0);
        for (int i = 0; i < lineTableWords.length; i++) {
            lineTableWords[i] = b.get() & 0xFFFF;
        }

        b.position(LINE_TABLE_WORDS);
        b.get(fbDataWords);

        int last = 0;
        final int h = videoMode.getDimension().height;
        final int w = videoMode.getDimension().width;

        for (int row = 0; row < h; row++) {
            //TODO why 64???
            final int linePos = lineTableWords[row] + screenShift + 64;
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

    private static void initBgrMapper() {
        for (int i = 0; i < bgr5toRgb8Mapper.length; i++) {
            int b = ((i >> 10) & 0x1F) << 3; //5 to 8 bits, multiply by 8
            int g = ((i >> 5) & 0x1F) << 3;
            int r = ((i >> 0) & 0x1F) << 3;
            bgr5toRgb8Mapper[i] = (r << 16) | (g << 8) | b;
        }
    }

    public int[] getScreenDataLinear() {
        return buffer;
    }
}
