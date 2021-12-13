package sh2;

import omegadrive.util.FileLoader;
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

    private void init() {
        assert sh2m.toFile().exists();
        assert sh2s.toFile().exists();
        assert m68k.toFile().exists();

        biosData[CpuDeviceAccess.MASTER.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(sh2m));
        biosData[CpuDeviceAccess.SLAVE.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(sh2s));
        biosData[CpuDeviceAccess.M68K.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(m68k));
    }

    public ByteBuffer getBiosData(CpuDeviceAccess type) {
        return biosData[type.ordinal()];
    }
}
