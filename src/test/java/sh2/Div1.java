package sh2;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static sh2.Sh2.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Div1 {

    private int Q, T, M;

    private Sh2Context ctx;

    @BeforeEach
    public void b4() {
        ctx = new Sh2Context(Sh2Util.Sh2Access.MASTER);
    }


    /**
     * Sequence taken from
     * Mars Sample Program - Gnu Sierra (Unknown) (SDK Build).32x
     */
    @Test
    public void testDiv1() {
        int n = 1, m = 5;
        final int[] R = ctx.registers;

        run(ctx, 0xffffffff, 0x11b4c, 0xFFD0B800);
        run(ctx, 0, 0x11b4c, 0x195000);

        run(ctx, 0, 0xf842, 0xe200);
        run(ctx, 0, 0xf842, 0x195000);
        run(ctx, 0, 0x12c00, 0);
        run(ctx, 0, 0x12c00, 0x3ab800);
        run(ctx, 0xffffffff, 0x103fc, 0xffe20000);
        run(ctx, 0xffffffff, 0x103fc, 0xffe7b000);
        run(ctx, 0, 0x11b6a, 304800);
        run(ctx, 0, 0x11b6a, 195000);
        run(ctx, 0xffffffff, 0x156e6, 0xffe15a00);
        run(ctx, 0, 0x156e6, 0x195000);
        run(ctx, 0, 0x15504, 0x1e0000);
        run(ctx, 0, 0x15504, 0x195000);
        run(ctx, 0xffffffff, 0x15504, 0x195000);
        run(ctx, 0xffffffff, 0x13d96, 0xffd0b800);
        run(ctx, 0, 0x13d96, 0xffe7b000);
        run(ctx, 0xffffffff, 0x1021a, 0x1fa600);
        run(ctx, 0, 0x1021a, 0xffe7b000);
        run(ctx, 0xffffffff, 0x13db4, 0x304800);
        run(ctx, 0, 0x13db4, 0xffe7b000);
        run(ctx, 0xffffffff, 0x160be, 0x1e00);
        run(ctx, 0, 0x160be, 0xffe7b000);
        run(ctx, 0, 0x12c00, 0);
        run(ctx, 0xffffffff, 0x12c00, 0xffc64800);

        int expR1 = 0xaf8f7400;
        int expR5 = 0x12c00;
        int expR4 = 0xFFC64800;
        int expQ = 0, expM = 0, expT = 1;

        Assertions.assertEquals(expR1, R[1]);
        Assertions.assertEquals(expR5, R[5]);
        Assertions.assertEquals(expR4, R[4]);
        Assertions.assertEquals(expM > 0, (ctx.SR & flagM) > 0);
        Assertions.assertEquals(expQ > 0, (ctx.SR & flagQ) > 0);
        Assertions.assertEquals(expT > 0, (ctx.SR & flagT) > 0);

        //switches to R0
        run(ctx, 5, 0, 0xffffffff, 0x6a6700, 0x96a0ffff);
        run(ctx, 5, 0, 0xffffffff, 0x618d00, 0x9f7affff);

        int expR0 = 0xcb0cf8ff;
        expR5 = 0x618d00;
        expR4 = 0x9F7AFFFF;
        expQ = 0;
        expM = 0;
        expT = 1;

        Assertions.assertEquals(expR1, R[1]);
        Assertions.assertEquals(expR5, R[5]);
        Assertions.assertEquals(expR4, R[4]);
        Assertions.assertEquals(expM > 0, (ctx.SR & flagM) > 0);
        Assertions.assertEquals(expQ > 0, (ctx.SR & flagQ) > 0);
        Assertions.assertEquals(expT > 0, (ctx.SR & flagT) > 0);
    }

    private void run(Sh2Context ctx, int val_n, int val_m, int val_r4) {
        run(ctx, -1, -1, val_n, val_m, val_r4);
    }

    private void run(Sh2Context ctx, int m, int n, int val_n, int val_m, int val_r4) {
        n = n < 0 ? 1 : n;
        m = m < 0 ? 5 : m;
        ctx.registers[n] = val_n;
        ctx.registers[m] = val_m;
        ctx.registers[4] = val_r4;
        DIV0S(ctx, m, n);

        for (int i = 0; i < 32; i++) {
            ROTL(ctx, 4);
            DIV1(ctx, n, m);
        }
    }

    private final void DIV0S(Sh2Context ctx, int m, int n) {
        final int[] R = ctx.registers;
        if ((R[n] & 0x80000000) == 0)
            Q = 0;
        else
            Q = 1;

        if ((R[m] & 0x80000000) == 0)
            M = 0;
        else
            M = 1;
        T = (char) (M != Q ? 1 : 0);
        ctx.SR &= ~(flagT | flagQ | flagM);
        ctx.SR |= (Q * flagQ | T * flagT | M * flagM);
        System.out.printf("####,div0s: r[%d]=%x >= r[%d]=%x, %d, %d, %d\n", n, R[n], m, R[m], M, Q, T);
    }

    private final void ROTL(Sh2Context ctx, int n) {
        final int[] R = ctx.registers;
        if ((R[n] & 0x80000000) != 0)
            T = 1;
        else
            T = 0;

        R[n] <<= 1;

        if (T != 0) {
            R[n] |= 0x1;
        } else {
            R[n] &= 0xFFFFFFFE;
        }
        ctx.SR &= ~(flagT);
        ctx.SR |= (T * flagT);
    }
}
