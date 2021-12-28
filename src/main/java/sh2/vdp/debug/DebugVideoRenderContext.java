package sh2.vdp.debug;

import omegadrive.util.Util;
import sh2.vdp.MarsVdp;

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
//NOTE, do not move or change, tests depend on it
public class DebugVideoRenderContext implements Serializable {

    private static final long serialVersionUID = 853059726237085082L;
    public MarsVdp.VdpPriority priority;
    public Dimension dim;
    public int[] mdData;
    public int[] s32xData;

    public static void dumpData(MarsVdp.MarsVdpRenderContext ctx, int[] mdData) {
        DebugVideoRenderContext vrc = new DebugVideoRenderContext();
        vrc.priority = ctx.vdpContext.priority;
        vrc.mdData = mdData;
        vrc.s32xData = ctx.screen;
        vrc.dim = ctx.vdpContext.videoMode.getDimension();
        try {
            Path f = Files.createTempFile("vrc_", ".dat", new FileAttribute[0]);
            Files.write(f, Util.serializeObject(vrc), StandardOpenOption.WRITE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
