package sh2;

import omegadrive.util.FileLoader;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class BiosHolder {

    private ByteBuffer[] biosData = new ByteBuffer[Sh2Util.CpuDeviceAccess.values().length];
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

        biosData[Sh2Util.CpuDeviceAccess.MASTER.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(sh2m));
        biosData[Sh2Util.CpuDeviceAccess.SLAVE.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(sh2s));
        biosData[Sh2Util.CpuDeviceAccess.M68K.ordinal()] = ByteBuffer.wrap(FileLoader.readFileSafe(m68k));
    }

    public ByteBuffer getBiosData(Sh2Util.CpuDeviceAccess type) {
        return biosData[type.ordinal()];
    }
}
