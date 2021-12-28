package sh2.vdp;

import omegadrive.Device;
import omegadrive.util.VideoMode;
import omegadrive.vdp.util.UpdatableViewer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface MarsVdp extends Device {

    static final Logger LOG = LogManager.getLogger(MarsVdp.class.getSimpleName());

    static final int PANEL_TEXT_HEIGHT = 20;
    static final int PANEL_HEIGHT = 256 + PANEL_TEXT_HEIGHT;
    static final int PANEL_WIDTH = 320;
    static final Dimension layerDim = new Dimension(PANEL_WIDTH, PANEL_HEIGHT);
    static final int DIRECT_COLOR_LINES = 204; // ~(65536 - 256)/320
    static final int LINE_TABLE_WORDS = 256;
    static final int LINE_TABLE_BYTES = LINE_TABLE_WORDS << 1;

    static final int[] bgr5toRgb8Mapper = new int[0x10000];
    static final int NUM_FB = 2;

    void draw(MarsVdpContext context);

    void updateVideoMode(VideoMode videoMode);

    MarsVdpRenderContext getMarsVdpRenderContext();

    void updateDebugView(UpdatableViewer debugView);

    public enum VdpPriority {MD, S32X}

    public enum BitmapMode {
        BLANK, PACKED_PX, DIRECT_COL, RUN_LEN;

        public static BitmapMode[] vals = BitmapMode.values();
    }

    public class MarsVdpContext {
        public BitmapMode bitmapMode = BitmapMode.BLANK;
        public VdpPriority priority = VdpPriority.MD;
        public int screenShift = 0;
        public VideoMode videoMode = VideoMode.NTSCJ_H20_V18;
        public int fsLatch = 0, frameBufferDisplay = 0, frameBufferWritable = 1;
        public boolean hBlankOn, vBlankOn = true;
        public int hCount = 0;
    }

    public class MarsVdpRenderContext {
        public int[] screen;
        public MarsVdpContext vdpContext;
    }

    static void initBgrMapper() {
        for (int i = 0; i < bgr5toRgb8Mapper.length; i++) {
            int b = ((i >> 10) & 0x1F) << 3; //5 to 8 bits, multiply by 8
            int g = ((i >> 5) & 0x1F) << 3;
            int r = ((i >> 0) & 0x1F) << 3;
            bgr5toRgb8Mapper[i] = (r << 16) | (g << 8) | b;
        }
    }
}