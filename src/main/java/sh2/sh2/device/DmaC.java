package sh2.sh2.device;

import omegadrive.util.Size;
import omegadrive.vdp.md.VdpFifo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.DmaFifo68k;
import sh2.IMemory;
import sh2.Sh2MMREG;
import sh2.sh2.device.DmaHelper.DmaChannelSetup;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.*;
import static sh2.sh2.device.IntControl.Sh2Interrupt.NONE_15;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1/2,  Chaotix, Primal Rage, and Virtua Racing
 */
public class DmaC {

    private static final Logger LOG = LogManager.getLogger(DmaC.class.getSimpleName());

    private static final int SH2_CHCR_TRANSFER_END_BIT = 1;

    private final ByteBuffer regs;

    private final IMemory memory;
    private final DmaFifo68k dma68k;
    private final VdpFifo fifo;
    private final CpuDeviceAccess cpu;
    private final DmaChannelSetup[] dmaChannelSetup;
    private boolean oneDmaInProgress = false;

    public DmaC(CpuDeviceAccess cpu, IMemory memory, DmaFifo68k dma68k, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.memory = memory;
        this.dma68k = dma68k;
        this.fifo = dma68k.getFifo();
        this.dmaChannelSetup = new DmaChannelSetup[]{DmaHelper.createChannel(0), DmaHelper.createChannel(1)};
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
        if (!oneDmaInProgress) {
            return;
        }
        for (DmaChannelSetup c : dmaChannelSetup) {
            if (c.dmaInProgress) {
                dmaOneStep(c);
            }
        }
    }

    private void writeSh2(CpuDeviceAccess cpu, int reg, int value, Size size) {
        switch (reg) {
            case DMA_CHCR0:
            case DMA_CHCR1:
                handleChannelControlWrite(reg, value);
                break;
            case DMAOR:
                handleOperationRegWrite(value);
                break;
        }
    }

    private void handleChannelControlWrite(int reg, int value) {
        int channel = (reg >> 4) & 1;
        DmaChannelSetup chan = dmaChannelSetup[channel];
        boolean wasEn = chan.chcr_dmaEn;
        DmaHelper.updateChannelControl(chan, value);
        if (wasEn != chan.chcr_dmaEn) {
            if (chan.chcr_dmaEn) {
                checkDmaStart(chan);
            } else {
                dmaEnd(chan, false);
            }
        }
    }

    private void handleOperationRegWrite(int value) {
        DmaHelper.updateChannelDmaor(dmaChannelSetup[0], value);
        DmaHelper.updateChannelDmaor(dmaChannelSetup[1], value);
        boolean dme = (value & 1) > 0;
        if (dme) {
            checkDmaStart(dmaChannelSetup[0]);
            checkDmaStart(dmaChannelSetup[1]);
        } else {
            dmaEnd(dmaChannelSetup[0], false);
            dmaEnd(dmaChannelSetup[1], false);
        }
    }

    private void checkDmaStart(DmaChannelSetup chan) {
        if (chan.chcr_dmaEn && chan.dmaor_dme) {
            if (!chan.dmaInProgress) {
                chan.dmaInProgress = true;
                updateOneDmaInProgress();
                DmaHelper.updateFifoDma(chan, readBufferForChannel(chan.channel, DMA_SAR0, Size.LONG));
                chan.fourWordsLeft = 4;
                LOG.info("DMA start: " + chan);
                if (chan.fifoDma && chan.chcr_transferSize != DmaHelper.DmaTransferSize.WORD) {
                    LOG.error("Unhandled transfer size {}", chan.chcr_transferSize);
                }
                dmaOneStep(chan);
            }
        }
    }

    private void dmaOneStep(DmaChannelSetup c) {
        if (c.fifoDma) {
            dmaFifo(c);
        } else {
            dmaSh2(c);
        }
    }

    //TODO should dmaFifo handle transferSize != WORD? probably not
    private void dmaFifo(DmaChannelSetup c) {
        int len = readBufferForChannel(c.channel, DMA_TCR0, Size.LONG);
        boolean lessThanFourLeft = c.fourWordsLeft == 4 && len < c.fourWordsLeft;
        boolean fifoFilling = !lessThanFourLeft && c.fourWordsLeft == 4 && !fifo.isFull();
        if (c.fifoDma && (fifoFilling || fifo.isEmpty())) {
            return;
        }
        c.fourWordsLeft--;
        int srcAddress = readBufferForChannel(c.channel, DMA_SAR0, Size.LONG);
        int destAddress = readBufferForChannel(c.channel, DMA_DAR0, Size.LONG);
        long val = fifo.pop().data;
        memory.write16i(destAddress, (int) val);
        LOG.info("DMA write, src: {}, dest: {}, val: {}, dmaLen: {}", th(srcAddress), th(destAddress),
                th((int) val), th(len));
        writeBufferForChannel(c.channel, DMA_DAR0, destAddress + c.destDelta, Size.LONG);
        writeBufferForChannel(c.channel, DMA_SAR0, srcAddress + c.srcDelta, Size.LONG);
        if (c.fourWordsLeft == 0 || lessThanFourLeft) {
            dma68k.updateFifoState();
            c.fourWordsLeft = 4;
            len = len < c.fourWordsLeft ? len - 1 : (len - 4) & 0xFFFF;
            if (len == 0) {
                dmaEnd(c, true);
            }
            writeBufferForChannel(c.channel, DMA_TCR0, len, Size.LONG);
        }
    }

    private void dmaSh2(DmaChannelSetup c) {
        int len = readBufferForChannel(c.channel, DMA_TCR0, Size.LONG);
        int srcAddress = readBufferForChannel(c.channel, DMA_SAR0, Size.LONG);
        int destAddress = readBufferForChannel(c.channel, DMA_DAR0, Size.LONG);
        int val = memory.read(srcAddress, c.trnSize);
        memory.write(destAddress, val, c.trnSize);
        writeBufferForChannel(c.channel, DMA_DAR0, destAddress + c.destDelta, Size.LONG);
        writeBufferForChannel(c.channel, DMA_SAR0, srcAddress + c.srcDelta, Size.LONG);
        len = (len - 1) & 0xFFFF;
        LOG.info("DMA write, src: {}, dest: {}, val: {}, dmaLen: {}", th(srcAddress), th(destAddress),
                th((int) val), th(len));
        if (len == 0) {
            dmaEnd(c, true);
        }
        writeBufferForChannel(c.channel, DMA_TCR0, len, Size.LONG);
    }

    private void dmaEnd(DmaChannelSetup c, boolean normal) {
        if (c.dmaInProgress) {
            c.dmaInProgress = false;
            updateOneDmaInProgress();
            dma68k.dmaEnd();
            //transfer ended normally, ie. TCR = 0
            if (normal) {
                setBitInt(c.channel, DMA_CHCR0 + 2, SH2_CHCR_TRANSFER_END_BIT, 1, Size.WORD);
                if (c.chcr_intEn) {
                    int vector = readVcrDma(c.channel);
                    IntControl.intc[cpu.ordinal()].setIntPending(NONE_15, true);
                    LOG.info("{} DMA interrupt: {}, vector: {}", cpu, NONE_15, vector);
                }
            }
            LOG.info("DMA stop, aborted: {}, {}", !normal, c);
        }
    }

    private void updateOneDmaInProgress() {
        oneDmaInProgress = dmaChannelSetup[0].dmaInProgress || dmaChannelSetup[1].dmaInProgress;
    }

    private void setBitInt(int channel, int regChan0, int bitPos, int bitVal, Size size) {
        setBit(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, bitPos, bitVal, size);
    }

    private int readVcrDma(int channel) {
        return readBuffer(regs, (VRCDMA0 + (channel << 3)) & Sh2MMREG.SH2_REG_MASK, Size.LONG) & 0xFF;
    }

    //channel1 = +0x10
    private int readBufferForChannel(int channel, int regChan0, Size size) {
        return readBuffer(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, size);
    }

    private void writeBufferForChannel(int channel, int regChan0, int value, Size size) {
        writeBuffer(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, value, size);
    }
}