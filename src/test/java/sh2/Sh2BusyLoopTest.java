package sh2;

import omegadrive.cpu.CpuFastDebug;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sh2.dict.S32xDict;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.sh2.drc.Sh2Block;
import sh2.sh2.prefetch.Sh2Prefetch;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2BusyLoopTest {

    private Sh2Context sh2Context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);

    @Test
    public void testDetectBusyLoop() {
        int[] opcodes;
        clearSh2Context();

        /**
         * bsr
         * nop
         */
        opcodes = new int[]{0xaffe, 0x9};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isPollSequence(opcodes));
        Assertions.assertTrue(isBusyLoopSequence(opcodes));

        /**
         * nop
         * bsr
         * nop
         */
        opcodes = new int[]{0x9, 0xaffe, 0x9};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isPollSequence(opcodes));
        Assertions.assertTrue(isBusyLoopSequence(opcodes));

        opcodes = new int[]{0x9, 0xaffd, 0x9};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isPollSequence(opcodes));
        Assertions.assertTrue(isBusyLoopSequence(opcodes));
    }

    @Test
    public void testDetectPolling() {
        int[] opcodes;
        /**
         *          * M 06001092	6202	mov.l @R0, R2
         *          * M 06001094	3120	cmp/eq R2, R1
         *          * M 06001096	89fc	bt H'06001092
         */
        clearSh2Context();
        setReg(sh2Context, 0, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6202, 0x3120, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          * M 06000296	c608	mov.l @(8, GBR), R0
         *          * M 06000298	8800	cmp/eq H'00, R0
         *          * M 0600029a	8bfc	bf H'06000296
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc608, 0x8800, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          * M 0204b1f0	841a	mov.b @(10, R1), R0
         *          * M 0204b1f2	2028	tst R2, R0
         *          * M 0204b1f4	89fc	bt H'0204b1f0
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x841a, 0x2028, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          * S 06008f4a	3142	cmp/hs R4, R1
         *          * S 06008f4c	8ffd	bf/s H'06008f4a
         *          * S 06008f4e	6102	mov.l @R0, R1
         */
        clearSh2Context();
        setReg(sh2Context, 0, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x3142, 0x8ffd, 0x6102};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          * S 06009122	c510	mov.w @(16, GBR), R0
         *          * S 06009124	3010	cmp/eq R1, R0
         *          * S 06009126	8bfc	bf H'06009122
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc510, 0x3010, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          * M 06008ce8	6011	mov.w @R1, R0
         *          * M 06008cea	8802	cmp/eq H'02, R0
         *          * M 06008cec	8bfc	bf H'06008ce8
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6011, 0x8802, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          M 0601102e	85e5	mov.w @(5, R14), R0
         *          M 06011030	2018	tst R1, R0
         *          M 06011032	8bfc	bf H'0601102e
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x85e5, 0x2018, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          M 06004930	c420	mov.b @(32, GBR), R0
         *          M 06004932	2008	tst R0, R0
         *          M 06004934	89fc	bt H'06004930
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc420, 0x2008, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         *          S 06003ba6	c608	mov.l @(8, GBR), R0
         *          S 06003ba8	4015	cmp/pl R0
         *          S 06003baa	8bfc	bf H'06003ba6
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc608, 0x4015, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        /**
         * M 060008c6	50e8	mov.l @(8, R14), R0
         * M 060008c8	2008	tst R0, R0
         * M 060008ca	8bfc	bf H'060008c6
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x50e8, 0x2008, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /** M 06000a24	6011	mov.w @R1, R0
         * M 06000a26	4011	cmp/pz R0
         * M 06000a28	89fc	bt H'06000a24
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6011, 0x4011, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));

        /**
         * S 06003686	3210	cmp/eq R1, R2
         * S 06003688	8dfd	bt/s H'06003686
         * S 0600368a	6202	mov.l @R0, R2
         */
        clearSh2Context();
        setReg(sh2Context, 0, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x3210, 0x8dfd, 0x6202};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        
         /*
           M 06000742	c802	tst H'02, R0
         * M 06000744	8dfd	bt/s H'06000742
         * M 06000746	5013	mov.l @(3, R1), R0
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0xc802, 0x8dfd, 0x5013};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /* M 06000c4a	84ea	mov.b @(10, R14), R0
         * M 06000c4c	c880	tst H'80, R0
         * M 06000c4e	89fc	bt H'06000c4a
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x84ea, 0xc880, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /* M 06000efc	6010	mov.b @R1, R0
         * M 06000efe	2008	tst R0, R0
         * M 06000f00	8bfc	bf H'06000efc
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6010, 0x2008, 0x8bfc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         * M 06002b22	8ffe	bf/s H'06002b22
         * M 06002b24	cc01	tst.b H'01, @(R0, GBR)
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0x8ffe, 0xcc01};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         * M 06001d2c	5010	mov.l @(0, R1), R0
         * M 06001d2e	3406	cmp/hi R0, R4
         * M 06001d30	89fc	bt H'06001d2c
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x5010, 0x3406, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         *   * M 02001fb0	6232	mov.l @R3, R2
         *     M 02001fb2	3126	cmp/hi R2, R1
         *     M 02001fb4	89fc	bt H'02001fb0
         */
        clearSh2Context();
        setReg(sh2Context, 3, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6232, 0x3126, 0x89fc};
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
    }


    @Test
    public void testIgnore() {
        int[] opcodes;

        /**
         * M 060038d8	3c2c	add R2, R12
         * M 060038da	4d10	dt R13
         * M 060038dc	8bfc	bf H'060038d8
         */
        opcodes = new int[]{0x3c2c, 0x4d10, 0x8bfc};
        Assertions.assertFalse(isPollOrBusyLoop(opcodes));
        Assertions.assertTrue(isIgnored(opcodes));
    }

    private boolean isPollOrBusyLoop(int[] opcodes) {
        return CpuFastDebug.isBusyLoop(Sh2Debug.isLoopOpcode, opcodes);
    }

    private boolean isPollSequence(int[] opcodes) {
        Ow2DrcOptimizer.clear();
        return getPollType(opcodes).ordinal() > Ow2DrcOptimizer.PollType.BUSY_LOOP.ordinal();
    }

    private boolean isBusyLoopSequence(int[] opcodes) {
        Ow2DrcOptimizer.clear();
        return getPollType(opcodes) == Ow2DrcOptimizer.PollType.BUSY_LOOP;
    }

    private Ow2DrcOptimizer.PollType getPollType(int[] opcodes) {
        Sh2Block block = new Sh2Block();
        block.prefetchPc = sh2Context.PC;
        block.prefetchWords = opcodes;
        block.prefetchLenWords = opcodes.length;
        block.drcContext = new Sh2Prefetch.Sh2DrcContext();
        block.drcContext.sh2Ctx = sh2Context;
        block.drcContext.cpu = sh2Context.cpuAccess;
        Ow2DrcOptimizer.pollDetector(block);
        return block.pollType;
    }

    private boolean isIgnored(int[] opcodes) {
        return CpuFastDebug.isIgnore(Sh2Debug.isIgnoreOpcode, opcodes);
    }

    private Sh2Context clearSh2Context() {
        Ow2DrcOptimizer.clear();
        sh2Context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);
        return sh2Context;
    }

    private Sh2Context setReg(Sh2Context sh2Context, int reg, int val) {
        sh2Context.registers[reg] = val;
        return sh2Context;
    }
}
