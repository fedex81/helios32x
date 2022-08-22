package sh2.sh2;

import org.junit.jupiter.api.BeforeEach;
import sh2.*;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public abstract class Sh2BaseTest {

    protected Sh2Impl sh2;
    protected Sh2Context ctx;
    protected IMemory memory;

    @BeforeEach
    public void before() {
        memory = new Sh2Memory(new S32XMMREG(), ByteBuffer.allocate(0xFF), BiosHolder.NO_BIOS);
        sh2 = new Sh2Impl(memory);
        ctx = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        sh2.setCtx(ctx);
    }
}
