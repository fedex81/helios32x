package sh2;

import omegadrive.util.ImageUtil;
import omegadrive.util.VideoMode;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import static sh2.MarsVdpDebugView.ImageType.*;

/**
 * VdpDebugView
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2019
 */
public interface MarsVdpDebugView {

    static MarsVdpDebugView NO_OP = new MarsVdpDebugView() {
        @Override
        public void update(VideoMode videoMode, int fbSelect, int[] buffer) {
        }
    };

    public enum ImageType {BUFF_0, BUFF_1, FULL}

    void update(VideoMode videoMode, int fbSelect, int[] buffer);

    public static MarsVdpDebugView createInstance() {
        return MarsVdpDebugViewImpl.DEBUG_VIEWER_ENABLED ? new MarsVdpDebugViewImpl() : NO_OP;
    }

    class MarsVdpDebugViewImpl implements MarsVdpDebugView {

        protected static final boolean DEBUG_VIEWER_ENABLED;

        static {
            DEBUG_VIEWER_ENABLED =
                    Boolean.parseBoolean(System.getProperty("32x.show.vdp.debug.viewer", "false"));
            if (DEBUG_VIEWER_ENABLED) {
//                LOG.info("Debug viewer enabled");
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

        private static final int PANEL_TEXT_HEIGHT = 20;
        private static final int PANEL_HEIGHT = 256 + PANEL_TEXT_HEIGHT;
        private static final int PANEL_WIDTH = 320;
        private static final Dimension layerDim = new Dimension(PANEL_WIDTH, PANEL_HEIGHT);

        private JFrame frame;
        private JPanel panel;
        private final ImageIcon[] imgIcons = new ImageIcon[ImageType.values().length];
        private final BufferedImage[] imageList = new BufferedImage[ImageType.values().length];
        private VideoMode videoMode = VideoMode.NTSCJ_H20_V18; //force an update later

        private void init() {
            imageList[BUFF_0.ordinal()] = ImageUtil.createImage(gd, layerDim);
            imageList[BUFF_1.ordinal()] = ImageUtil.createImage(gd, layerDim);
            imageList[FULL.ordinal()] = ImageUtil.createImage(gd, layerDim);
            SwingUtilities.invokeLater(() -> {
                this.frame = new JFrame();
                this.panel = new JPanel();
                JComponent p0 = createComponent(BUFF_0);
                JComponent p1 = createComponent(BUFF_1);
                JComponent p2 = createComponent(FULL);
                panel.add(p0);
                panel.add(p1);
                panel.add(p2);
                panel.setSize(new Dimension(PANEL_WIDTH * 2, (int) (PANEL_HEIGHT * 1.2)));
                frame.add(panel);
                frame.setMinimumSize(panel.getSize());
                frame.setTitle("32x Vdp Debug Viewer");
                frame.pack();
                frame.setVisible(true);
            });
        }

        protected MarsVdpDebugViewImpl() {
            init();
        }

        JComponent createComponent(ImageType type) {
            int num = type.ordinal();
            imgIcons[num] = new ImageIcon(imageList[num]);
            JPanel pnl = new JPanel();
            BoxLayout bl = new BoxLayout(pnl, BoxLayout.Y_AXIS);
            pnl.setLayout(bl);
            JLabel title = new JLabel(type.toString());
            JLabel lbl = new JLabel(imgIcons[num]);
            pnl.add(title);
            pnl.add(lbl);
            return pnl;
        }

        private void updateVideoMode(VideoMode videoMode) {
            if (videoMode.equals(this.videoMode)) {
                return;
            }
            imageList[0] = ImageUtil.createImage(gd, videoMode.getDimension());
            imageList[1] = ImageUtil.createImage(gd, videoMode.getDimension());
            imageList[2] = ImageUtil.createImage(gd, videoMode.getDimension());
            imgIcons[0].setImage(imageList[0]);
            imgIcons[1].setImage(imageList[1]);
            imgIcons[2].setImage(imageList[2]);
            this.videoMode = videoMode;
            frame.invalidate();
            frame.pack();
            frame.repaint();
        }

        @Override
        public void update(VideoMode videoMode, int dramBank, int[] rgb888) {
            if (videoMode != this.videoMode) {
                updateVideoMode(videoMode);
            }
            copyToImages(dramBank, rgb888);
            panel.repaint();
        }

        //copy the current front buffer to the FULL image
        private void copyToImages(int num, int[] rgb888) {
            int[] imgData = ImageUtil.getPixels(imageList[num]);
            int[] imgDataFull = ImageUtil.getPixels(imageList[FULL.ordinal()]);
            System.arraycopy(rgb888, 0, imgData, 0, rgb888.length);
            System.arraycopy(rgb888, 0, imgDataFull, 0, rgb888.length);
        }
    }
}

