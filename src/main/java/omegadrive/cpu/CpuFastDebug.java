package omegadrive.cpu;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static omegadrive.util.Util.th;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class CpuFastDebug {

    private static final Logger LOG = LogManager.getLogger(CpuFastDebug.class.getSimpleName());

    private static final boolean logToSysOut = Boolean.parseBoolean(System.getProperty("helios.logToSysOut", "false"));

    public interface CpuDebugInfoProvider {
        String getInstructionOnly(int pc);

        String getCpuState(String head);

        int getPc();

        int getOpcode();

        default String getInstructionOnly() {
            return getInstructionOnly(getPc());
        }
    }

    public static class PcInfoWrapper {
        public int area;
        public int pcMasked;
        public int opcode;
        public int pcLoops;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PcInfoWrapper that = (PcInfoWrapper) o;
            return area == that.area && pcMasked == that.pcMasked;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(area, pcMasked);
        }
    }

    public static class CpuDebugContext {
        public int[] pcAreasMaskMap;
        public int pcAreasNumber, pcAreaSize, pcMask, pcAreaShift;
        public int[] pcAreas; //TODO remove
        public Predicate<Integer> isLoopOpcode = i -> false;
        public Predicate<Integer> isIgnoreOpcode = i -> true;
        public int debugMode;
    }

    public enum DebugMode {NONE, INST_ONLY, NEW_INST_ONLY, STATE}

    public final static DebugMode[] debugModeVals = DebugMode.values();
    public static final PcInfoWrapper NOT_VISITED = new PcInfoWrapper();

    private final CpuDebugContext ctx;
    public final PcInfoWrapper[][] pcInfoWrapper;
    public DebugMode debugMode = DebugMode.NONE;
    private CpuDebugInfoProvider debugInfoProvider;
    private int delay;
    private final static boolean VERBOSE = false;
    public static int CK_DELAY_ON_LOOP = 50;

    //TODO - should be in sh2debug
    //00_00_0000 - 00_00_4000 BOOT ROM
    //02_00_0000 - 02_40_0000 ROM
    //04_00_0000 - 04_04_0000 FRAME BUFFER + OVERWRITE
    //06_00_0000 - 06_04_0000 RAM
    //C0_00_0000 - C0_01_0000 CACHE AREA
    public static final Map<Integer, Integer> areaMaskMap = ImmutableMap.<Integer, Integer>builder().
            //boot rom + regs, rom, frame buffer + overwrite, sdram
                    put(0, 0x8000 - 1).put(2, 0x40_0000 - 1).put(4, 0x4_0000 - 1).put(6, 0x4_0000 - 1).
            //cache through
                    put(0x20, 0x8000 - 1).put(0x22, 0x40_0000 - 1).put(0x24, 0x4_0000 - 1).put(0x26, 0x4_0000 - 1).
            //cache access
                    put(0xC0, 0x10000 - 1).build();

    public static final int[] pcAreaMaskMap = new int[0x100];

    static {
        for (var e : areaMaskMap.entrySet()) {
            pcAreaMaskMap[e.getKey()] = e.getValue();
        }
    }
    //TODO

    public CpuFastDebug(CpuDebugInfoProvider debugInfoProvider, CpuDebugContext ctx) {
        this.debugInfoProvider = debugInfoProvider;
        this.ctx = ctx;
        ctx.pcAreasMaskMap = pcAreaMaskMap;
        this.pcInfoWrapper = createWrapper(ctx.pcAreasNumber, areaMaskMap);
        init();
    }

    public void init() {
        assert ctx.debugMode >= 0 && ctx.debugMode < debugModeVals.length;
        debugMode = debugModeVals[ctx.debugMode];
    }

    public static PcInfoWrapper[][] createWrapper(int pcAreasNumber, Map<Integer, Integer> areaMaskMap) {
        PcInfoWrapper[][] pcInfoWrapper = new PcInfoWrapper[pcAreasNumber][0];
        for (var m : areaMaskMap.entrySet()) {
            pcInfoWrapper[m.getKey()] = new PcInfoWrapper[m.getValue() + 1];
            Arrays.fill(pcInfoWrapper[m.getKey()], NOT_VISITED);
        }
        return pcInfoWrapper;
    }

    public void printDebugMaybe() {
        switch (debugMode) {
            case STATE:
                log(debugInfoProvider.getCpuState(""), "");
                break;
            case INST_ONLY:
                log(debugInfoProvider.getInstructionOnly(), "");
                break;
            case NEW_INST_ONLY:
                printNewInstruction();
                break;
            default:
                break;
        }
    }

    private void printNewInstruction() {
        final int pc = debugInfoProvider.getPc();
        final int area = pc >>> ctx.pcAreaShift;
        if (area == 0xC0) {
            System.out.print("");
        }
        final int mask = ctx.pcAreasMaskMap[area];
        final int pcMasked = pc & mask;
        final int opcode = debugInfoProvider.getOpcode();

        PcInfoWrapper piw = pcInfoWrapper[area][pcMasked];
        if (piw != NOT_VISITED && piw.opcode != opcode) {
            piw.opcode = opcode;
            log(debugInfoProvider.getInstructionOnly(), " [NEW-R]");
        } else if (piw == NOT_VISITED) {
            piw = new PcInfoWrapper();
            piw.pcMasked = pcMasked;
            piw.opcode = opcode;
            pcInfoWrapper[area][pcMasked] = piw;
//            log(debugInfoProvider.getInstructionOnly(), " [NEW]");
        }
    }

    private void log(String s1, String s2) {
        LOG.info("{}{}", s1, s2);
        if (logToSysOut) {
            System.out.println(s1 + s2);
        }
    }

    private static int pcHistorySize = 12;
    private int FRONT = 0, BACK = 1;
    private int[][] opcodesHistory = new int[2][pcHistorySize];
    private int[][] pcHistory = new int[2][pcHistorySize];
    private int pcHistoryPointer = 0, loops;
    private boolean looping = false, isKnownLoop;
    private int loopsCounter = 0;

    public int isBusyLoop(int pc, int opcode) {
        pcHistory[FRONT][pcHistoryPointer] = pc;
        opcodesHistory[FRONT][pcHistoryPointer] = opcode;
        pcHistoryPointer = (pcHistoryPointer + 1) % pcHistorySize;
        if (pcHistoryPointer == 0) {
            if (Arrays.equals(pcHistory[FRONT], pcHistory[BACK])) {
                if (Arrays.equals(opcodesHistory[FRONT], opcodesHistory[BACK])) {
                    loops++;
                    if (!looping && loops > pcHistorySize) {
                        handleLoop(pc);
                        looping = true;
                    }
                } else { //opcodes are different
                    handleStopLoop(pc);
                }
            } else {
                if (looping) {
                    handleStopLoop(pc);
                }
                loops = 0;
            }
            FRONT = (FRONT + 1) & 1;
            BACK = (BACK + 1) & 1;
        }
        return delay;
    }

    private void handleStopLoop(int pc) {
        looping = false;
        loops = 0;
        delay = 0;
        if (!isKnownLoop) {
            if (VERBOSE) {
                String s = debugInfoProvider.getInstructionOnly();
                LOG.info("Stop loop: {}", s);
                System.out.println("Stop loop: " + s);
            }
        }
    }

    private void handleLoop(int pc) {
        final int[] opcodes = Arrays.stream(opcodesHistory[FRONT]).distinct().sorted().toArray();
        final boolean isBusy = isBusyLoop(ctx.isLoopOpcode, opcodes);
        delay = isBusy ? CK_DELAY_ON_LOOP : 0;
        final int mask = ctx.pcAreasMaskMap[pc >> ctx.pcAreaShift];
        final int pcMasked = pc & mask;
        final int area = pc >>> ctx.pcAreaShift;
        PcInfoWrapper piw = pcInfoWrapper[area][pcMasked];
        if (piw != NOT_VISITED && piw.pcLoops > 0) {
            isKnownLoop = true;
            if (VERBOSE && isBusy) {
                LOG.info("Known loop at: {}, busy: {}", th(pc), isBusy);
                System.out.println("Known loop at: " + th(pc) + ", busy: " + isBusy);
            }
            return;
        }
        isKnownLoop = false;
        loopsCounter++;
        for (int i = 0; i < pcHistorySize; i++) {
            int pci = pcHistory[FRONT][i];
            piw.pcLoops = loopsCounter;
        }
        if (VERBOSE) {
            boolean ignore = isIgnore(ctx.isIgnoreOpcode, opcodes);
            if (!ignore) {
                int[] pcs = Arrays.stream(pcHistory[FRONT]).distinct().sorted().toArray();
                String s = Arrays.stream(pcs).mapToObj(debugInfoProvider::getInstructionOnly).collect(Collectors.joining("\n"));
//                if(pcs.length < 4 && !isBusy) {
                System.out.println(pcs.length + " Loop, isBusy: " + isBusy + "\n" + s + "\n" + debugInfoProvider.getCpuState(""));
//                }
            }
        }
    }

    public static boolean isBusyLoop(final Predicate<Integer> isLoopOpcode, final int[] opcodes) {
        for (int i = 0; i < opcodes.length; i++) {
            if (!isLoopOpcode.test(opcodes[i])) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIgnore(final Predicate<Integer> isIgnoredOpcode, final int[] opcodes) {
        for (int i = 0; i < opcodes.length; i++) {
            if (isIgnoredOpcode.test(opcodes[i])) {
                return true;
            }
        }
        return false;
    }
}
