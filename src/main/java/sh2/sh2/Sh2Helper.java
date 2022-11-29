package sh2.sh2;

import omegadrive.cpu.CpuFastDebug;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.drc.Sh2Block;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.SH2_PC_AREA_SHIFT;
import static sh2.sh2.Sh2Debug.createContext;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Helper {

    private static final Logger LOG = LogHelper.getLogger(Sh2Helper.class.getSimpleName());
    public static final Sh2Disassembler disasm = new Sh2Disassembler();
    private static final String simpleFormat = "%s %08x\t%04x\t%s";


    public static final Sh2PcInfoWrapper SH2_NOT_VISITED = new Sh2PcInfoWrapper(0, 0);
    private static Sh2PcInfoWrapper[][] piwArr;
    private static final Sh2PcInfoWrapper[] empty = new Sh2PcInfoWrapper[0];

    public final static class Sh2PcInfoWrapper extends CpuFastDebug.PcInfoWrapper {

        public Sh2Block block = Sh2Block.INVALID_BLOCK;
        public Map<Integer, Sh2Block> knownBlocks = Collections.emptyMap();
        private static final boolean verbose = false;

        public Sh2PcInfoWrapper(int area, int pcMasked) {
            super(area, pcMasked);
        }

        public void setBlock(Sh2Block block) {
            assert this != SH2_NOT_VISITED;
            this.block = block;
            if (verbose && block == Sh2Block.INVALID_BLOCK) {
                LOG.info("set invalid block: {} {}", th(area), th(pcMasked));
            }
        }

        public void invalidateBlock() {
            if (verbose) LOG.info("Invalidate pc: {} {}", th(area), th(pcMasked));
            if (block != Sh2Block.INVALID_BLOCK) {
                if (verbose) LOG.info("{} Block: {}", block.drcContext.cpu, block);
                block.invalidate();
                block = Sh2Block.INVALID_BLOCK;
            }
        }

        public Sh2Block addToKnownBlocks(Sh2Block b) {
            assert this != SH2_NOT_VISITED;
            if (knownBlocks == Collections.EMPTY_MAP) {
                knownBlocks = new HashMap<>(2);
            }
            return knownBlocks.put(b.hashCodeWords, b);
        }
    }


    public static void clear() {
        piwArr = createWrapper(createContext());
    }

    /**
     * Even indexes -> MASTER pc
     * Odd indexes  -> SLAVE pc, actual PC is pc & ~1
     */
    public static Sh2PcInfoWrapper[][] getPcInfoWrapper() {
        if (piwArr == null) {
            piwArr = createWrapper(createContext());
        }
        return piwArr;
    }

    private static Sh2PcInfoWrapper[][] createWrapper(CpuFastDebug.CpuDebugContext ctx) {
        Sh2PcInfoWrapper[][] pcInfoWrapper = new Sh2PcInfoWrapper[ctx.pcAreasNumber][0];

        for (int i = 0; i < ctx.pcAreasMaskMap.length; ++i) {
            int pcAreaSize = ctx.pcAreasMaskMap[i] + 1;
            pcInfoWrapper[i] = empty;
            if (pcAreaSize > 1) {
                pcInfoWrapper[i] = new Sh2PcInfoWrapper[pcAreaSize];
                Arrays.fill(pcInfoWrapper[i], SH2_NOT_VISITED);
            }
        }
        return pcInfoWrapper;
    }

    public static boolean isValidPc(int pc, CpuDeviceAccess cpu) {
        getPcInfoWrapper();
        assert (pc & 1) == 0 : th(pc);
        final int piwPc = pc | cpu.ordinal();
        return piwArr[piwPc >>> SH2_PC_AREA_SHIFT].length > 0;
    }

    public static Sh2PcInfoWrapper getOrDefault(int pc, CpuDeviceAccess cpu) {
        getPcInfoWrapper();
        assert (pc & 1) == 0 : th(pc);
        final int piwPc = pc | cpu.ordinal();
        final Sh2PcInfoWrapper[] piwSubArr = piwArr[piwPc >>> SH2_PC_AREA_SHIFT];
        if (piwSubArr.length == 0) {
            return SH2_NOT_VISITED;
        }
        //TODO cache-through vs cached
        Sh2PcInfoWrapper piw = piwSubArr[piwPc & Sh2Debug.pcAreaMaskMap[piwPc >>> SH2_PC_AREA_SHIFT]];
        assert (piw != SH2_NOT_VISITED
                ? piw.pcMasked == (pc & Sh2Debug.pcAreaMaskMap[pc >>> SH2_PC_AREA_SHIFT]) : true) : th(piwPc) + "," + th(piw.pcMasked);
        return piw;
    }

    /**
     * area = pc >>> SH2_PC_AREA_SHIFT;
     * pcMasked = pc & pcAreaMaskMap[area]
     */
    public static Sh2PcInfoWrapper get(int pc, CpuDeviceAccess cpu) {
        getPcInfoWrapper();
        assert (pc & 1) == 0 : th(pc);
        final int piwPc = pc | cpu.ordinal();
        //TODO cache-through vs cached
        Sh2PcInfoWrapper piw = piwArr[piwPc >>> SH2_PC_AREA_SHIFT][piwPc & Sh2Debug.pcAreaMaskMap[piwPc >>> SH2_PC_AREA_SHIFT]];
        assert (piw != SH2_NOT_VISITED
                ? piw.pcMasked == (pc & Sh2Debug.pcAreaMaskMap[pc >>> SH2_PC_AREA_SHIFT]) : true) : th(piwPc) + "," + th(piw.pcMasked);
        return piw;
    }

    /**
     * area = pc >>> SH2_PC_AREA_SHIFT;
     * pcMasked = pc & pcAreaMaskMap[area]
     */
    public static Sh2PcInfoWrapper getOrCreate(int pc, CpuDeviceAccess cpu) {
        Sh2PcInfoWrapper piw = get(pc, cpu);
        assert piw != null;
        if (piw == SH2_NOT_VISITED) {
            final int piwPc = pc | cpu.ordinal();
            piw = new Sh2PcInfoWrapper(pc >>> SH2_PC_AREA_SHIFT, pc & Sh2Debug.pcAreaMaskMap[pc >>> SH2_PC_AREA_SHIFT]);
            piwArr[piw.area][piw.pcMasked | cpu.ordinal()] = piw;
        }
        assert piw.pcMasked == (pc & Sh2Debug.pcAreaMaskMap[pc >>> SH2_PC_AREA_SHIFT]);
        return piw;
    }

    public static void printInst(Sh2Context ctx) {
        System.out.println(getInstString(ctx));
    }

    public static String getInstString(Sh2Context ctx) {
        assert ctx.opcode > 0;
        return String.format(simpleFormat, ctx.sh2TypeCode, ctx.PC, ctx.opcode, disasm.disassemble(ctx.PC, ctx.opcode));
    }

    public static String getInstString(int pc, int opcode) {
        return disasm.disassemble(pc, opcode);
    }

    public static String getInstString(String sh2Type, int pc, int opcode) {
        return String.format(simpleFormat, sh2Type, pc, opcode, disasm.disassemble(pc, opcode));
    }

    public static void printState(Sh2Context ctx) {
        System.out.println(toDebuggingString(ctx));
    }

    public static StringBuilder toListOfInst(Sh2Block ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.prefetchWords.length; i++) {
            int pc = ctx.start + (i << 1);
            pc = pc != ctx.pcMasked ? pc : ctx.prefetchPc;
            sb.append(Sh2Helper.getInstString("", pc, ctx.prefetchWords[i])).append("\n");
        }
        return sb;
    }

    public static String toDebuggingString(Sh2Context ctx) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append(disasm.disassemble(ctx.PC, ctx.opcode)).append("\n");
        sb.append(String.format("PC : %08x\t", ctx.PC));
        sb.append(String.format("GBR: %08x\t", ctx.GBR));
        sb.append(String.format("VBR: %08x\t", ctx.VBR));
        sb.append(String.format("SR : %08x\t", ctx.SR));

        sb.append(((ctx.SR & Sh2.flagT) != 0 ? "T" : "-") + ((ctx.SR & Sh2.flagS) != 0 ? "S" : "-") +
                ((ctx.SR & Sh2.flagQ) != 0 ? "Q" : "-") + (((ctx.SR & Sh2.flagM) != 0 ? "M" : "-")));
        sb.append("\n");


        for (int i = 0; i < 16; i++) {
            sb.append(String.format("R%02d: %08x\t", i, ctx.registers[i]));
            if (i == 7) {
                sb.append("\n");
            }
        }
        sb.append("\n");
        sb.append(String.format("MCH: %08x\t", ctx.MACH));
        sb.append(String.format("MCL: %08x\t", ctx.MACL));
        sb.append(String.format("PR : %08x\t", ctx.PR));
        sb.append("\n");
        return sb.toString();
    }
}