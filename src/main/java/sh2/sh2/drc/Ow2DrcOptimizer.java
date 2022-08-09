package sh2.sh2.drc;

import com.google.common.collect.ImmutableMap;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32xUtil;
import sh2.dict.S32xDict;
import sh2.dict.S32xDict.S32xRegType;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Instructions;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.sh2.Sh2Disassembler.NOP;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 * TODO busyLoop is effectively polling an interrupt change, needs to trigger an event
 */
public class Ow2DrcOptimizer {

    private final static Logger LOG = LogHelper.getLogger(Ow2DrcOptimizer.class.getSimpleName());

    static class InstCtx {
        public int start, end;
    }

    private static final InstCtx instCtx = new InstCtx();

    private static final Predicate<Integer> isCmpTstOpcode = Sh2Debug.isTstOpcode.or(Sh2Debug.isCmpOpcode);

    public static final Map<S32xRegType, PollType> ptMap = ImmutableMap.of(
            S32xRegType.NONE, NONE,
            S32xRegType.COMM, COMM
            //TODO needs to notify when a reg changes and it is NOT triggered by a memory write
//            S32xRegType.DMA, DMAC,
//            S32xRegType.PWM, PWM,
//            S32xRegType.VDP, VDP
    );

    public enum PollState {NO_POLL, ACTIVE_POLL}

    public enum PollType {
        UNKNOWN,
        NONE,
        BUSY_LOOP,
        SDRAM,
        COMM,
        DMAC,
        PWM,
        VDP;
    }

    public static class PollerCtx {
        public S32xUtil.CpuDeviceAccess cpu;
        public int pc, memoryTarget, branchDest;
        public Size memTargetSize;
        public Sh2Block block = Sh2Block.INVALID_BLOCK;
        public PollState pollState = PollState.NO_POLL;

        public boolean isPollingActive() {
//            assert isPollingBlock();
            return pollState != PollState.NO_POLL;
        }

        public boolean isPollingBlock() {
            return block.pollType.ordinal() > NONE.ordinal();
        }

        public void stopPolling() {
            if (isPollingActive()) {
//                LOG.info("{} Stopping {} poll at PC {}, on address: {}", cpu, block.pollType, th(pc), th(memoryTarget));
            }
//            block = Sh2Block.INVALID_BLOCK;
            pollState = PollState.NO_POLL;
//            memoryTarget = 0;
        }
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
     *
     * Space Harrier
     */
    public static void pollDetector(Sh2Block block) {
        if (false) {
            block.pollType = NONE;
            return;
        }
        //TODO
        if (block.pollType == UNKNOWN) {
            if (block.prefetchLenWords == 3) {
                checkPollLen3(block);
            } else if (block.prefetchLenWords == 2) {
                checkPollLen2(block);
            }
            if (block.pollType == UNKNOWN) {
                busyLoopDetector(block);
            }
        }
        //mark this block as processed
        if (block.pollType == UNKNOWN) {
            block.pollType = NONE;
        }
    }

    /**
     * Space Harrier
     * S 0600016e	0009	nop
     * S 06000170	affd	bra H'0600016e
     * S 06000172	0009	nop
     */
    public static void busyLoopDetector(Sh2Block block) {
        assert block.pollType == UNKNOWN;
        boolean busyLoop = false;
        if (block.prefetchLenWords == 3) {
            int w1 = block.prefetchWords[0];
            int w2 = block.prefetchWords[1];
            int w3 = block.prefetchWords[2];
            busyLoop = w1 == NOP && w2 == 0xaffd && w3 == NOP;
        } else if (block.prefetchLenWords == 2) {
            int w1 = block.prefetchWords[0];
            int w2 = block.prefetchWords[1];
            busyLoop = w1 == 0xaffe && w2 == NOP;
        }
        if (busyLoop) {
            LOG.info("{} BusyLoop detected: {}\n{}", block.drcContext.cpu, th(block.prefetchPc),
                    Sh2Instructions.toListOfInst(block));
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            block.pollType = BUSY_LOOP;
            map.put(ctx.pc, ctx);
        }
    }


    /**
     * S 0603fc24	e000	mov H'00, R0 [NEW]
     * S 0603fc26	d103	mov.l @(H'0603fc34), R1 [NEW]
     * S 0603fc28	2102	mov.l R0, @R1 [NEW]
     * S 0603fc2a	6012	mov.l @R1, R0 [NEW]
     * S 0603fc2c	8800	cmp/eq H'00, R0 [NEW]
     * S 0603fc2e	89fc	bt H'0603fc2a [NEW]
     *
     * @param block
     */
    private static void checkPollLen3(Sh2Block block) {
        int blen = block.prefetchLenWords;
        int w1 = block.prefetchWords[blen - 3];
        int w2 = block.prefetchWords[blen - 2];
        int w3 = block.prefetchWords[blen - 1];

        boolean poll = Sh2Debug.isMovOpcode.test(w1) && isCmpTstOpcode.test(w2) && Sh2Debug.isBranchOpcode.test(w3);
        if (poll) {
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            int jumpPc = ctx.pc + ((blen - 1) << 1);
            setTargetInfoMov(ctx, w1, w3, jumpPc);
            addPollMaybe(ctx, block);
        }
    }

    private static void checkPollLen2(Sh2Block block) {
        int blen = block.prefetchLenWords;
        int w1 = block.prefetchWords[blen - 2];
        int w2 = block.prefetchWords[blen - 1];

        boolean poll = isCmpTstOpcode.test(w1) && Sh2Debug.isBranchOpcode.test(w2);
        if (poll) {
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            int jumpPc = ctx.pc + ((blen - 1) << 1);
            setTargetInfoCmp(ctx, w1, w2, jumpPc);
            addPollMaybe(ctx, block);
        }
    }

    private static void addPollMaybe(PollerCtx ctx, Sh2Block block) {
        if (ctx.memoryTarget != 0 && ctx.branchDest == ctx.pc) {
            block.pollType = getAccessType(ctx, ctx.memoryTarget);
            if (block.pollType != UNKNOWN) {
                map.put(ctx.pc, ctx);
            }
        }
        LOG.info("{} Poll {} at PC {}: {} {}\n{}", block.drcContext.cpu,
                block.pollType == UNKNOWN ? "ignored" : "detected", th(block.prefetchPc),
                th(ctx.memoryTarget), block.pollType,
                Sh2Instructions.toListOfInst(block));
    }

    private static void setTargetInfoCmp(PollerCtx ctx, int cmp, int jmp, int jmpPc) {
        final Sh2Context sh2Ctx = ctx.block.drcContext.sh2Ctx;
        final int[] r = sh2Ctx.registers;
        if ((cmp & 0xFF00) == 0xCC00) {
            //TST.B #imm,@(R0,GBR) 11001100iiiiiiii     (R0 + GBR) & imm;if the result is 0, 1→T
            ctx.memoryTarget = r[0] + sh2Ctx.GBR;
        } else if (cmp == NOP) {
            //TODO busy loop
        }
        if ((jmp & 0xF000) == 0x8000) { //BT, BF, BTS, BFS
            int d = (byte) (jmp & 0xFF) << 1;
            ctx.branchDest = jmpPc + d + 4;
        }
    }

    private static void setTargetInfoMov(PollerCtx ctx, int mov, int jmp, int jmpPc) {
        final Sh2Context sh2Ctx = ctx.block.drcContext.sh2Ctx;
        final int[] r = sh2Ctx.registers;
        if ((mov & 0xF000) == 0x5000) {
            //MOV.L@(disp,Rm),  Rn(disp × 4 + Rm) → Rn    0101nnnnmmmmdddd
            ctx.memoryTarget = ((mov & 0xF) << 2) + r[(mov & 0xF0) >> 4];
            ctx.memTargetSize = Size.LONG;
        } else if (((mov & 0xF000) == 0xC000) && ((((mov >> 8) & 0xF) == 4) || (((mov >> 8) & 0xF) == 5) || (((mov >> 8) & 0xF) == 6))) {
            //mov.x @(disp, GBR), R0
            //MOV.B@(disp,GBR),     R0(disp + GBR) → sign extension → R0    11000100dddddddd
            ctx.memTargetSize = Size.vals[(mov >> 10) & 0xF];
            ctx.memoryTarget = ((mov & 0xFF) << ctx.memTargetSize.ordinal()) + sh2Ctx.GBR;
        } else if (((mov & 0xF000) == 0x6000) && ((mov & 0xF) < 3)) {
            //MOVXL, MOV.X @Rm,Rn
            ctx.memoryTarget = r[(mov & 0xF0) >> 4];
            ctx.memTargetSize = Size.vals[mov & 0xF];
        } else if (((mov & 0xF000) == 0x8000) && (((mov & 0xF00) == 0x400) || ((mov & 0xF00) == 0x500))) {
            //MOVBL4, MOV.B @(disp,Rm),R0
            //MOVWL4, MOV.W @(disp,Rm),R0
            ctx.memoryTarget = r[(mov & 0xF0) >> 4] + (mov & 0xF);
            ctx.memTargetSize = Size.vals[(mov >> 8) & 1];
        }
        if ((jmp & 0xF000) == 0x8000) { //BT, BF, BTS, BFS
            int d = (byte) (jmp & 0xFF) << 1;
            ctx.branchDest = jmpPc + d + 4;
        }
    }


    public static PollType getAccessType(PollerCtx ctx, int address) {
        switch (address >> 24) {
            case 6:
            case 0x26:
                return SDRAM;
            case 0:
            case 0x20:
                PollType pt = ptMap.get(S32xDict.getRegSpec(MASTER, address).deviceType);
                assert pt != null : th(address);
                return pt == null ? UNKNOWN : pt;
            default:
                LOG.error("Unexpected access type for polling: {}", th(address));
                return UNKNOWN;
        }
    }

    public static InstCtx optimizeMaybe(Sh2Block block, Sh2Prefetch.BytecodeContext ctx) {
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
