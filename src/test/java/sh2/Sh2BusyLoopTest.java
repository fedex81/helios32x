package sh2;

import omegadrive.cpu.CpuFastDebug;
import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sh2.dict.S32xDict;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Debug;
import sh2.sh2.drc.Ow2DrcOptimizer;
import sh2.sh2.drc.Ow2Sh2Bytecode;
import sh2.sh2.drc.Sh2Block;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.util.Arrays;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2BusyLoopTest {

    private Sh2Context sh2Context = new Sh2Context(S32xUtil.CpuDeviceAccess.MASTER);

    private int[] opcodes;
    @Test
    public void testDetectBusyLoop() {
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
        assertPollBusyLoop(opcodes);

        /**
         *          * M 06000296	c608	mov.l @(8, GBR), R0
         *          * M 06000298	8800	cmp/eq H'00, R0
         *          * M 0600029a	8bfc	bf H'06000296
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc608, 0x8800, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         *          * M 0204b1f0	841a	mov.b @(10, R1), R0
         *          * M 0204b1f2	2028	tst R2, R0
         *          * M 0204b1f4	89fc	bt H'0204b1f0
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x841a, 0x2028, 0x89fc};
        assertPollBusyLoop(opcodes);

        /**
         *          * S 06008f4a	3142	cmp/hs R4, R1
         *          * S 06008f4c	8ffd	bf/s H'06008f4a
         *          * S 06008f4e	6102	mov.l @R0, R1
         */
        clearSh2Context();
        setReg(sh2Context, 0, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x3142, 0x8ffd, 0x6102};
        assertPollBusyLoop(opcodes);

        /**
         *          * S 06009122	c510	mov.w @(16, GBR), R0
         *          * S 06009124	3010	cmp/eq R1, R0
         *          * S 06009126	8bfc	bf H'06009122
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc510, 0x3010, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         *          * M 06008ce8	6011	mov.w @R1, R0
         *          * M 06008cea	8802	cmp/eq H'02, R0
         *          * M 06008cec	8bfc	bf H'06008ce8
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6011, 0x8802, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         *          M 0601102e	85e5	mov.w @(5, R14), R0
         *          M 06011030	2018	tst R1, R0
         *          M 06011032	8bfc	bf H'0601102e
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x85e5, 0x2018, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         *          M 06004930	c420	mov.b @(32, GBR), R0
         *          M 06004932	2008	tst R0, R0
         *          M 06004934	89fc	bt H'06004930
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc420, 0x2008, 0x89fc};
        assertPollBusyLoop(opcodes);

        /**
         *          S 06003ba6	c608	mov.l @(8, GBR), R0
         *          S 06003ba8	4015	cmp/pl R0
         *          S 06003baa	8bfc	bf H'06003ba6
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc608, 0x4015, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         * M 060008c6	50e8	mov.l @(8, R14), R0
         * M 060008c8	2008	tst R0, R0
         * M 060008ca	8bfc	bf H'060008c6
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x50e8, 0x2008, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /** M 06000a24	6011	mov.w @R1, R0
         * M 06000a26	4011	cmp/pz R0
         * M 06000a28	89fc	bt H'06000a24
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6011, 0x4011, 0x89fc};
        assertPollBusyLoop(opcodes);

        /**
         * S 06003686	3210	cmp/eq R1, R2
         * S 06003688	8dfd	bt/s H'06003686
         * S 0600368a	6202	mov.l @R0, R2
         */
        clearSh2Context();
        setReg(sh2Context, 0, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x3210, 0x8dfd, 0x6202};
        assertPollBusyLoop(opcodes);
        
         /*
           M 06000742	c802	tst H'02, R0
         * M 06000744	8dfd	bt/s H'06000742
         * M 06000746	5013	mov.l @(3, R1), R0
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0xc802, 0x8dfd, 0x5013};
        assertPollBusyLoop(opcodes);

        /* M 06000c4a	84ea	mov.b @(10, R14), R0
         * M 06000c4c	c880	tst H'80, R0
         * M 06000c4e	89fc	bt H'06000c4a
         */
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x84ea, 0xc880, 0x89fc};
        assertPollBusyLoop(opcodes);

        /* M 06000efc	6010	mov.b @R1, R0
         * M 06000efe	2008	tst R0, R0
         * M 06000f00	8bfc	bf H'06000efc
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6010, 0x2008, 0x8bfc};
        assertPollBusyLoop(opcodes);

        /**
         * M 06002b22	8ffe	bf/s H'06002b22
         * M 06002b24	cc01	tst.b H'01, @(R0, GBR)
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0x8ffe, 0xcc01};
        assertPollBusyLoop(opcodes);

        /**
         * M 06001d2c	5010	mov.l @(0, R1), R0
         * M 06001d2e	3406	cmp/hi R0, R4
         * M 06001d30	89fc	bt H'06001d2c
         */
        clearSh2Context();
        setReg(sh2Context, 1, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x5010, 0x3406, 0x89fc};
        assertPollBusyLoop(opcodes);

        /**
         *   * M 02001fb0	6232	mov.l @R3, R2
         *     M 02001fb2	3126	cmp/hi R2, R1
         *     M 02001fb4	89fc	bt H'02001fb0
         */
        clearSh2Context();
        setReg(sh2Context, 3, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6232, 0x3126, 0x89fc};
        assertPollBusyLoop(opcodes);
    }

    @Test
    public void testPollDetect() {
        /**
         star wars
         06000bbe	85e5	mov.w @(5, R14), R0
         00000bc0	c901	and H'01, R0
         00000bc2	3100	cmp/eq R0, R1
         00000bc4	8bfb	bf H'00000bbe
         **/
        clearSh2Context();
        setReg(sh2Context, 14, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x85e5, 0xc901, 0x3100, 0x8bfb};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         * Doom res
         * SLAVE Poll ignored at PC 600102a: 0 UNKNOWN
         * 0600102a	6981	mov.w @R8, R9
         * 0000102c	29c8	tst R12, R9
         * 0000102e	8dfc	bt/s H'0000102a
         * 00001030	6098	swap.b R9, R0
         */
        clearSh2Context();
        setReg(sh2Context, 8, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6981, 0x29c8, 0x8dfc, 0x6098};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /*
         *      Darxide
         *      02099312	6161	mov.w @R6, R1
         *      00099314	2129	and R2, R1
         *      00099316	611d	extu.w R1, R1
         *      00099318	2118	tst R1, R1
         *      0009931a	89fa	bt H'00099312
         */
        clearSh2Context();
        setReg(sh2Context, 6, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6161, 0x2129, 0x611d, 0x2118, 0x89fa};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         * Darxide
         *  0600cd3e	c513	mov.w @(19, GBR), R0
         *  0000cd40	2079	and R7, R0
         *  0000cd42	3010	cmp/eq R1, R0
         *  0000cd44	8ffb	bf/s H'0000cd3e
         *  0000cd46	6103	mov R0, R1
         */
        clearSh2Context();
        sh2Context.GBR = S32xDict.SH2_START_SDRAM;
        opcodes = new int[]{0xc513, 0x2079, 0x3010, 0x8ffb, 0x6103};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /*
         *      stellar assault
         *      0601c6ca	848b	mov.b @(11, R8), R0
         *      0001c6cc	2098	tst R9, R0
         *      0001c6ce	8ffc	bf/s H'0001c6ca
         *      0001c6d0	6043	mov R4, R0
         */
        clearSh2Context();
        setReg(sh2Context, 8, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x848b, 0x2098, 0x8ffc, 0x6043};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /*
         *      32xcolor
         *      06000024	6121	mov.w @R2, R1
         *      00000026	611f	exts.w R1, R1
         *      00000028	4111	cmp/pz R1
         *      0000002a	89fb	bt H'00000024
         */
        clearSh2Context();
        setReg(sh2Context, 2, S32xDict.SH2_START_SDRAM);
        opcodes = new int[]{0x6121, 0x611f, 0x4111, 0x89fb};
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));

        /**
         * Doom res
         *
         *      02015b40	6031	mov.w @R3, R0
         *      00015b42	6121	mov.w @R2, R1
         *      00015b44	611d	extu.w R1, R1
         *      00015b46	c901	and H'01, R0
         *      00015b48	3010	cmp/eq R1, R0
         *      00015b4a	8bf9	bf H'00015b40
         *
         *      Brutal
         *      MASTER Poll ignored at PC 6002736: 0 UNKNOWN
         *      06002736	c608	mov.l @(8, GBR), R0
         *      00002738	d108	mov.l @(H'0000275c), R1
         *      0000273a	3100	cmp/eq R0, R1
         *      0000273c	8bfb	bf H'00002736
         *
         *      060003fc	c608	mov.l @(8, GBR), R0
         *      000003fe	6303	mov R0, R3
         *      00000400	2019	and R1, R0
         *      00000402	cb20	or H'20, R0
         *      00000404	3020	cmp/eq R2, R0
         *      00000406	8bf9	bf H'000003fc
         *
         *      FIFA 96
         *      SLAVE Poll detected at PC 6000690: 44e1 NONE
         *      06000690	e08c	mov H'ffffff8c, R0
         *      00000692	6002	mov.l @R0, R0
         *      00000694	c802	tst H'02, R0
         *      00000696	89fb	bt H'00000690
         *
         *      MASTER Poll ignored at PC 6000b22: 0 UNKNOWN
         *      06000b22	d02b	mov.l @(H'06000bd0), R0
         *      00000b24	6002	mov.l @R0, R0
         *      00000b26	d12b	mov.l @(H'00000bd4), R1
         *      00000b28	3100	cmp/eq R0, R1
         *      00000b2a	8ffa	bf/s H'00000b22
         *      00000b2c	0009	nop
         *
         *      MASTER Poll detected at PC 6000742: ffffff8c NONE
         *      06000742	c802	tst H'02, R0
         *      00000744	8dfd	bt/s H'00000742
         *      00000746	5013	mov.l @(3, R1), R0
         *
         *      knuckles
         *      MASTER Poll ignored at PC 600311e: 0 UNKNOWN
         *      0600311e	d00c	mov.l @(H'06003150), R0
         *      00003120	6001	mov.w @R0, R0
         *      00003122	4011	cmp/pz R0
         *      00003124	89fb	bt H'0000311e
         *
         *      nba jam
         *      SLAVE Poll ignored at PC 6001660: 0 UNKNOWN
         *      06001660	6012	mov.l @R1, R0
         *      00001662	0009	nop
         *      00001664	2008	tst R0, R0
         *      00001666	8bfb	bf H'00001660
         *
         *      sangokushi
         *      SLAVE Poll ignored at PC 6000588: 0 UNKNOWN
         *      06000588	c51c	mov.w @(28, GBR), R0
         *      0000058a	4019	shlr8 R0
         *      0000058c	c880	tst H'80, R0
         *      0000058e	8bfb	bf H'00000588
         *
         *      MASTER Poll ignored at PC 20d2442: 0 UNKNOWN
         *      020d2442	624d	extu.w R4, R2
         *      000d2444	63c1	mov.w @R12, R3
         *      000d2446	633d	extu.w R3, R3
         *      000d2448	3230	cmp/eq R3, R2
         *      000d244a	89fa	bt H'000d2442
         *
         *      star trek
         *      MASTER Poll ignored at PC 6000274: 0 UNKNOWN
         *      06000274	841b	mov.b @(11, R1), R0
         *      00000276	4001	shlr R0
         *      00000278	4001	shlr R0
         *      0000027a	89fb	bt H'00000274
         *
         *      star wars
         *      SLAVE Poll ignored at PC 6000a1c: 0 UNKNOWN
         *      06000a1c	2211	mov.w R1, @R2
         *      00000a1e	6020	mov.b @R2, R0
         *      00000a20	c880	tst H'80, R0
         *      00000a22	89fb	bt H'00000a1c
         *
         *      tempo
         *      MASTER Poll ignored at PC 600d0ca: 0 UNKNOWN
         *      0600d0ca	0009	nop
         *      0000d0cc	0009	nop
         *      0000d0ce	6061	mov.w @R6, R0
         *      0000d0d0	c801	tst H'01, R0
         *      0000d0d2	8bfa	bf H'0000d0ca
         *
         *      vf
         *      MASTER Poll ignored at PC 60007a6: 0 UNKNOWN
         *      060007a6	6010	mov.b @R1, R0
         *      000007a8	0009	nop
         *      000007aa	0009	nop
         *      000007ac	0009	nop
         *      000007ae	0009	nop
         *      000007b0	0009	nop
         *      000007b2	8800	cmp/eq H'00, R0
         *      000007b4	8bf7	bf H'000007a6
         *
         *      wwf raw
         *      busy loop!!
         *      SLAVE Poll ignored at PC 6007e52: 0 UNKNOWN
         *      06007e52	0009	nop
         *      00007e54	0009	nop
         *      00007e56	0009	nop
         *      00007e58	0009	nop
         *      00007e5a	affa	bra H'00007e52
         *      00007e5c	0009	nop
         *
         *      wwf wrestlemania
         *      MASTER Poll ignored at PC 6000564: 0 UNKNOWN
         *      06000564	6070	mov.b @R7, R0
         *      00000566	e180	mov H'ffffff80, R1
         *      00000568	3100	cmp/eq R0, R1
         *      0000056a	8bfb	bf H'00000564
         *
         *      MASTER Poll ignored at PC 6000ab0: 0 UNKNOWN
         *      06000ab0	84e3	mov.b @(3, R14), R0
         *      00000ab2	0009	nop
         *      00000ab4	0009	nop
         *      00000ab6	0009	nop
         *      00000ab8	0009	nop
         *      00000aba	2008	tst R0, R0
         *      00000abc	89f8	bt H'00000ab0
         *
         *
         *      SLAVE Poll detected at PC 60002e4: 20004024 COMM
         *      060002e4	6202	mov.l @R0, R2
         *      000002e6	0009	nop
         *      000002e8	0009	nop
         *      000002ea	3210	cmp/eq R1, R2
         *      000002ec	89fa	bt H'000002e4
         *
         *      32xfire
         *      MASTER Poll ignored at PC 6000110: 0 UNKNOWN
         *      06000110	6011	mov.w @R1, R0
         *      00000112	202a	xor R2, R0
         *      00000114	c801	tst H'01, R0
         *      00000116	89fb	bt H'00000110
         *
         *      Doom RR
         *      MASTER Poll ignored at PC 204d8f4: 0 UNKNOWN
         *      0204d8f4	3200	cmp/eq R0, R2
         *      0004d8f6	6012	mov.l @R1, R0
         *      0004d8f8	8dfc	bt/s H'0004d8f4
         *      0004d8fa	2039	and R3, R0
         *
         *      sonic32x plus
         *      MASTER Poll ignored at PC 6000194: 0 UNKNOWN
         *      06000194	0009	nop
         *      00000196	c511	mov.w @(17, GBR), R0
         *      00000198	8800	cmp/eq H'00, R0
         *      0000019a	89fb	bt H'00000194
         */
    }

    /**
     * NOTE: old style also checks Sh2Debug detection
     */
    private void assertPollBusyLoop(int[] opcodes) {
        Assertions.assertTrue(isPollOrBusyLoop(opcodes));
        Assertions.assertFalse(isIgnored(opcodes));
        Assertions.assertTrue(isPollSequence(opcodes));
        Assertions.assertFalse(isBusyLoopSequence(opcodes));
    }


    /**
     * VR
     * MASTER Poll ignored at PC 6004482: ffffff8c UNKNOWN /ffff8c = DMA_CHCR0
         *  06004482	50e3	mov.l @(3, R14), R0
         *  00004484	c802	tst H'02, R0
         *  00004486	89fc	bt H'00004482
         */

        /**
         * FIFA 96
         *
         * S 06000690	e08c	mov H'ffffff8c, R0 [NEW] //ffff8c = DMA_CHCR0
         * S 06000692	6002	mov.l @R0, R0 [NEW]      //read DMA_CHCR0
         * S 06000694	c802	tst H'02, R0 [NEW]       // while TE=0 (ie. dma in progress)
         *                                               // (R0 & 2 == 0 -> T = 1)
         * S 06000696	89fb	bt H'06000690 [NEW]
         */
        /**
         * Spiderman
         * sh2.sh2.drc.S_6000990_1803010976427.run() 12%
         * S 06000990	5034	mov.l @(4, R3), R0 [NEW] //R3 = 0x60051E8
         * S 06000992	6903	mov R0, R9 [NEW]
         * S 06000994	8800	cmp/eq H'00, R0 [NEW]
         * S 06000996	8d20	bt/s H'060009da [NEW]
         * S 06000998	5130	mov.l @(0, R3), R1 [NEW] //R3 = 0x60051E8
         *
         * S 060009da	7318	add H'18, R3 [NEW]
         * S 060009dc	4410	dt R4 [NEW] //R4 = 4
         * S 060009de	8bd7	bf H'06000990 [NEW]
         */


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
        Sh2Block block = new Sh2Block(sh2Context.PC);
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
        sh2Context.PC = 0x100;
        return sh2Context;
    }

    private Sh2Context setReg(Sh2Context sh2Context, int reg, int val) {
        sh2Context.registers[reg] = val;
        return sh2Context;
    }

    //TODO Optimizer
    static class InstCtx {
        public int start, end;
    }

    private static final InstCtx instCtx = new InstCtx();

    public static InstCtx optimizeMaybe(Sh2Block block, Sh2Prefetch.BytecodeContext ctx) {

        instCtx.start = 0;
        instCtx.end = block.prefetchWords.length;

        if (true) return instCtx;
        /**
         *
         * NOTE: no perf increase when using it, revisit at some point
         *
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
//            LOG.info("{} Optimizing at PC: {}, cyclesConsumed: {}\n{}", block.drcContext.cpu,
//                    th(block.prefetchPc), block.cyclesConsumed,
//                    Sh2Instructions.toListOfInst(block));
        }
        return instCtx;
    }
}
