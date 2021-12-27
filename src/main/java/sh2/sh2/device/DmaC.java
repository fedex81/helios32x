package sh2.sh2.device;

import omegadrive.util.Size;
import omegadrive.vdp.md.VdpFifo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.DmaFifo68k;
import sh2.IMemory;
import sh2.S32xUtil.*;
import sh2.Sh2MMREG;
import sh2.dict.Sh2Dict;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1
 */
public class DmaC {

    private static final Logger LOG = LogManager.getLogger(DmaC.class.getSimpleName());

    private static final int SH2_CHCR_TRANSFER_END_BIT = 1;

    private final ByteBuffer regs;

    private final IMemory memory;
    private final DmaFifo68k dma68k;
    private final VdpFifo fifo;
    private final CpuDeviceAccess cpu;
    private boolean dmaInProgress;

    public DmaC(CpuDeviceAccess cpu, IMemory memory, DmaFifo68k dma68k, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.memory = memory;
        this.dma68k = dma68k;
        this.fifo = dma68k.getFifo();
    }

    public void write(CpuDeviceAccess cpu, int reg, int value, Size size) {
        LOG.info("{} DMA write {}: {} {}", cpu, sh2RegNames[reg & ~1],
                Integer.toHexString(value), size);
        switch (cpu) {
            case MASTER:
            case SLAVE:
                writeSh2(cpu, reg, value, size);
                break;
        }
    }

    public void dmaStep() {
        if (dmaInProgress) {
            dmaOneStep(0);
        }
    }

    private void writeSh2(CpuDeviceAccess cpu, int reg, int value, Size size) {
//        int val1 = readBuffer(regs, reg & SH2_REG_MASK, Size.LONG);
//        if ((val1 & 4) > 0) {
//            LOG.error("{} Interrupt request on DMA complete not supported", sh2Access);
//        }
        switch (reg) {
            case Sh2Dict.DMAOR:
                if ((value & 1) == 1) {
                    int conf = readBufferForChannel(0, DMA_CHCR0 + 2, Size.WORD);
                    int destMode = conf >> 14;
                    int scrMode = (conf >> 12) & 0x3;
                    int transfSize = (conf >> 10) & 0x3;
                    LOG.info("DMA setup: {}", th(conf));
                    dmaInProgress = true;
                    fourWordsLeft = 4;
                    dmaOneStep(0);
                }
                break;
        }
    }

    private int fourWordsLeft;

    private void dmaOneStep(int channel) {
        int len = readBufferForChannel(channel, DMA_TCR0, Size.LONG);
        boolean lessThanFourLeft = fourWordsLeft == 4 && len < fourWordsLeft;
        boolean fifoFilling = !lessThanFourLeft && fourWordsLeft == 4 && !fifo.isFull();
        if (fifoFilling || fifo.isEmpty()) {
            return;
        }
        fourWordsLeft--;
        int srcAddress = readBufferForChannel(channel, DMA_SAR0, Size.LONG);
        int destAddress = readBufferForChannel(channel, DMA_DAR0, Size.LONG);
        long val = fifo.pop().data;
        memory.write16i(destAddress, (int) val);
        LOG.info("DMA write, src: {}, dest: {}, val: {}, dmaLen: {}", th(srcAddress), th(destAddress),
                th((int) val), th(len));
        writeBufferForChannel(channel, DMA_DAR0, destAddress + 2, Size.LONG);
        if (fourWordsLeft == 0 || lessThanFourLeft) {
            dma68k.updateFifoState();
            fourWordsLeft = 4;
            len = len < fourWordsLeft ? len - 1 : (len - 4) & 0xFFFF;
            if (len == 0) {
                dmaEnd(channel);
            }
            writeBufferForChannel(channel, DMA_TCR0, len, Size.LONG);
        }
    }

    private void dmaEnd(int channel) {
        dmaInProgress = false;
        dma68k.dmaEnd();
        setBitInt(channel, DMA_CHCR0 + 2, SH2_CHCR_TRANSFER_END_BIT, 1, Size.WORD);
    }

    private void setBitInt(int channel, int reg, int bitPos, int bitVal, Size size) {
        setBit(regs, (reg + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, bitPos, bitVal, size);
    }

    //channel1 = +0x10
    private int readBufferForChannel(int channel, int reg, Size size) {
        return readBuffer(regs, (reg + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, size);
    }

    private void writeBufferForChannel(int channel, int reg, int value, Size size) {
        writeBuffer(regs, (reg + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, value, size);
    }
}