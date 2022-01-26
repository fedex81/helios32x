package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.DmaFifo68k;
import sh2.IMemory;
import sh2.Sh2MMREG;
import sh2.sh2.device.DmaHelper.DmaChannelSetup;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.DMA;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1/2,  Chaotix, Primal Rage, and Virtua Racing
 */
public class DmaC implements StepDevice {

    private static final Logger LOG = LogManager.getLogger(DmaC.class.getSimpleName());

    private static final int SH2_CHCR_TRANSFER_END_BIT = 1;
    private static final boolean verbose = false;

    private final ByteBuffer regs;

    private final IntControl intControl;
    private final IMemory memory;
    private final DmaFifo68k dma68k;
    private final CpuDeviceAccess cpu;
    private final DmaChannelSetup[] dmaChannelSetup;
    private boolean oneDmaInProgress = false;

    @Deprecated
    public static DmaC[] dmaC = new DmaC[2];

    public DmaC(CpuDeviceAccess cpu, IntControl intControl, IMemory memory, DmaFifo68k dma68k, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.memory = memory;
        this.dma68k = dma68k;
        this.intControl = intControl;
        this.dmaChannelSetup = new DmaChannelSetup[]{DmaHelper.createChannel(0), DmaHelper.createChannel(1)};
        dmaC[cpu.ordinal()] = this;
    }

    public void write(RegSpec regSpec, int value, Size size) {
        if (verbose) LOG.info("{} DMA write {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(value), size);
        writeBuffer(regs, regSpec.addr, value, size);
        switch (cpu) {
            case MASTER:
            case SLAVE:
                writeSh2(cpu, regSpec, value, size);
                break;
        }
    }

    @Override
    public void step() {
        if (!oneDmaInProgress) {
            return;
        }
        for (DmaChannelSetup c : dmaChannelSetup) {
            if (c.dmaInProgress && (c.chcr_autoReq || c.dreqTrigger)) {
                dmaOneStep(c);
            }
        }
    }

    private void writeSh2(CpuDeviceAccess cpu, RegSpec regSpec, int value, Size size) {
        switch (regSpec) {
            case DMA_CHCR0:
            case DMA_CHCR1:
                handleChannelControlWrite(regSpec.addr, value);
                break;
            case DMA_DMAOR:
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
        if (chan.chcr_dmaEn && chan.dmaor_dme && (chan.chcr_autoReq || chan.dreqTrigger)) {
            if (!chan.dmaInProgress) {
                chan.dmaInProgress = true;
                updateOneDmaInProgress();
                if (verbose) LOG.info("{} DMA start: {}", cpu, chan);
            }
        }
    }

    public void dmaReqTrigger(int channel, boolean enable) {
        dmaChannelSetup[channel].dreqTrigger = enable;
        if (enable) {
            checkDmaStart(dmaChannelSetup[channel]);
        }
    }

    private void dmaOneStep(DmaChannelSetup c) {
        int len = readBufferForChannel(c.channel, DMA_TCR0.addr, Size.LONG) & 0x00FF_FFFF;
        int srcAddress = readBufferForChannel(c.channel, DMA_SAR0.addr, Size.LONG);
        int destAddress = readBufferForChannel(c.channel, DMA_DAR0.addr, Size.LONG);
        int steps = c.transfersPerStep;
        do {
            int val = memory.read(srcAddress, c.trnSize);
            memory.write(destAddress, val, c.trnSize);
            if (verbose)
                LOG.info("{} DMA write, src: {}, dest: {}, val: {}, dmaLen: {}", cpu, th(srcAddress), th(destAddress),
                        th((int) val), th(len));
            srcAddress += c.srcDelta;
            destAddress += c.destDelta;
            len = (len - 1) & 0xFFFF;
        } while (--steps > 0 && len > 0);
        writeBufferForChannel(c.channel, DMA_DAR0.addr, destAddress, Size.LONG);
        writeBufferForChannel(c.channel, DMA_SAR0.addr, srcAddress, Size.LONG);

        if (len <= 0) {
            if (verbose)
                LOG.info("{} DMA end, src: {}, dest: {}, dmaLen: {}", cpu, th(srcAddress), th(destAddress), th(len));
            dmaEnd(c, true);
            len = 0;
        }
        writeBufferForChannel(c.channel, DMA_TCR0.addr, Math.max(len, 0), Size.LONG);
    }

    private void dmaEnd(DmaChannelSetup c, boolean normal) {
        if (c.dmaInProgress) {
            c.dmaInProgress = false;
            c.dreqTrigger = false;
            updateOneDmaInProgress();
            dma68k.dmaEnd();
            //transfer ended normally, ie. TCR = 0
            if (normal) {
                setBitInt(c.channel, DMA_CHCR0.addr + 2, SH2_CHCR_TRANSFER_END_BIT, 1, Size.WORD);
                if (c.chcr_intEn) {
                    intControl.setExternalIntPending(DMA, c.channel, true);
                }
            }
            if (verbose) LOG.info("{} DMA stop, aborted: {}, {}", cpu, !normal, c);
        }
    }

    private void updateOneDmaInProgress() {
        oneDmaInProgress = dmaChannelSetup[0].dmaInProgress || dmaChannelSetup[1].dmaInProgress;
    }

    private void setBitInt(int channel, int regChan0, int bitPos, int bitVal, Size size) {
        setBit(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, bitPos, bitVal, size);
    }

    private int readVcrDma(int channel) {
        return readBuffer(regs, (DMA_VRCDMA0.addr + (channel << 3)) & Sh2MMREG.SH2_REG_MASK, Size.LONG) & 0xFF;
    }

    //channel1 = +0x10
    private int readBufferForChannel(int channel, int regChan0, Size size) {
        return readBuffer(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, size);
    }

    private void writeBufferForChannel(int channel, int regChan0, int value, Size size) {
        writeBuffer(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, value, size);
    }
}