package sh2.sh2;

import omegadrive.cpu.CpuFastDebug;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.sh2.drc.Sh2Block;

import java.util.Arrays;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.SH2_PC_AREA_SHIFT;
import static sh2.sh2.Sh2Debug.createContext;
import static sh2.sh2.drc.Ow2DrcOptimizer.UNKNOWN_POLLER;

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

    public final static class Sh2PcInfoWrapper extends CpuFastDebug.PcInfoWrapper {

        public Sh2Block block = Sh2Block.INVALID_BLOCK;
        public Ow2DrcOptimizer.PollerCtx poller = UNKNOWN_POLLER;
        private static final boolean verbose = false;

        public Sh2PcInfoWrapper(int area, int pcMasked) {
            super(area, pcMasked);
        }

        public void setBlock(Sh2Block block) {
            this.block = block;
            if (verbose && block == Sh2Block.INVALID_BLOCK) {
                LOG.info("set invalid block: {} {}", th(area), th(pcMasked));
            }
        }

        public void invalidateBlock() {
            if (verbose) LOG.info("Invalidate pc: {} {}", th(area), th(pcMasked));
            if (poller != UNKNOWN_POLLER) {
                if (verbose) LOG.info("Poller: {}", poller);
                poller.invalidate();
                poller = UNKNOWN_POLLER;
            }
            if (block != Sh2Block.INVALID_BLOCK) {
                if (verbose) LOG.info("{} Block: {}", block.drcContext.cpu, block);
                block.invalidate();
                block = Sh2Block.INVALID_BLOCK;
            }
        }
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
            if (pcAreaSize > 1) {
                pcInfoWrapper[i] = new Sh2PcInfoWrapper[pcAreaSize];
                Arrays.fill(pcInfoWrapper[i], SH2_NOT_VISITED);
            }
        }
        return pcInfoWrapper;
    }

    /**
     * area = pc >>> SH2_PC_AREA_SHIFT;
     * pcMasked = pc & pcAreaMaskMap[area]
     */
    public static Sh2PcInfoWrapper get(int pc, CpuDeviceAccess cpu) {
        getPcInfoWrapper();
        assert (pc & 1) == 0 : th(pc);
        final int piwPc = pc | cpu.ordinal();
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