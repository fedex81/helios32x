package sh2.sh2.drc;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32xUtil;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.sh2.prefetch.Sh2Prefetcher;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static omegadrive.util.Util.th;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2DrcOptimizer {

    private final static Logger LOG = LogHelper.getLogger(Ow2DrcOptimizer.class.getSimpleName());

    static class InstCtx {
        public int start, end;
    }

    private static final InstCtx instCtx = new InstCtx();

    private static final Predicate<Integer> isCmpTstOpcode = Sh2Debug.isTstOpcode.or(Sh2Debug.isCmpOpcode);

    public static class PollerCtx {
        public S32xUtil.CpuDeviceAccess cpu;
        public int pc, memoryTarget;
        public Size memTargetSize;
        public Sh2Prefetcher.Sh2Block block;
        public boolean isPolling;
    }

    public static final PollerCtx NO_POLLER = new PollerCtx();

    public static final Map<Integer, PollerCtx> map = new HashMap<>();

    /**
     * Doom
     * <p>
     * SLAVE hits: fffff, cycles: 11, tot: affff5
     * 0204e7ce	50d0	mov.l @(0, R13), R0
     * 0004e7d0	8800	cmp/eq H'00, R0
     * 0004e7d2	8920	bt H'0004e816
     * <p>
     * 02046c14	5133	mov.l @(3, R3), R1
     * 00046c16	3160	cmp/eq R6, R1
     * 00046c18	8b0d	bf H'00046c36
     * <p>
     * Brutal
     * 000001ca	c608	mov.l @(8, GBR), R0
     * 000001cc	3200	cmp/eq R0, R2
     * 000001ce	8bf7	bf H'000001c0
     * <p>
     * 06003a8c	c438	mov.b @(56, GBR), R0
     * 00003a8e	c880	tst H'80, R0
     * 00003a90	8b1e	bf H'00003ad0
     */
    public static void pollDetector(Sh2Prefetcher.Sh2Block block) {
        if (block.poller < 0 && block.prefetchWords.length == 3) {
            int w1 = block.prefetchWords[0];
            int w2 = block.prefetchWords[1];
            int w3 = block.prefetchWords[2];
            boolean poll = Sh2Debug.isMovOpcode.test(w1) && isCmpTstOpcode.test(w2) && Sh2Debug.isBranchOpcode.test(w3);
            block.poller = 0;
            if (poll) {
                LOG.info("{} Poll detected: {}\n{}", block.drcContext.cpu, th(block.prefetchPc), Sh2Instructions.toListOfInst(block));
                PollerCtx ctx = new PollerCtx();
                ctx.pc = block.prefetchPc;
                ctx.block = block;
                ctx.cpu = block.drcContext.cpu;
                if ((w1 & 0xF000) == 0x5000) {
                    ctx.memoryTarget = ((w1 & 0xF) << 2) + block.drcContext.sh2Ctx.registers[(w1 & 0xF0) >> 4];
                    ctx.memTargetSize = Size.LONG;
                } else if ((w1 & 0xFF00) == 0xC600) {
                    ctx.memoryTarget = ((w1 & 0xFF) << 2) + block.drcContext.sh2Ctx.GBR;
                    ctx.memTargetSize = Size.LONG;
                } else if ((w1 & 0xFF00) == 0xC400) {
                    ctx.memoryTarget = (w1 & 0xFF) + block.drcContext.sh2Ctx.GBR;
                    ctx.memTargetSize = Size.BYTE;
                }
                if (ctx.memoryTarget != 0) {
                    int region = ctx.memoryTarget >> 24;
                    switch (region) {
                        //SDRAM, COMM
                        case 0, 6, 0x20, 0x26 -> {
                            LOG.info("{} Poll detected: {}, memTarget: {}\n{}", block.drcContext.cpu, th(block.prefetchPc),
                                    th(ctx.memoryTarget), Sh2Instructions.toListOfInst(block));
                            ctx.isPolling = true;
                            block.poller = 1;
                            map.put(ctx.pc, ctx);
                        }
                    }
                }
            }
        } else {
//                LOG.info("{} Not a poller: {}\n{}", block.drcContext.cpu, th(block.prefetchPc), Sh2Instructions.toListOfInst(block));
        }
    }

    public static InstCtx optimizeMaybe(Sh2Prefetcher.Sh2Block block, Sh2Prefetch.BytecodeContext ctx) {
        instCtx.start = 0;
        instCtx.end = block.prefetchWords.length;
        /**
         * Space Harrier
         *  R4 = R3 = 0 95%
         *  can be simplified:
         *
         *  R1 = R0 = 0
         *  000002c2	4710	dt R7
         *  000002c4	8fda	bf/s H'0000027c
         *  000002c6	7e20	add H'20, R14
         *
         * Sh2Prefetcher$Sh2Block: SLAVE block, hitCount: 9fffff
         *  060002b0	e100	mov H'00, R1
         *  000002b2	e000	mov H'00, R0
         *  000002b4	201f	muls.w R1, R0
         *  000002b6	4129	shlr16 R1
         *  000002b8	021a	sts MACL, R2   //MACL,R2 always 0
         *  000002ba	201f	muls.w R1, R0
         *  000002bc	342c	add R2, R4     //R4 never changes
         *  000002be	021a	sts MACL, R2
         *  000002c0	332c	add R2, R3     //R3 never changes
         *  000002c2	4710	dt R7          //SR |= 1 when R7 = 1
         *  000002c4	8fda	bf/s H'0000027c
         *  000002c6	7e20	add H'20, R14
         */
        if (block.prefetchPc == 0x060002b0 && Arrays.hashCode(block.prefetchWords) == -888790968) {
            Ow2Sh2Bytecode.storeToReg(ctx, 0, 0, Size.BYTE); //r0 = 0
            Ow2Sh2Bytecode.storeToReg(ctx, 1, 0, Size.BYTE); //r1 = 0
            instCtx.start = 9; //start from: dt r7
            LOG.info("{} Optimizing at PC: {}, cyclesConsumed: {}\n{}", block.drcContext.cpu,
                    th(block.prefetchPc), block.cyclesConsumed,
                    Sh2Instructions.toListOfInst(block));
        }
        return instCtx;
    }


}
