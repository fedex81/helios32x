package sh2;

import omegadrive.util.VideoMode;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static sh2.S32XMMREG.DRAM_SIZE;

/**
 * VdpDebugView
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2019
 */
public interface VdpDebugView {

    static VdpDebugView NO_OP = new VdpDebugView() {
        @Override
        public void update(VideoMode videoMode, S32XMMREG.BITMAP_MODE bitmap_mode, int fbSelect, int screenShift) {
        }
    };

    void update(VideoMode videoMode, S32XMMREG.BITMAP_MODE bitmap_mode, int fbSelect, int screenShift);

    public static VdpDebugView createInstance(ByteBuffer[] frameBuffers, ByteBuffer colorPalette) {
        return VdpDebugViewImpl.DEBUG_VIEWER_ENABLED ? new VdpDebugViewImpl(frameBuffers, colorPalette) : NO_OP;
    }

    class VdpDebugViewImpl implements VdpDebugView {

        protected static final boolean DEBUG_VIEWER_ENABLED;

        static {
            DEBUG_VIEWER_ENABLED =
                    Boolean.parseBoolean(System.getProperty("32x.show.vdp.debug.viewer", "true"));
            if (DEBUG_VIEWER_ENABLED) {
//            LOG.info("Debug viewer enabled");
            }
        }

        public static GraphicsDevice gd;
        static boolean isHeadless;

        static {
            isHeadless = GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance();
            if (!isHeadless) {
                gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            }
        }

        static final int PANEL_TEXT_HEIGHT = 20;
        static final int PANEL_HEIGHT = 512 + PANEL_TEXT_HEIGHT;
        static final int PANEL_WIDTH = 320;
        static final Dimension layerDim = new Dimension(PANEL_WIDTH, PANEL_HEIGHT);
        static final int DIRECT_COLOR_LINES = 204; // ~(65536 - 256)/320
        static final int LINE_TABLE_WORDS = 256;
        static final int LINE_TABLE_BYTES = LINE_TABLE_WORDS << 1;

        private static final int[] bgr5toRgb8Mapper = new int[0x10000];
        static final int NUM_FB = 2;

        private JFrame frame;
        private JPanel panel;
        private final ImageIcon[] imgIcons = new ImageIcon[NUM_FB];
        private final BufferedImage[] imageList = new BufferedImage[NUM_FB];
        private ShortBuffer[] frameBuffersWord = new ShortBuffer[NUM_FB];
        private ShortBuffer colorPaletteWords;
        private VideoMode videoMode = VideoMode.NTSCJ_H20_V18; //force an update later
        private short[] fbDataWords = new short[(DRAM_SIZE - LINE_TABLE_BYTES) >> 1];
        private int[] lineTableWords = new int[LINE_TABLE_WORDS];

        protected VdpDebugViewImpl(ByteBuffer[] frameBuffers, ByteBuffer colorPalette) {
            this.colorPaletteWords = colorPalette.asShortBuffer();
            this.frameBuffersWord[0] = frameBuffers[0].asShortBuffer();
            this.frameBuffersWord[1] = frameBuffers[1].asShortBuffer();
            init();
        }

        private void init() {
            imageList[0] = createImage(gd, layerDim);
            imageList[1] = createImage(gd, layerDim);
            SwingUtilities.invokeLater(() -> {
                this.frame = new JFrame();
                this.panel = new JPanel();
                JComponent p0 = createComponent(0);
                JComponent p1 = createComponent(1);
                panel.add(p0);
                panel.add(p1);
                panel.setSize(new Dimension(PANEL_WIDTH * 2, (int) (PANEL_HEIGHT * 1.5)));
                frame.add(panel);
                frame.setMinimumSize(panel.getSize());
                frame.setTitle("Vdp Debug Viewer");
                frame.pack();
                frame.setVisible(true);
            });
            initBgrMapper();
        }

        JComponent createComponent(int num) {
            imgIcons[num] = new ImageIcon(imageList[num]);
            JLabel lbl = new JLabel(imgIcons[num]);
            return lbl;
        }

        @Override
        public void update(VideoMode videoMode, S32XMMREG.BITMAP_MODE bitmap_mode, int dramBank, int screenShift) {
            if (videoMode != this.videoMode) {
                updateVideoMode(videoMode);
            }
//        System.out.println(System.currentTimeMillis() + " Update, draw: " + dramBank);
            draw(videoMode, bitmap_mode, dramBank, screenShift);
//            draw(videoMode, bitmap_mode, 1, screenShift);
            panel.repaint();
        }

        private void updateVideoMode(VideoMode videoMode) {
            imageList[0] = createImage(gd, videoMode.getDimension());
            imageList[1] = createImage(gd, videoMode.getDimension());
            imgIcons[0].setImage(imageList[0]);
            imgIcons[1].setImage(imageList[1]);
            System.out.println("Updating videoMode, " + this.videoMode + " -> " + videoMode);
            this.videoMode = videoMode;
        }

        void draw(VideoMode videoMode, S32XMMREG.BITMAP_MODE bitmap_mode, int num, int screenShift) {
            switch (bitmap_mode) {
                case BLANK:
                    int[] imgData = getPixels(imageList[num]);
                    Arrays.fill(imgData, 0, imgData.length, 0);
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
        }

        //Mars Sample Program - Pharaoh
        private void drawDirectColor(VideoMode videoMode, int num, int screenShift) {
            final int w = videoMode.getDimension().width;
            final ShortBuffer b = frameBuffersWord[num];
            final int[] imgData = getPixels(imageList[num]);
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
            final int[] imgData = getPixels(imageList[num]);
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
            final int[] imgData = getPixels(imageList[num]);

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

        private static int[] getPixels(BufferedImage img) {
            return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }

        public static BufferedImage createImage(GraphicsDevice gd, Dimension d) {
            BufferedImage bi = gd.getDefaultConfiguration().createCompatibleImage(d.width, d.height);
            if (bi.getType() != BufferedImage.TYPE_INT_RGB) {
                //mmh we need INT_RGB here
                bi = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
            }
            return bi;
        }
    }
}

