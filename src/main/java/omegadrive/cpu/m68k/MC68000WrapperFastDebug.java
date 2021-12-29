/*
 * MC68000WrapperDebug
 * Copyright (c) 2018-2019 Federico Berti
 * Last modified: 12/07/19 20:51
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package omegadrive.cpu.m68k;

import com.google.common.collect.ImmutableSet;
import omegadrive.bus.model.GenesisBusProvider;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.DebugMode;

import java.util.Arrays;
import java.util.Set;

public class MC68000WrapperFastDebug extends MC68000Wrapper {

    private static final Logger LOG = LogManager.getLogger(MC68000WrapperFastDebug.class.getSimpleName());

    public static boolean debug = true;
    private static final int PC_AREAS = 0x10;
    private static final int PC_AREA_SIZE = 0x10_0000;
    private static final int PC_AREA_MASK = PC_AREA_SIZE - 1;

    private DebugMode debugMode = DebugMode.NEW_INST_ONLY;

    //NOTE RAM could change, ROM as well due to banking
    //00_0000 - 00_4000 ROM
    //FF_0000 - FF_FFFF RAM
    static final int[] areas = {0, 1, 2, 3, 8, 9, 0xF};
    private int[][] pcVisited = new int[PC_AREAS][];
    private int[][] opcodes = new int[PC_AREAS][];
    private Set<Object> arraySet = ImmutableSet.of(pcVisited, opcodes);

    public MC68000WrapperFastDebug(GenesisBusProvider busProvider) {
        super(busProvider);
        init();
    }

    @Override
    public void init() {
        for (Object o : arraySet) {
            Arrays.stream(areas).forEach(idx -> ((int[][]) o)[idx] = new int[PC_AREA_SIZE]);
        }
    }

    @Override
    public int runInstruction() {
        int res = 0;
        try {
            currentPC = m68k.getPC(); //needs to be set
            printDebugMaybe();
            res = super.runInstruction();

        } catch (Exception e) {
            LOG.error("68k error", e);
        }
        return res;
    }

    protected void printDebugMaybe() {
        if (debug) {
            switch (debugMode) {
                case STATE:
                    printCpuState("");
                    break;
                case INST_ONLY:
                    printInstOnly();
                    break;
                case NEW_INST_ONLY:
                    printNewInst(currentPC);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public boolean raiseInterrupt(int level) {
        m68k.raiseInterrupt(level);
        boolean raise = m68k.getInterruptLevel() == level;
        if (raise) {
//            LOG.info("M68K before INT, level: {}, newLevel: {}", m68k.getInterruptLevel(), level);
        }
        return raise;
    }

    protected void handleException(int vector) {
        if (vector == LEV4_EXCEPTION || vector == LEV6_EXCEPTION) {
            return;
        }
        if (vector == ILLEGAL_ACCESS_EXCEPTION) {
            printCpuState("Exception: " + vector);
            if (MC68000Helper.STOP_ON_EXCEPTION) {
                setStop(true);
            }
        }
    }

    private void printNewInst(int pc) {
        pc &= 0xFF_FFFF;
        final int area = pc >> 20;
        final int[] pcv = pcVisited[area];
        final int[] opc = opcodes[area];
        final int opcode = addressSpace.internalReadWord(pc);
        final int prevOpcode = opc[pc & PC_AREA_MASK];
        String val = " [NEW]";
        if (prevOpcode == 0) {
            opc[pc & PC_AREA_MASK] = opcode;
            pcv[pc & PC_AREA_MASK] = 1;
            LOG.info("{}{}", MC68000Helper.dumpOp(m68k), val);
        } else if (prevOpcode != opcode) {
            opc[pc & PC_AREA_MASK] = opcode;
            pcv[pc & PC_AREA_MASK] = 1;
            val = " [NEW-R]";
            LOG.info("{}{}", MC68000Helper.dumpOp(m68k), val);
        }
    }

    protected void printCpuState(String head) {
        MC68000Helper.printCpuState(m68k, Level.INFO, head, addressSpace.size());
    }

    protected void printInstOnly() {
        try {
            LOG.info(MC68000Helper.dumpOp(m68k));
        } catch (Exception e) {
            String pc = Long.toHexString(m68k.getPC() & 0xFF_FFFF);
            LOG.warn("Unable to dump the instruction: {}", pc, e);
        }
    }
}