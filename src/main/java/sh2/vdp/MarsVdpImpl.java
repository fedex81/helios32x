package sh2.vdp;

import omegadrive.util.VideoMode;
import omegadrive.vdp.util.UpdatableViewer;
import omegadrive.vdp.util.VdpDebugView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.vdp.debug.MarsVdpDebugView;

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
    private short[] fbDataWords = new short[DRAM_SIZE >> 1];
    private int[] lineTableWords = new int[LINE_TABLE_WORDS];

    private MarsVdpDebugView view;
    private MarsVdpContext latestContext;
    private MarsVdpRenderContext renderContext;

    private boolean wasBlankScreen = false;

    static {
        MarsVdp.initBgrMapper();
    }

    public static MarsVdp createInstance(MarsVdpContext vdpContext, ByteBuffer[] frameBuffers, ByteBuffer colorPalette) {
        return createInstance(vdpContext, frameBuffers[0].asShortBuffer(), frameBuffers[1].asShortBuffer(),
                colorPalette.asShortBuffer());
    }

    public static MarsVdp createInstance(MarsVdpContext vdpContext, ShortBuffer frameBuffer0, ShortBuffer frameBuffer1, ShortBuffer colorPalette) {
        MarsVdpImpl v = new MarsVdpImpl();
        v.colorPaletteWords = colorPalette;
        v.frameBuffersWord[0] = frameBuffer0;
        v.frameBuffersWord[1] = frameBuffer1;
        v.view = MarsVdpDebugView.createInstance();
        v.latestContext = vdpContext;
        v.renderContext = new MarsVdpRenderContext();
        v.renderContext.screen = v.buffer;
        v.renderContext.vdpContext = vdpContext;
        v.updateVideoModeInternal(vdpContext.videoMode);
        return v;
    }

    @Override
    public void draw(MarsVdpContext context) {
        switch (context.bitmapMode) {
            case BLANK:
                drawBlank();
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

    private void drawBlank() {
        if (wasBlankScreen) {
            return;
        }
        Arrays.fill(buffer, 0, buffer.length, 0);
        wasBlankScreen = true;
    }

    //Mars Sample Program - Pharaoh
    //space harrier intro screen
    private void drawDirectColor(MarsVdpContext context) {
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);
        b.position(0);
        b.get(fbDataWords);
        final short[] fb = fbDataWords;
//        final int centerDcHalfShift = w * ((256 - context.videoMode.getDimension().height) >> 1);

        for (int row = 0; row < DIRECT_COLOR_LINES; row++) {
            final int linePos = lineTableWords[row] + context.screenShift;
            final int fbBasePos = row * w;
            for (int col = 0; col < w; col++) {
                imgData[fbBasePos + col] = getDirectColorWithPriority(fb[linePos + col] & 0xFFFF);
            }
        }
        wasBlankScreen = false;
    }

    //space harrier sega intro
    private void drawRunLen(MarsVdpContext context) {
        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);
        b.position(0);
        b.get(fbDataWords);

        for (int row = 0; row < h; row++) {
            int col = 0;
            final int basePos = row * w;
            final int linePos = lineTableWords[row];
            int nextWord = linePos;
            if (basePos >= fbDataWords.length) {
                break;
            }
            do {
                int rl = fbDataWords[nextWord++];
                int dotColorIdx = rl & 0xFF;
                int dotLen = ((rl & 0xFF00) >> 8) + 1;
                int nextLimit = col + dotLen;
                int color = getColorWithPriority(dotColorIdx);
                for (; col < nextLimit; col++) {
                    imgData[basePos + col] = color;
                }
            } while (col < w);
        }
        wasBlankScreen = false;
    }

    //32X Sample Program - Celtic - PWM Test
    void drawPackedPixel(MarsVdpContext context) {
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);

        b.position(0);
        b.get(fbDataWords);

        int last = 0;
        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;

        for (int row = 0; row < h; row++) {
            final int linePos = lineTableWords[row] + context.screenShift;
            final int basePos = row * w;
            for (int col = 0, wordOffset = 0; col < w; col += 2, wordOffset++) {
                final int palWordIdx1 = (fbDataWords[linePos + wordOffset] >> 8) & 0xFF;
                final int palWordIdx2 = fbDataWords[linePos + wordOffset] & 0xFF;
                imgData[basePos + col] = getColorWithPriority(palWordIdx1);
                imgData[basePos + col + 1] = getColorWithPriority(palWordIdx2);
                last = basePos + col + 1;
            }
        }
        wasBlankScreen = false;
    }

    private void populateLineTable(final ShortBuffer b) {
        b.position(0);
        for (int i = 0; i < lineTableWords.length; i++) {
            lineTableWords[i] = b.get() & 0xFFFF;
        }
    }

    //NOTE: encodes priority as the LSB (bit) of the word
    private int getColorWithPriority(int palWordIdx) {
        int palValue = colorPaletteWords.get(palWordIdx) & 0xFFFF;
        return getDirectColorWithPriority(palValue);
    }

    private int getDirectColorWithPriority(int palValue) {
        int prio = (palValue >> 15) & 1;
        int color = bgr5toRgb8Mapper[palValue];
        color = (color & ~1) | prio;
        return color;
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
        renderContext.screen = buffer;
        LOG.info("Updating videoMode, {} -> {}", latestContext.videoMode, videoMode);
    }

    @Override
    public MarsVdpRenderContext getMarsVdpRenderContext() {
        return renderContext;
    }

    @Override
    public void updateDebugView(UpdatableViewer debugView) {
        if (debugView instanceof VdpDebugView) {
            ((VdpDebugView) debugView).setAdditionalPanel(view.getPanel());
        }
    }

    @Override
    public void dumpMarsData() {
        DebugMarsVdpRenderContext d = new DebugMarsVdpRenderContext();
        d.renderContext = getMarsVdpRenderContext();
        frameBuffersWord[0].position(0);
        frameBuffersWord[1].position(0);
        d.frameBuffer0 = new short[frameBuffersWord[0].capacity()];
        d.frameBuffer1 = new short[frameBuffersWord[1].capacity()];
        frameBuffersWord[0].get(d.frameBuffer0);
        frameBuffersWord[1].get(d.frameBuffer1);

        colorPaletteWords.position(0);
        d.palette = new short[colorPaletteWords.capacity()];
        colorPaletteWords.get(d.palette);
        //NOTE needs to redraw as the buffer and the context might be out of sync
        draw(d.renderContext.vdpContext);
        d.renderContext.screen = buffer;
        MarsVdp.storeMarsData(d);
    }

    @Override
    public void reset() {
        view.reset();
    }
}
