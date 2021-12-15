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

import omegadrive.bus.model.GenesisBusProvider;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.DebugMode;

public class MC68000WrapperFastDebug extends MC68000Wrapper {

    private static final Logger LOG = LogManager.getLogger(MC68000WrapperFastDebug.class.getSimpleName());

    public static boolean debug = true;
    private static final int PC_AREAS = 0x10;
    private static final int PC_AREA_SIZE = 0x10_0000;
    private static final int PC_AREA_MASK = PC_AREA_SIZE - 1;

    private DebugMode debugMode = DebugMode.NONE;

    //NOTE RAM could change, ROM as well due to banking
    //00_0000 - 00_4000 ROM
    //FF_0000 - FF_FFFF RAM
    private int[][] pcVisited = new int[PC_AREAS][];
    private int[][] opcodes = new int[PC_AREAS][];

    public MC68000WrapperFastDebug(GenesisBusProvider busProvider) {
        super(busProvider);
        init();
    }

    @Override
    public void init() {
        pcVisited[0x0] = new int[PC_AREA_SIZE];
        pcVisited[0x1] = new int[PC_AREA_SIZE];
        pcVisited[0x2] = new int[PC_AREA_SIZE];
        pcVisited[0x3] = new int[PC_AREA_SIZE];
        pcVisited[0x8] = new int[PC_AREA_SIZE];
        pcVisited[0x9] = new int[PC_AREA_SIZE];
        pcVisited[0xF] = new int[PC_AREA_SIZE];
        opcodes[0x0] = new int[PC_AREA_SIZE];
        opcodes[0x1] = new int[PC_AREA_SIZE];
        opcodes[0x2] = new int[PC_AREA_SIZE];
        opcodes[0x3] = new int[PC_AREA_SIZE];
        opcodes[0x8] = new int[PC_AREA_SIZE];
        opcodes[0x9] = new int[PC_AREA_SIZE];
        opcodes[0xF] = new int[PC_AREA_SIZE];
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

    private void printNewInst(int PC) {
        final int area = PC >> 20;
        final int[] pcv = pcVisited[area];
        final int[] opc = opcodes[area];
        final int opcode = addressSpace.internalReadWord(PC);
        final int prevOpcode = opc[PC & PC_AREA_MASK];
        String val = " [NEW]";
        if (prevOpcode == 0) {
            opc[PC & PC_AREA_MASK] = opcode;
            pcv[PC & PC_AREA_MASK] = 1;
            LOG.info("{}{}", MC68000Helper.dumpOp(m68k), val);
        } else if (prevOpcode != opcode) {
            opc[PC & PC_AREA_MASK] = opcode;
            pcv[PC & PC_AREA_MASK] = 1;
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