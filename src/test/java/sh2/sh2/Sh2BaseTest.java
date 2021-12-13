package sh2.sh2;

import org.junit.jupiter.api.BeforeEach;
import sh2.S32xUtil;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public abstract class Sh2BaseTest {

    protected Sh2 sh2;
    protected Sh2Context ctx;

    @BeforeEach
    public void before() {
        sh2 = new Sh2(null, null);
        ctx = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        sh2.setCtx(ctx);
    }
}
