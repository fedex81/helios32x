package sh2.sh2.drc;

import com.google.common.collect.Range;
import omegadrive.util.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.sh2.Sh2Helper;
import sh2.sh2.Sh2MultiTestBase;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.dict.S32xDict.SH2_SDRAM_MASK;
import static sh2.dict.S32xDict.SH2_START_SDRAM;
import static sh2.sh2.drc.Sh2Block.INVALID_BLOCK;
import static sh2.sh2.drc.Sh2DrcDecodeTest.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class Sh2BlockInvalidateTest extends Sh2MultiTestBase {

    private int pc = 0x100;
    private final boolean verbose = true;


    static {
        config = configCacheEn;
    }

    protected static Stream<Sh2.Sh2Config> fileProvider() {
        return Arrays.stream(configList).filter(c -> c.prefetchEn && c.drcEn);//.limit(1);
    }

    @ParameterizedTest
    @MethodSource("fileProvider")
    public void testDrc(Sh2.Sh2Config c) {
        System.out.println("Testing: " + c);
        resetCacheConfig(c);
        testDrcTrace(trace1, trace1Ranges);
        resetCacheConfig(c);
        testDrcTrace(trace2, trace2Ranges);
        resetCacheConfig(c);
        testDrcTrace(trace3, trace3Ranges);
    }

    private int cnt = 0;

    private void testDrcTrace(int[] trace, Range<Integer>[] blockRanges) {
        Range<Integer>[] blockRangesSdram =
                (Range<Integer>[]) Arrays.stream(blockRanges).map(br -> Range.closed(SH2_START_SDRAM | br.lowerEndpoint(),
                        SH2_START_SDRAM | br.upperEndpoint())).toArray(Range[]::new);
        System.out.println("Trace: " + Arrays.toString(trace));
        for (var br : blockRangesSdram) {
            System.out.println("Block: " + br);
            int blockEndExclude = br.upperEndpoint();
            for (int writeAddr = br.lowerEndpoint() - 5; writeAddr < blockEndExclude + 2; writeAddr++) {
                for (Size size : Size.vals) {
                    //avoid addressError
                    boolean skip = (writeAddr & 1) == 1 && size != Size.BYTE || (writeAddr & 3) != 0 && size == Size.LONG;
                    if (skip) {
                        continue;
                    }
                    Range writeRange = Range.closed(writeAddr, writeAddr + (size.getByteSize() - 1));
                    setTrace(trace, masterCtx);
                    sh2.run(masterCtx);
                    sh2.run(masterCtx);
                    sh2.run(masterCtx);

                    int memMapPc = br.lowerEndpoint();
                    Sh2Helper.Sh2PcInfoWrapper wrapper = Sh2Helper.getOrDefault(memMapPc, MASTER);
                    checkWrapper(wrapper, memMapPc, SH2_SDRAM_MASK);
                    Sh2Block preBlock = wrapper.block;
                    boolean blockStillValid = noOverlapRanges(br, writeRange);

                    if (verbose)
                        System.out.println(cnt + ", blockPc: " + memMapPc + ", write: " + writeAddr + " " + size +
                                ",blockRange: " + br + ",writeRange: " + writeRange +
                                ",invalidate: " + !blockStillValid + ",block: " + preBlock);

                    Md32xRuntimeData.setAccessTypeExt(MASTER);
                    memory.write(writeAddr, (0xFF << 16) | 0xFF, size);

                    //previous block becomes invalid
                    Assertions.assertEquals(blockStillValid, preBlock.isValid());
                    //current block gets reset to the INVALID_BLOCK
                    Assertions.assertEquals(blockStillValid ? preBlock : INVALID_BLOCK, wrapper.block);

                    //check all other blocks
                    for (var br1 : blockRangesSdram) {
                        if (br1 == br) {
                            continue;
                        }
                        Sh2Helper.Sh2PcInfoWrapper w = Sh2Helper.getOrDefault(br1.lowerEndpoint(), MASTER);
                        blockStillValid = noOverlapRanges(br1, writeRange);
                        if (verbose)
                            System.out.println(cnt + ", blockPc: " + br1.lowerEndpoint() + ", write: " + writeAddr + " " + size +
                                    ",blockRange: " + br1 + ",writeRange: " + writeRange + ",invalidate: " + !blockStillValid +
                                    ",block: " + w.block);
                        Assertions.assertEquals(blockStillValid, w.block.isValid());
                        if (!blockStillValid) {
                            Assertions.assertEquals(INVALID_BLOCK, w.block);
                        }
                    }
                    cnt++;
                }
            }
        }
    }

    @Override
    @BeforeEach
    public void before() {
        super.before();
        IMemory.MemoryDataCtx mdc = lc.memory.getMemoryDataCtx();
        int sp = mdc.rom.capacity() - 4;
        ByteBuffer bios = mdc.bios[MASTER.ordinal()].buffer;
        bios.putInt(0, SH2_START_SDRAM | pc);
        bios.putInt(4, SH2_START_SDRAM | sp);
    }

    private boolean noOverlapRanges(Range blockRange, Range writeRange) {
        boolean noOverlap = true;
        if (blockRange.isConnected(writeRange)) {
            noOverlap = blockRange.intersection(writeRange).isEmpty();
        }
        return noOverlap;
    }

    private void setTrace(int[] trace, Sh2Context context) {
        ByteBuffer ram = memory.getMemoryDataCtx().sdram;
        for (int i = 0; i < trace.length; i++) {
            ram.putShort(pc + (i << 1), (short) trace[i]);
        }
        sh2.reset(context);
    }
}
