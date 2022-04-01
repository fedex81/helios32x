package sh2;

import omegadrive.util.FileUtil;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class BiosHolder {

    private ByteBuffer[] biosData = new ByteBuffer[CpuDeviceAccess.values().length];
    private Path sh2m, sh2s, m68k;

    public BiosHolder(Path sh2m, Path sh2s, Path m68k) {
        this.sh2m = sh2m;
        this.sh2s = sh2s;
        this.m68k = m68k;
        init();
    }

    public BiosHolder(ByteBuffer[] biosData) {
        this.biosData = biosData;
    }

    private void init() {
        assert sh2m.toFile().exists();
        assert sh2s.toFile().exists();
        assert m68k.toFile().exists();

        biosData[CpuDeviceAccess.MASTER.ordinal()] = ByteBuffer.wrap(FileUtil.readFileSafe(sh2m));
        biosData[CpuDeviceAccess.SLAVE.ordinal()] = ByteBuffer.wrap(FileUtil.readFileSafe(sh2s));
        biosData[CpuDeviceAccess.M68K.ordinal()] = ByteBuffer.wrap(FileUtil.readFileSafe(m68k));

        assert biosData[CpuDeviceAccess.MASTER.ordinal()].capacity() > 0;
        assert biosData[CpuDeviceAccess.SLAVE.ordinal()].capacity() > 0;
        assert biosData[CpuDeviceAccess.M68K.ordinal()].capacity() > 0;
    }

    public ByteBuffer getBiosData(CpuDeviceAccess type) {
        return biosData[type.ordinal()];
    }
}
