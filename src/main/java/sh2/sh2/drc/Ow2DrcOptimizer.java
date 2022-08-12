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
import java.util.StringJoiner;
import java.util.function.Predicate;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.sh2.Sh2Debug.isBranchOpcode;
import static sh2.sh2.Sh2Debug.isMovOpcode;
import static sh2.sh2.Sh2Disassembler.NOP;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2DrcOptimizer {

    private final static Logger LOG = LogHelper.getLogger(Ow2DrcOptimizer.class.getSimpleName());
    private static final Predicate<Integer> isCmpTstOpcode = Sh2Debug.isTstOpcode.or(Sh2Debug.isCmpOpcode);

    public static final Predicate<int[]> isPollSequenceLen3 = w ->
            isMovOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]) && isBranchOpcode.test(w[2]);

    public static final Predicate<int[]> isPollSequenceLen2 = w ->
            (isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1])) ||
                    (isBranchOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]));

    public static final Predicate<int[]> isBusyLoopLen3 = w ->
            w[0] == NOP && (w[1] == 0xaffd || w[1] == 0xaffe) && w[2] == NOP;

    public static final Predicate<int[]> isBusyLoopLen2 = w ->
            w[0] == 0xaffe && w[1] == NOP;


    public static final Map<S32xRegType, PollType> ptMap = ImmutableMap.of(
            //TODO needs to notify when a reg changes and it is NOT triggered by a memory write
            //TODO check VDP
//            S32xRegType.DMA, DMAC,
//            S32xRegType.PWM, PWM,
            S32xRegType.VDP, VDP,
            S32xRegType.NONE, NONE,
            S32xRegType.COMM, COMM
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

    public enum PollCancelType {
        NONE,
        INT,
        SYS,
        SDRAM,
        COMM,
        DMAC,
        PWM,
        VDP;
    }

    private static final Predicate<int[]> mov_cmp_jmp_Pred = w ->
            isMovOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]) && isBranchOpcode.test(w[2]);
    private static final Predicate<int[]> cmp_jmpds_mov_Pred = w ->
            isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1]) && isMovOpcode.test(w[2]);

    public enum PollSeqType {
        none(0, a -> true),
        mov_cmp_jmp(3, mov_cmp_jmp_Pred),
        cmp_jmpds_mov(3, cmp_jmpds_mov_Pred),
        cmp_jmp(2, w -> isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1])),
        jmp_cmp(2, w -> isBranchOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]));

        private static final PollSeqType[] vals = PollSeqType.values();
        private int len;
        private Predicate<int[]> pred;

        PollSeqType(int len, Predicate<int[]> p) {
            this.len = len;
            this.pred = p;
        }

        public static PollSeqType getPollSequence(int[] w) {
            if (w.length < 2 || w.length > 3) { //TODO
                return none;
            }
            for (PollSeqType pst : vals) {
                if (pst == none) {
                    continue;
                }
                if (pst.len == w.length && pst.pred.test(w)) return pst;
            }
            return none;
        }
    }

    public static class PollerCtx {
        public S32xUtil.CpuDeviceAccess cpu;
        public int pc, memoryTarget, branchDest;
        public Size memTargetSize;
        public Sh2Block block = Sh2Block.INVALID_BLOCK;
        public PollState pollState = PollState.NO_POLL;

        public static PollerCtx create(Sh2Block block) {
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            return ctx;
        }

        public boolean isPollingActive() {
//            assert isPollingBlock();
            return pollState != PollState.NO_POLL;
        }

        public boolean isPollingBlock() {
            return block.pollType.ordinal() > NONE.ordinal();
        }

        public void stopPolling() {
            pollState = PollState.NO_POLL;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", PollerCtx.class.getSimpleName() + "[", "]")
                    .add("cpu=" + cpu)
                    .add("pc=" + pc)
                    .add("memoryTarget=" + memoryTarget)
                    .add("branchDest=" + branchDest)
                    .add("memTargetSize=" + memTargetSize)
                    .add("block=" + block)
                    .add("pollState=" + pollState)
                    .toString();
        }
    }

    public static final PollerCtx NO_POLLER = new PollerCtx();

    public static final Map<Integer, PollerCtx> map = new HashMap<>();

    public static void pollDetector(Sh2Block block) {
        if (map.containsKey(block.prefetchPc)) {
            PollerCtx ctx = map.getOrDefault(block.prefetchPc, NO_POLLER);
            assert ctx != NO_POLLER && ctx.block == block && ctx.cpu == block.drcContext.cpu;
            return;
        }
        if (block.pollType == UNKNOWN) {
            PollSeqType pollSeqType = PollSeqType.getPollSequence(block.prefetchWords);
            PollerCtx ctx = PollerCtx.create(block);
            setTargetInfo(ctx, pollSeqType, block.drcContext.sh2Ctx, block.prefetchWords);
            addPollMaybe(ctx, block);
            if (block.pollType == UNKNOWN) {
                busyLoopDetector(block);
            }
        }
        //mark this block as processed
        if (block.pollType == UNKNOWN) {
            block.pollType = NONE;
        }
    }

    public static void busyLoopDetector(Sh2Block block) {
        assert block.pollType == UNKNOWN;
        boolean busyLoop = isBusyLoop(block.prefetchWords);
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

    private static void setTargetInfo(PollerCtx ctx, PollSeqType pollSeqType, Sh2Context sh2Context, int[] words) {
        final int[] r = sh2Context.registers;
        int memReadOpcode = 0, jmp = 0, jmpPc = 0;
        switch (pollSeqType) {
            case mov_cmp_jmp:
                memReadOpcode = words[0];
                jmp = words[2];
                jmpPc = ctx.pc + ((words.length - 1) << 1);
                break;
            case cmp_jmpds_mov:
                memReadOpcode = words[2];
                jmp = words[1];
                jmpPc = ctx.pc + ((words.length - 2) << 1);
                break;
            case cmp_jmp:
                memReadOpcode = words[0];
                jmp = words[1];
                jmpPc = ctx.pc + ((words.length - 1) << 1);
                break;
            case jmp_cmp:
                memReadOpcode = words[1];
                jmp = words[0];
                jmpPc = ctx.pc;
                break;
        }
        if ((memReadOpcode & 0xF000) == 0x5000) {
            //MOV.L@(disp,Rm),  Rn(disp × 4 + Rm) → Rn    0101nnnnmmmmdddd
            ctx.memoryTarget = ((memReadOpcode & 0xF) << 2) + r[(memReadOpcode & 0xF0) >> 4];
            ctx.memTargetSize = Size.LONG;
        } else if (((memReadOpcode & 0xF000) == 0xC000) && ((((memReadOpcode >> 8) & 0xF) == 4) || (((memReadOpcode >> 8) & 0xF) == 5) || (((memReadOpcode >> 8) & 0xF) == 6))) {
            //mov.x @(disp, GBR), R0
            //MOV.B@(disp,GBR),     R0(disp + GBR) → sign extension → R0    11000100dddddddd
            ctx.memTargetSize = Size.vals[(memReadOpcode >> 10) & 0xF];
            ctx.memoryTarget = ((memReadOpcode & 0xFF) << ctx.memTargetSize.ordinal()) + sh2Context.GBR;
        } else if (((memReadOpcode & 0xF000) == 0x6000) && ((memReadOpcode & 0xF) < 3)) {
            //MOVXL, MOV.X @Rm,Rn
            ctx.memoryTarget = r[(memReadOpcode & 0xF0) >> 4];
            ctx.memTargetSize = Size.vals[memReadOpcode & 0xF];
        } else if (((memReadOpcode & 0xF000) == 0x8000) && (((memReadOpcode & 0xF00) == 0x400) || ((memReadOpcode & 0xF00) == 0x500))) {
            //MOVBL4, MOV.B @(disp,Rm),R0
            //MOVWL4, MOV.W @(disp,Rm),R0
            ctx.memoryTarget = r[(memReadOpcode & 0xF0) >> 4] + (memReadOpcode & 0xF);
            ctx.memTargetSize = Size.vals[(memReadOpcode >> 8) & 1];
        } else if (((memReadOpcode & 0xFF00) == 0xCC00)) {
            //TST.B #imm,@(R0,GBR) 11001100iiiiiiii     (R0 + GBR) & imm;if the result is 0, 1→T
            ctx.memoryTarget = r[0] + sh2Context.GBR;
            ctx.memTargetSize = Size.BYTE;
        }
        if ((jmp & 0xF000) == 0x8000) { //BT, BF, BTS, BFS
            int d = (byte) (jmp & 0xFF) << 1;
            ctx.branchDest = jmpPc + d + 4;
        }
    }


    /**
     * TODO??
     * S 0603fc24	e000	mov H'00, R0 [NEW]
     * S 0603fc26	d103	mov.l @(H'0603fc34), R1 [NEW]
     * S 0603fc28	2102	mov.l R0, @R1 [NEW]
     * S 0603fc2a	6012	mov.l @R1, R0 [NEW]
     * S 0603fc2c	8800	cmp/eq H'00, R0 [NEW]
     * S 0603fc2e	89fc	bt H'0603fc2a [NEW]
     *
     * @param block
     */

    private static void addPollMaybe(PollerCtx ctx, Sh2Block block) {
        boolean log = false;
        if (ctx.memTargetSize != null && ctx.branchDest == ctx.pc) {
            block.pollType = getAccessType(ctx, ctx.memoryTarget);
            if (block.pollType != UNKNOWN) {
                log = true;
                PollerCtx prevCtx = map.put(ctx.pc, ctx);
                assert prevCtx == null : ctx + "\n" + prevCtx;
            }
        }
        if (ctx.memTargetSize == null && block.pollType != UNKNOWN) {
            log = true;
        }
        if (log) {
            LOG.info("{} Poll {} at PC {}: {} {}\n{}", block.drcContext.cpu,
                    block.pollType == UNKNOWN ? "ignored" : "detected", th(block.prefetchPc),
                    th(ctx.memoryTarget), block.pollType,
                    Sh2Instructions.toListOfInst(block));
        }
    }
    public static boolean isBusyLoop(int[] prefetchWords) {
        return switch (prefetchWords.length) {
            case 3 -> isBusyLoopLen3.test(prefetchWords);
            case 2 -> isBusyLoopLen2.test(prefetchWords);
            default -> false;
        };
    }


    public static PollType getAccessType(PollerCtx ctx, int address) {
        switch (address >> 24) {
            case 6:
            case 0x26:
                return SDRAM;
            case 0:
            case 0x20:
                PollType pt = ptMap.get(S32xDict.getRegSpec(MASTER, address).deviceType);
//                assert pt != null : th(address);
                return pt == null ? UNKNOWN : pt;
            default:
                LOG.error("Unexpected access type for polling: {}", th(address));
                return UNKNOWN;
        }
    }

    //TODO seems useless
    static class InstCtx {
        public int start, end;
    }

    private static final InstCtx instCtx = new InstCtx();

    //TODO seems useless
    public static InstCtx optimizeMaybe(Sh2Block block, Sh2Prefetch.BytecodeContext ctx) {

        instCtx.start = 0;
        instCtx.end = block.prefetchWords.length;

        if (true) return instCtx;
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

    public static void clear() {
        map.clear();
    }
}
