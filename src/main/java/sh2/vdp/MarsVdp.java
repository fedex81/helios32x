package sh2.vdp;

import omegadrive.Device;
import omegadrive.util.Size;
import omegadrive.util.Util;
import omegadrive.util.VideoMode;
import omegadrive.vdp.util.UpdatableViewer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xDict;

import java.awt.*;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;

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

    void write(int address, int value, Size size);

    int read(int address, Size size);

    boolean vdpRegWrite(S32xDict.RegSpecS32x regSpec, int reg, int value, Size size);

    void draw(MarsVdpContext context);

    void updateVideoMode(VideoMode videoMode);

    MarsVdpRenderContext getMarsVdpRenderContext();

    void updateDebugView(UpdatableViewer debugView);

    int[] doCompositeRendering(int[] mdData, MarsVdpRenderContext ctx);

    default void dumpMarsData() {
        throw new UnsupportedOperationException();
    }

    void setHBlank(boolean hBlankOn, int hen);

    void setVBlank(boolean vBlankOn);

    public enum VdpPriority {MD, S32X}

    public enum BitmapMode {
        BLANK, PACKED_PX, DIRECT_COL, RUN_LEN;

        public static BitmapMode[] vals = BitmapMode.values();
    }

    //NOTE, do not move or change, tests depend on it
    public class MarsVdpContext implements Serializable {
        private static final long serialVersionUID = -5808119960311023889L;

        public BitmapMode bitmapMode = BitmapMode.BLANK;
        public VdpPriority priority = VdpPriority.MD;
        public int screenShift = 0;
        public VideoMode videoMode = VideoMode.NTSCJ_H20_V18;
        public int fsLatch = 0, frameBufferDisplay = 0, frameBufferWritable = 1;
        public boolean hBlankOn, vBlankOn = true;
        public int hCount = 0;

        @Override
        public String toString() {
            return "MarsVdpContext{" +
                    "bitmapMode=" + bitmapMode +
                    ", priority=" + priority +
                    ", screenShift=" + screenShift +
                    ", videoMode=" + videoMode +
                    ", fsLatch=" + fsLatch +
                    ", frameBufferDisplay=" + frameBufferDisplay +
                    ", frameBufferWritable=" + frameBufferWritable +
                    ", hBlankOn=" + hBlankOn +
                    ", vBlankOn=" + vBlankOn +
                    ", hCount=" + hCount +
                    '}';
        }
    }

    //NOTE, do not move or change, tests depend on it
    public class MarsVdpRenderContext implements Serializable {
        private static final long serialVersionUID = 6079468834587022465L;

        public int[] screen;
        public MarsVdpContext vdpContext;
    }

    //NOTE, do not move or change, tests depend on it
    public class DebugMarsVdpRenderContext implements Serializable {
        private static final long serialVersionUID = 6600715540292231809L;

        public MarsVdpRenderContext renderContext;
        public short[] frameBuffer0;
        public short[] frameBuffer1;
        public short[] palette;
    }

    static void initBgrMapper() {
        for (int i = 0; i < bgr5toRgb8Mapper.length; i++) {
            int b = ((i >> 10) & 0x1F) << 3; //5 to 8 bits, multiply by 8
            int g = ((i >> 5) & 0x1F) << 3;
            int r = ((i >> 0) & 0x1F) << 3;
            bgr5toRgb8Mapper[i] = (r << 16) | (g << 8) | b;
        }
    }

    static void storeMarsData(MarsVdp.DebugMarsVdpRenderContext ctx) {
        try {
            Path f = Files.createTempFile("mvrc_", ".dat", new FileAttribute[0]);
            Files.write(f, Util.serializeObject(ctx), StandardOpenOption.WRITE);
            LOG.info("File written: {}", f.toAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}