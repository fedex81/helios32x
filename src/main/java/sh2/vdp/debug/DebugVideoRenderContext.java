package sh2.vdp.debug;

import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.vdp.MarsVdp;

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

    private static final transient Logger LOG = LogManager.getLogger(DebugVideoRenderContext.class.getSimpleName());

    private static final long serialVersionUID = -2583260195705611811L;
    public MarsVdp.MarsVdpContext marsVdpContext;
    public int[] mdData;
    public int[] s32xData;

    //dumpCompositeData, ie Md + 32x
    public static void dumpCompositeData(MarsVdp.MarsVdpRenderContext ctx, int[] mdData) {
        DebugVideoRenderContext vrc = new DebugVideoRenderContext();
        vrc.marsVdpContext = ctx.vdpContext;
        vrc.mdData = mdData;
        vrc.s32xData = ctx.screen;
        try {
            Path f = Files.createTempFile("vrc_", ".dat", new FileAttribute[0]);
            Files.write(f, Util.serializeObject(vrc), StandardOpenOption.WRITE);
            LOG.info("File written: {}", f.toAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static MarsVdp.MarsVdpRenderContext toMarsVdpRenderContext(DebugVideoRenderContext dvrc) {
        MarsVdp.MarsVdpRenderContext vrc = new MarsVdp.MarsVdpRenderContext();
        vrc.screen = dvrc.s32xData;
        vrc.vdpContext = dvrc.marsVdpContext;
        return vrc;
    }
}
