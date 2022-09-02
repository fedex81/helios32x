package sh2.sh2.drc;

import com.google.common.collect.ImmutableMap;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.dict.S32xDict;
import sh2.dict.S32xDict.S32xRegType;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Helper;

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

    private final static boolean ENABLE_POLL_DETECT = true;
    private static final Predicate<Integer> isCmpTstOpcode = Sh2Debug.isTstOpcode.or(Sh2Debug.isCmpOpcode);

    public static final Predicate<int[]> isPollSequenceLen3 = w ->
            isMovOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]) && isBranchOpcode.test(w[2]);

    public static final Predicate<int[]> isPollSequenceLen2 = w ->
            (isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1])) ||
                    (isBranchOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]));

    /**
     * NOP
     * BRA -4 (goes back to NOP)
     * NOP
     * <p>
     * NOP
     * BRA -2 (stays here)
     * NOP
     */
    public static final Predicate<int[]> isBusyLoopLen3 = w ->
            w[0] == NOP && (w[1] == 0xaffd || w[1] == 0xaffe) && w[2] == NOP;

    /**
     * bra -2 (stays here)
     * any inst (ie. NOP, MOVLI, ...)
     */
    public static final Predicate<int[]> isBusyLoopLen2 = w -> w[0] == 0xaffe;

    public static final Map<S32xRegType, PollType> ptMap = ImmutableMap.of(
            S32xRegType.DMA, DMA,
            S32xRegType.PWM, PWM,
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
        DMA,
        PWM,
        SYS,
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

    // FIFA 96
    // MOVI + mov_cmp_jmp
    private static final Predicate<int[]> mov_mov_cmp_jmp_Pred = w ->
            (w[0] & 0xF000) == 0xE000 &&
                    isMovOpcode.test(w[1]) && isCmpTstOpcode.test(w[2]) && isBranchOpcode.test(w[3]);

    //Doom res
    private static final Predicate<int[]> tas_movt_cmp_jmp_Pred = w ->
            (w[0] & 0xF0FF) == 0x401b &&
                    (w[1] & 0xF0FF) == 0x0029 &&
                    isCmpTstOpcode.test(w[2]) && isBranchOpcode.test(w[3]);

    private static final Predicate<int[]> mov_cmp_jmp_Pred = w ->
            isMovOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]) && isBranchOpcode.test(w[2]);
    private static final Predicate<int[]> cmp_jmpds_mov_Pred = w ->
            isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1]) && isMovOpcode.test(w[2]);

    public enum PollSeqType {
        none(0, a -> true),

        tas_movt_cmp_jmp(4, tas_movt_cmp_jmp_Pred),
        mov_mov_cmp_jmp(4, mov_mov_cmp_jmp_Pred),
        mov_cmp_jmp(3, mov_cmp_jmp_Pred),
        cmp_jmpds_mov(3, cmp_jmpds_mov_Pred),
        cmp_jmp(2, w -> isCmpTstOpcode.test(w[0]) && isBranchOpcode.test(w[1])),
        jmp_cmp(2, w -> isBranchOpcode.test(w[0]) && isCmpTstOpcode.test(w[1]));

        private static final PollSeqType[] vals = PollSeqType.values();
        private final int len;
        private final Predicate<int[]> pred;

        PollSeqType(int len, Predicate<int[]> p) {
            this.len = len;
            this.pred = p;
        }

        public static PollSeqType getPollSequence(int[] w) {
            if (w.length < 2 || w.length > 4) { //TODO
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
        public CpuDeviceAccess cpu;
        public int pc, memoryTarget, branchDest;
        public Size memTargetSize;
        public Sh2Block block = Sh2Block.INVALID_BLOCK;
        public SysEvent event;
        public PollState pollState = PollState.NO_POLL;
        @Deprecated
        public long initialValue = Long.MAX_VALUE;
        public int spinCount = 0;

        public static PollerCtx create(Sh2Block block) {
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            ctx.event = SysEvent.NONE;
            return ctx;
        }

        public boolean isPollingActive() {
            return pollState != PollState.NO_POLL;
        }

        public boolean isPollingBlock() {
            return block.pollType.ordinal() > NONE.ordinal();
        }

        public boolean isPollingBusyLoop() {
            return block.pollType == BUSY_LOOP;
        }

        public void stopPolling() {
            pollState = PollState.NO_POLL;
            initialValue = Long.MAX_VALUE;
            spinCount = 0;
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

        public void invalidate() {
            block = Sh2Block.INVALID_BLOCK;
            event = SysEvent.NONE;
        }
    }

    public static final PollerCtx NO_POLLER = new PollerCtx();

    public static final Map<Integer, PollerCtx>[] map = new HashMap[]{new HashMap<>(), new HashMap<>()};

    public static void pollDetector(Sh2Block block) {
        final var pollerMap = map[block.drcContext.cpu.ordinal()];
        if (pollerMap.containsKey(block.prefetchPc)) {
            PollerCtx ctx = pollerMap.getOrDefault(block.prefetchPc, NO_POLLER);
            assert ctx != NO_POLLER && ctx.block == block && ctx.cpu == block.drcContext.cpu :
                    "Poller: " + ctx + "\nPrev: " + ctx.block + "\nNew : " + block;
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
                    Sh2Helper.toListOfInst(block));
            //TODO check
            if (block.prefetchLenWords == 2 && block.prefetchWords[0] == 0xaffe) {
                int w1 = block.prefetchWords[1];
                if (w1 != NOP) {
                    LOG.warn("{} BusyLoop check: {}\n{}", block.drcContext.cpu, th(block.prefetchPc),
                            Sh2Helper.toListOfInst(block));
                }
            }
            PollerCtx ctx = new PollerCtx();
            ctx.pc = block.prefetchPc;
            ctx.block = block;
            ctx.cpu = block.drcContext.cpu;
            block.pollType = BUSY_LOOP;
            ctx.event = SysEvent.INT;
            PollerCtx prevCtx = map[ctx.cpu.ordinal()].put(ctx.pc, ctx);
            assert prevCtx == null : ctx + "\n" + prevCtx;
        }
    }

    private static void setTargetInfo(PollerCtx ctx, PollSeqType pollSeqType, Sh2Context sh2Context, int[] words) {
        final int[] r = sh2Context.registers;
        int memReadOpcode = 0, jmp = 0, jmpPc = 0, start = 0;
        assert ctx.initialValue == Long.MAX_VALUE;
        switch (pollSeqType) {
            case tas_movt_cmp_jmp:
                memReadOpcode = words[0];
                jmp = words[3];
                jmpPc = ctx.pc + ((words.length - 1) << 1);
                break;
            case mov_mov_cmp_jmp:
                start = 1;
                //fall-through
            case mov_cmp_jmp:
                memReadOpcode = words[start];
                jmp = words[start + 2];
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
            //MOVLL4 MOV.L@(disp,Rm),  Rn(disp × 4 + Rm) → Rn    0101nnnnmmmmdddd
            ctx.memoryTarget = ((memReadOpcode & 0xF) << 2) + r[(memReadOpcode & 0xF0) >> 4];
            ctx.memTargetSize = Size.LONG;
        } else if (((memReadOpcode & 0xF000) == 0xC000) && ((((memReadOpcode >> 8) & 0xF) == 4) || (((memReadOpcode >> 8) & 0xF) == 5) || (((memReadOpcode >> 8) & 0xF) == 6))) {
            //MOVBLG MOV.B@(disp,GBR),     R0(disp + GBR) → sign extension → R0    11000100dddddddd
            ctx.memTargetSize = Size.vals[(memReadOpcode >> 8) & 0x3];
            ctx.memoryTarget = ((memReadOpcode & 0xFF) << ctx.memTargetSize.ordinal()) + sh2Context.GBR;
        } else if (((memReadOpcode & 0xF000) == 0x6000) && ((memReadOpcode & 0xF) < 3)) {
            //MOVXL, MOV.X @Rm,Rn
            ctx.memoryTarget = r[(memReadOpcode & 0xF0) >> 4];
            ctx.memTargetSize = Size.vals[memReadOpcode & 0xF];
        } else if (((memReadOpcode & 0xF000) == 0x8000) && (((memReadOpcode & 0xF00) == 0x400) || ((memReadOpcode & 0xF00) == 0x500))) {
            //MOVBL4, MOV.B @(disp,Rm),R0
            //MOVWL4, MOV.W @(disp,Rm),R0
            ctx.memTargetSize = Size.vals[(memReadOpcode >> 8) & 1];
            ctx.memoryTarget = r[(memReadOpcode & 0xF0) >> 4] + ((memReadOpcode & 0xF) << ctx.memTargetSize.ordinal());
        } else if (((memReadOpcode & 0xFF00) == 0xCC00)) {
            //TSTM TST.B #imm,@(R0,GBR) 11001100iiiiiiii     (R0 + GBR) & imm;if the result is 0, 1→T
            ctx.memoryTarget = r[0] + sh2Context.GBR;
            ctx.memTargetSize = Size.BYTE;
        } else if ((memReadOpcode & 0xF0FF) == 0x401b) { //TAS.B @Rn
            ctx.memoryTarget = r[(memReadOpcode >> 8) & 0xF];
            ctx.memTargetSize = Size.BYTE;
        }
        if ((jmp & 0xF000) == 0x8000) { //BT, BF, BTS, BFS
            int d = (byte) (jmp & 0xFF) << 1;
            ctx.branchDest = jmpPc + d + 4;
        } else if ((jmp & 0xF000) == 0xA000) { //BRA
            int disp = ((jmp & 0x800) == 0) ? 0x00000FFF & jmp : 0xFFFFF000 | jmp;
            ctx.branchDest = jmpPc + 4 + (disp << 1);
        }
        assert ctx.memTargetSize != null ? ctx.branchDest != 0 : true;
    }
    private static void addPollMaybe(PollerCtx ctx, Sh2Block block) {
        boolean supported = false, log = false;
        if (ctx.memTargetSize != null && ctx.branchDest == ctx.pc) {
            block.pollType = getAccessType(ctx, ctx.memoryTarget);
//            assert (block.pollType != SDRAM ? (ctx.memoryTarget | SH2_CACHE_THROUGH_OFFSET) == ctx.memoryTarget : true) : ctx;
            if (block.pollType != UNKNOWN) {
                ctx.event = SysEvent.valueOf(block.pollType.name());
                log = true;
                if (ENABLE_POLL_DETECT && block.pollType != DMA && block.pollType != PWM) { //TODO not supported
                    supported = true;
                    PollerCtx prevCtx = map[ctx.cpu.ordinal()].put(ctx.pc, ctx);
                    assert prevCtx == null : ctx + "\n" + prevCtx;
                }
            }
        }
        log |= ctx.memTargetSize == null && block.pollType != UNKNOWN;
        if (log) {
            LOG.info("{} Poll {} at PC {}: {} {}\n{}", block.drcContext.cpu,
                    supported ? "detected" : "ignored", th(block.prefetchPc),
                    th(ctx.memoryTarget), block.pollType,
                    Sh2Helper.toListOfInst(block));
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
                return pt == null ? NONE : pt;
            default:
                LOG.error("Unexpected access type for polling: {}", th(address));
                return NONE;
        }
    }
    public static void clear() {
        map[0].clear();
        map[1].clear();
    }
}
