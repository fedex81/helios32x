package sh2.sh2.drc;

import com.google.common.collect.ImmutableMap;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import sh2.Md32xRuntimeData;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.dict.S32xDict;
import sh2.dict.S32xDict.S32xRegType;
import sh2.event.SysEventManager.SysEvent;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.Sh2Helper;
import sh2.sh2.Sh2Helper.Sh2PcInfoWrapper;

import java.util.Arrays;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Predicate;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.dict.S32xDict.SH2_PC_AREA_SHIFT;
import static sh2.sh2.Sh2Debug.isBranchOpcode;
import static sh2.sh2.Sh2Debug.isMovOpcode;
import static sh2.sh2.Sh2Disassembler.NOP;
import static sh2.sh2.Sh2Impl.RM;
import static sh2.sh2.Sh2Impl.RN;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Ow2DrcOptimizer {

    /**
     * vf - sequence to optimize -> R1 = 0x80
     * 00000538	e180	mov H'ffffff80, R1 (MOVI)
     * 0000053a	611c	extu.b R1, R1
     */

    private final static Logger LOG = LogHelper.getLogger(Ow2DrcOptimizer.class.getSimpleName());

    //toggle poll detection but keep busyLoop detection enabled
    public final static boolean ENABLE_POLL_DETECT = true;
    private final static boolean LOG_POLL_DETECT = false;
    public static final Map<S32xRegType, PollType> ptMap = ImmutableMap.of(
            S32xRegType.DMA, DMA,
            S32xRegType.PWM, PWM,
            S32xRegType.VDP, VDP,
            S32xRegType.SYS, SYS,
            S32xRegType.NONE, NONE,
            S32xRegType.COMM, COMM
    );

    public enum PollState {NO_POLL, ACTIVE_POLL}

    public enum PollType {
        UNKNOWN,
        NONE,
        BUSY_LOOP(true),
        SDRAM(true),
        FRAMEBUFFER, //TODO DoomRes, is it worth it?
        COMM(true),
        DMA,
        PWM,
        SYS(true),
        VDP(true);

        public final boolean supported;

        PollType() {
            this(false);
        }

        PollType(boolean supported) {
            this.supported = supported;
        }
    }

    private static final Predicate<Integer> isCmpTstOpcode = Sh2Debug.isTstOpcode.or(Sh2Debug.isCmpOpcode);

    public static final Predicate<Integer> isTasOpcode = op -> (op & 0xF0FF) == 0x401b;
    public static final Predicate<Integer> isMovtOpcode = op -> (op & 0xF0FF) == 0x0029;
    public static final Predicate<Integer> isMemLoadOpcode = op -> isMovOpcode.or(isTasOpcode).test(op);
    private static final Predicate<Integer> isMoviOpcode = w -> (w & 0xF000) == 0xE000;

    //shlr8
    private static final Predicate<Integer> isShiftOpcode = w -> (w & 0xF0FF) == 0x4019;

    //opcode that modifies the T flag, ie. shlr
    private static final Predicate<Integer> isFlagOpcode = op -> (op & 0xF0FF) == 0x4001;

    private static final Predicate<Integer> isSwapOpcode = w -> ((w & 0xF00F) == 0x6008 || (w & 0xF00F) == 0x6009);
    private static final Predicate<Integer> isXorOpcode = w -> (w & 0xF00F) == 0x200A;

    private static final Predicate<Integer> isExtOpcode = w ->
            (w & 0xF00F) == 0x600C || (w & 0xF00F) == 0x600D || (w & 0xF00F) == 0x600E || (w & 0xF00F) == 0x600F;

    private static final Predicate<Integer> isAndOrOpcode = w -> ((w & 0xF00F) == 0x2009 || (w & 0xFF00) == 0xC900 ||
            (w & 0xFF00) == 0xCB00);

    public static final Predicate<Integer> isMovR2ROpcode = w -> (w & 0xF00F) == 0x6003; //mov Rm, Rn
    public static final Predicate<Integer> isRegOnlyOpcode = isAndOrOpcode.or(isExtOpcode).or(isSwapOpcode).
            or(isMovR2ROpcode).or(isMoviOpcode).or(isMovtOpcode).or(isShiftOpcode).or(isXorOpcode);

    private static final Predicate<Integer> isDtOpcode = op -> (op & 0xF0FF) == 0x4010;

    public static class BlockPollData {
        public int memLoadPos = -1, memLoadOpcode, cmpPos = -1, cmpOpcode, branchPos = -1,
                branchOpcode, branchPc, branchDestPc;
        public int pc;
        public int memLoadTarget = 0, memLoadTargetEnd;
        public int numNops, numRegOnly;
        public Size memLoadTargetSize;
        public final int[] words;
        public final Sh2Context ctx;
        public final Sh2Block block;
        public boolean isPoller, isBusyLoop;
        private int activeInstLen = 0;

        public BlockPollData(Sh2Block block, Sh2Context ctx, int pc, int[] prefetchWords) {
            this.words = prefetchWords;
            this.pc = pc;
            this.ctx = ctx;
            this.block = block;
        }

        public void init() {
            numNops = (int) Arrays.stream(words).filter(op -> NOP == op).count();
            int nonNopsLen = words.length - numNops;
            detect(nonNopsLen);
        }

        private void detect(int nonNopsLen) {
            int memLoads = 0;
            for (int i = 0; i < words.length; i++) {
                final int opcode = words[i];
                if (isMemLoadOpcode.test(opcode)) {
                    memLoadPos = i;
                    memLoadOpcode = opcode;
                    activeInstLen++;
                    memLoads++;
                } else if (isCmpTstOpcode.test(opcode) || isFlagOpcode.test(opcode)) {
                    cmpPos = i;
                    cmpOpcode = opcode;
                    activeInstLen++;
                } else if (isBranchOpcode.test(opcode)) {
                    branchPos = i;
                    branchOpcode = opcode;
                    branchPc = pc + (branchPos << 1);
                    activeInstLen++;
                }
            }
            if (memLoadPos >= 0) {
                parseMemLoad(this, ctx, memLoadOpcode);
            }
            if (branchPos >= 0) {
                branchDestPc = getBranchDestination(branchOpcode, branchPc);
            }
            if (nonNopsLen == 2) {
                if (cmpPos >= 0 && branchPos >= 0 && branchDestPc == pc) {
                    parseMemLoad(this, ctx, cmpOpcode);
                    memLoadPos = cmpPos;
                    memLoads++;
                }
            }
            if (nonNopsLen == 1) {
                int endPc = pc + ((words.length - 1) << 1);
                if (branchPos >= 0 && branchDestPc >= pc && branchDestPc <= endPc) {
                    isBusyLoop = true;
                } else {
//                    LOG.info("{} NOT a BusyLoop: {}\n{}", block.drcContext.cpu, th(block.prefetchPc),
//                            Sh2Helper.toListOfInst(block));
                }
            }
            numRegOnly = (int) Arrays.stream(words).filter(isRegOnlyOpcode::test).count();
            if (memLoads == 1) {
                isPollerRecalc();
            }
            /**
             *  06002406	0009	nop
             *  00002408	0009	nop
             *  0000240a	88ff	cmp/eq H'ffffffff, R0
             *  0000240c	8bfb	bf H'00002406
             */
            if (isPoller && memLoadTarget == 0) {
                LOG.warn("Busy Loop?\n{}\n{}\n{}", Sh2Helper.toListOfInst(block), block, this);
                isPoller = false;
                isBusyLoop = true;
            }
        }

        private boolean isPollerRecalc() {
            int len = words.length;
            boolean okLen = activeInstLen + numNops + numRegOnly == len;
            isPoller = (activeInstLen > 2 ? cmpPos >= 0 : true) && (activeInstLen > 1 ? memLoadPos >= 0 : true)
                    && branchPos >= 0 && branchDestPc == pc && okLen;
            return isPoller;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockPollData that = (BlockPollData) o;
            return memLoadPos == that.memLoadPos && memLoadOpcode == that.memLoadOpcode &&
                    cmpPos == that.cmpPos && cmpOpcode == that.cmpOpcode &&
                    branchPos == that.branchPos && branchOpcode == that.branchOpcode && branchPc == that.branchPc && branchDestPc == that.branchDestPc && pc == that.pc && memLoadTarget == that.memLoadTarget && isPoller == that.isPoller && memLoadTargetSize == that.memLoadTargetSize && com.google.common.base.Objects.equal(words, that.words) && com.google.common.base.Objects.equal(ctx, that.ctx);
        }

        @Override
        public int hashCode() {
            return com.google.common.base.Objects.hashCode(memLoadPos, memLoadOpcode, cmpPos, cmpOpcode, branchPos, branchOpcode, branchPc, branchDestPc, pc, memLoadTarget, memLoadTargetSize, words, ctx, isPoller);
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", BlockPollData.class.getSimpleName() + "[", "]")
                    .add("memLoadPos=" + th(memLoadPos))
                    .add("memLoadOpcode=" + th(memLoadOpcode))
                    .add("cmpPos=" + th(cmpPos))
                    .add("cmpOpcode=" + th(cmpOpcode))
                    .add("branchPos=" + th(branchPos))
                    .add("branchOpcode=" + th(branchOpcode))
                    .add("branchPc=" + th(branchPc))
                    .add("branchDestPc=" + th(branchDestPc))
                    .add("pc=" + th(pc))
                    .add("memLoadTarget=" + th(memLoadTarget))
                    .add("memLoadTargetSize=" + memLoadTargetSize)
                    .add("isPoller=" + isPoller)
                    .toString();
        }
    }

    public static class PollerCtx {
        public CpuDeviceAccess cpu;
        public int pc;
        public SysEvent event;
        public PollState pollState = PollState.NO_POLL;
        public int spinCount = 0;
        public BlockPollData blockPollData;
        private Sh2PcInfoWrapper piw;

        public static PollerCtx create(Sh2PcInfoWrapper piw) {
            PollerCtx ctx = new PollerCtx();
            Sh2Block block = piw.block;
            ctx.piw = piw;
            ctx.pc = block.prefetchPc;
            assert block.drcContext != null : piw;
            ctx.cpu = block.drcContext.cpu;
            ctx.event = SysEvent.NONE;
            ctx.blockPollData = new BlockPollData(block, block.drcContext.sh2Ctx, ctx.pc, block.prefetchWords);
            return ctx;
        }

        public boolean isPollingActive() {
            return pollState != PollState.NO_POLL;
        }

        public boolean isPollingBusyLoop() {
            return piw.block.pollType == BUSY_LOOP;
        }

        public void stopPolling() {
            pollState = PollState.NO_POLL;
            spinCount = 0;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", PollerCtx.class.getSimpleName() + "[", "]")
                    .add("cpu=" + cpu)
                    .add("pc=" + th(pc))
                    .add("event=" + event)
                    .add("pollState=" + pollState)
                    .add("spinCount=" + spinCount)
                    .add("blockPollData=" + blockPollData)
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PollerCtx pollerCtx = (PollerCtx) o;
            return pc == pollerCtx.pc && spinCount == pollerCtx.spinCount && cpu == pollerCtx.cpu
                    && event == pollerCtx.event && pollState == pollerCtx.pollState && com.google.common.base.Objects.equal(blockPollData, pollerCtx.blockPollData);
        }

        @Override
        public int hashCode() {
            return com.google.common.base.Objects.hashCode(cpu, pc, event, pollState, spinCount, blockPollData);
        }

        public void invalidate() {
            event = SysEvent.NONE;
        }
    }

    public static final PollerCtx NO_POLLER = new PollerCtx();
    public static final PollerCtx UNKNOWN_POLLER = new PollerCtx();


    public static void pollDetector(Sh2Block block) {
        Sh2PcInfoWrapper piw = Sh2Helper.get(block.prefetchPc, block.drcContext.cpu);
        if (piw.block.poller != UNKNOWN_POLLER) {
            PollerCtx ctx = piw.block.poller;
            Sh2Block prevBlock = piw.block;
            assert block.isValid() && ctx != UNKNOWN_POLLER &&
                    (prevBlock != Sh2Block.INVALID_BLOCK ? prevBlock == block : true)
                    && ctx.cpu == block.drcContext.cpu :
                    "Poller: " + ctx + "\nPiwBlock: " + piw.block + "\nPrevPoll: " + prevBlock + "\nNewPoll : " + block;
            return;
        }
        assert piw.block == block : "PiwBlock: " + piw.block + "\nBlock: " + block + "\n" +
                block.drcContext.cpu + "," + th(block.prefetchPc) + "," + th(block.hashCodeWords);
        if (block.pollType == UNKNOWN) {
            PollerCtx ctx = PollerCtx.create(piw);
            ctx.blockPollData.init();
            piw.block.poller = addPollMaybe(ctx, block);
        }
        //mark this block as processed
        if (block.pollType == UNKNOWN) {
            block.pollType = NONE;
        }
    }

    public static int getBranchDestination(int jmpOpcode, int jmpPc) {
        int branchDest = 0;
        if ((jmpOpcode & 0xF000) == 0x8000) { //BT, BF, BTS, BFS
            int d = (byte) (jmpOpcode & 0xFF) << 1;
            branchDest = jmpPc + d + 4;
        } else if ((jmpOpcode & 0xF000) == 0xA000) { //BRA
            int disp = ((jmpOpcode & 0x800) == 0) ? 0x00000FFF & jmpOpcode : 0xFFFFF000 | jmpOpcode;
            branchDest = jmpPc + 4 + (disp << 1);
        }
        return branchDest;
    }

    public static void parseMemLoad(BlockPollData bpd) {
        parseMemLoad(bpd, bpd.ctx, bpd.memLoadOpcode);
    }

    private static void parseMemLoad(BlockPollData bpd, Sh2Context sh2Context, int memReadOpcode) {
        final int[] r = sh2Context.registers;
        if ((memReadOpcode & 0xF000) == 0x5000) {
//            MOVLL4 MOV.L@(disp,Rm),  Rn(disp × 4 + Rm) → Rn    0101nnnnmmmmdddd
            bpd.memLoadTarget = ((memReadOpcode & 0xF) << 2) + r[RM(memReadOpcode)];
            bpd.memLoadTargetSize = Size.LONG;
        } else if (((memReadOpcode & 0xF000) == 0xC000) && ((((memReadOpcode >> 8) & 0xF) == 4) || (((memReadOpcode >> 8) & 0xF) == 5) || (((memReadOpcode >> 8) & 0xF) == 6))) {
            //MOVBLG MOV.B@(disp,GBR),     R0(disp + GBR) → sign extension → R0    11000100dddddddd
            bpd.memLoadTargetSize = Size.vals[(memReadOpcode >> 8) & 0x3];
            bpd.memLoadTarget = ((memReadOpcode & 0xFF) << bpd.memLoadTargetSize.ordinal()) + sh2Context.GBR;
        } else if (((memReadOpcode & 0xF000) == 0x6000) && ((memReadOpcode & 0xF) < 3)) {
            //MOVXL, MOV.X @Rm,Rn
            bpd.memLoadTarget = r[RM(memReadOpcode)];
            bpd.memLoadTargetSize = Size.vals[memReadOpcode & 0xF];
        } else if (((memReadOpcode & 0xF000) == 0x8000) && (((memReadOpcode & 0xF00) == 0x400) || ((memReadOpcode & 0xF00) == 0x500))) {
            //MOVBL4, MOV.B @(disp,Rm),R0
            //MOVWL4, MOV.W @(disp,Rm),R0
            bpd.memLoadTargetSize = Size.vals[(memReadOpcode >> 8) & 1];
            bpd.memLoadTarget = r[RM(memReadOpcode)] + ((memReadOpcode & 0xF) << bpd.memLoadTargetSize.ordinal());
        } else if (((memReadOpcode & 0xFF00) == 0xCC00)) {
            //TSTM TST.B #imm,@(R0,GBR) 11001100iiiiiiii     (R0 + GBR) & imm;if the result is 0, 1→T
            bpd.memLoadTarget = r[0] + sh2Context.GBR;
            bpd.memLoadTargetSize = Size.BYTE;
        } else if ((memReadOpcode & 0xF0FF) == 0x401b) { //TAS.B @Rn
            bpd.memLoadTarget = r[RN(memReadOpcode)];
            bpd.memLoadTargetSize = Size.BYTE;
        }
        if (bpd.memLoadTargetSize != null) {
            bpd.memLoadTargetEnd = bpd.memLoadTarget + (bpd.memLoadTargetSize.getByteSize() - 1);
        }
    }

    private static PollerCtx addPollMaybe(PollerCtx pctx, Sh2Block block) {
        boolean supported = false, log = false;
        BlockPollData bpd = pctx.blockPollData;
        PollerCtx toSet = NO_POLLER;
        if (bpd.isPoller) {
            block.pollType = getAccessType(bpd.memLoadTarget);
            log |= bpd.branchDestPc == bpd.pc;
            if (block.pollType != UNKNOWN) {
                pctx.event = SysEvent.valueOf(block.pollType.name());
                log = true;
                if (ENABLE_POLL_DETECT && block.pollType.supported) {
                    supported = true;
                    toSet = pctx;
//                    log = false;
                }
            }
            log |= bpd.memLoadTargetSize == null && block.pollType != UNKNOWN;
            if (LOG_POLL_DETECT && log) {
                LOG.makeLoggingEventBuilder(supported ? Level.INFO : Level.ERROR).log(
                        "{} Poll {} at PC {}: {} {}\n{}", block.drcContext.cpu,
                        supported ? "detected" : "ignored", th(block.prefetchPc),
                        th(bpd.memLoadTarget), block.pollType,
                        Sh2Helper.toListOfInst(block));
            }
        } else if (bpd.isBusyLoop) {
            LOG.info("{} BusyLoop detected: {}\n{}", block.drcContext.cpu, th(block.prefetchPc),
                    Sh2Helper.toListOfInst(block));
            block.pollType = BUSY_LOOP;
            pctx.event = SysEvent.INT;
            toSet = pctx;
        }
        if (block.pollType == UNKNOWN) {
            block.pollType = NONE;
        }
        assert toSet != null;
        return toSet;
    }

    //TODO poll on cached address??? tas poll is allowed even on cached addresses
    //TODO DoomRes polls the framebuffer
    public static PollType getAccessType(int address) {
        //TODO check, is it necessary/relevant
        final boolean isCache = false; //(address >>> PC_CACHE_AREA_SHIFT) == 0 && sh2Cache.getCacheContext().cacheEn > 0;
        if (isCache) {
            LOG.warn("Polling on a cache address: {}", th(address));
            System.err.println("Polling on a cache address: " + th(address));
        }
        switch (address >>> SH2_PC_AREA_SHIFT) {
            case 0x6:
            case 0x26:
                return SDRAM;
            case 0x24:
            case 0x4:
                return FRAMEBUFFER;
            case 0:
            case 0x20: {
                PollType pt = ptMap.get(S32xDict.getRegSpec(MASTER, address).deviceType);
//                assert pt != null : th(address);
                return pt == null ? NONE : pt;
            }
            default:
                LOG.error("{} Unexpected {} access type for polling: {}", Md32xRuntimeData.getAccessTypeExt(), NONE
                        , th(address));
                return NONE;
        }
    }

    /**
     * e142	mov H'42, R1
     * 611c	extu.b R1, R1
     */
    static Predicate<Integer> isMoviuExtu = op -> isMoviOpcode.test(op >> 16) && isExtOpcode.test(op & 0xFFFF) &&
            ((op >> 24) & 0xF) == ((op >> 4) & 0xF) && ((op >> 8) & 0xF) == ((op >> 4) & 0xF);


    public static void checkOptBlock(Sh2Block block) {
        final int[] ops = block.prefetchWords;
        boolean match;
        for (int i = 0; i < block.prefetchLenWords - 1; i += 2) {
            if (isMoviuExtu.test(ops[i] << 16 | ops[i + 1])) {
                System.out.println(Sh2Helper.toListOfInst(block.prefetchPc, ops[i], ops[i + 1]));
            } else if (isExtOpcode.test(ops[i + 1]) && ((ops[i + 1] >> 8) & 0xF) == ((ops[i + 1] >> 4) & 0xF)) {
                System.out.println("Check:\n" + Sh2Helper.toListOfInst(block.prefetchPc, ops[i], ops[i + 1]));
            }
        }
    }
}
