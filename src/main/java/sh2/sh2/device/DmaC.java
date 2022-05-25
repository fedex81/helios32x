package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.DmaFifo68k;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.Sh2MMREG;
import sh2.sh2.device.DmaHelper.DmaChannelSetup;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.SH2_CACHE_THROUGH_OFFSET;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.sh2.device.IntControl.OnChipSubType.DMA_C0;
import static sh2.sh2.device.IntControl.OnChipSubType.DMA_C1;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.DMA;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1/2,  Chaotix, Primal Rage, and Virtua Racing
 */
public class DmaC implements Sh2Device {

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

    public DmaC(CpuDeviceAccess cpu, IntControl intControl, IMemory memory, DmaFifo68k dma68k, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.memory = memory;
        this.dma68k = dma68k;
        this.intControl = intControl;
        this.dmaChannelSetup = new DmaChannelSetup[]{DmaHelper.createChannel(0), DmaHelper.createChannel(1)};
    }

    @Override
    public void write(RegSpec regSpec, int pos, int value, Size size) {
        if (verbose) LOG.info("{} DMA write {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(value), size);
        writeBuffer(regs, pos, value, size);
        switch (cpu) {
            case MASTER:
            case SLAVE:
                assert pos == regSpec.addr : th(pos) + ", " + th(regSpec.addr);
                writeSh2(cpu, regSpec, value, size);
                break;
        }
    }

    @Override
    public int read(RegSpec regSpec, int reg, Size size) {
        return readBuffer(regs, reg, size);
    }

    @Override
    public void step(int cycles) {
        if (!oneDmaInProgress) {
            return;
        }
        for (DmaChannelSetup c : dmaChannelSetup) {
            if (c.dmaInProgress && (c.chcr_autoReq || c.dreqLevel)) {
                dmaOneStep(c);
            }
        }
    }

    private void writeSh2(CpuDeviceAccess cpu, RegSpec regSpec, int value, Size size) {
        switch (regSpec) {
            case DMA_CHCR0:
            case DMA_CHCR1:
                assert size == Size.LONG;
                handleChannelControlWrite(regSpec.addr, value);
                break;
            case DMA_DMAOR:
                assert size == Size.LONG;
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
        if (chan.chcr_dmaEn && chan.dmaor_dme && !chan.chcr_tranEndOk && (chan.chcr_autoReq || chan.dreqLevel)) {
            if (!chan.dmaInProgress) {
                chan.dmaInProgress = true;
                updateOneDmaInProgress();
                if (verbose) LOG.info("{} DMA start: {}", cpu, chan);
            }
        }
    }

    //TODO unify
    public void dmaReqTriggerPwm(int channel, boolean enable) {
        DmaChannelSetup d = dmaChannelSetup[channel];
        d.dreqLevel = enable;
        if (enable) {
            checkDmaStart(d);
            if (d.dmaInProgress) {
                Md32xRuntimeData.setAccessTypeExt(cpu);
                dmaOneStep(d);
                d.dreqLevel = false;
                d.dmaInProgress = false;
                updateOneDmaInProgress();
            }
        }
        if (verbose) LOG.info("{} DreqPwm{} Level: {}", cpu, channel, enable);
    }

    public void dmaReqTrigger(int channel, boolean enable) {
        dmaChannelSetup[channel].dreqLevel = enable;
        if (enable) {
            checkDmaStart(dmaChannelSetup[channel]);
        }
        if (verbose) LOG.info("{} Dreq{} Level: {}", cpu, channel, enable);
    }

    private void dmaOneStep(DmaChannelSetup c) {
        int len = readBufferForChannel(c.channel, DMA_TCR0.addr, Size.LONG) & 0xFF_FFFF;
        int srcAddress = readBufferForChannel(c.channel, DMA_SAR0.addr, Size.LONG);
        int destAddress = readBufferForChannel(c.channel, DMA_DAR0.addr, Size.LONG);
        int steps = c.transfersPerStep;
        assert cpu == Md32xRuntimeData.getAccessTypeExt();
        //TODO test DMA cannot write to cache, Zaxxon, Knuckles, RBI Baseball, FIFA 96, Mars Check v2
        //        assert (destAddress >> Sh2Prefetch.PC_CACHE_AREA_SHIFT) != 0 : th(destAddress);
        //TODO 4. When the cache is used as on-chip RAM, the DMAC cannot access this RAM.
        destAddress |= SH2_CACHE_THROUGH_OFFSET;
        //DMA cannot write to cache area
        assert (destAddress >> Sh2Prefetch.PC_CACHE_AREA_SHIFT) != 0 : th(destAddress);
        do {
            int val = memory.read(srcAddress, c.trnSize);
            memory.write(destAddress, val, c.trnSize);
            if (verbose)
                LOG.info("{} DMA write, src: {}, dest: {}, val: {}, dmaLen: {}", cpu, th(srcAddress), th(destAddress),
                        th((int) val), th(len));
            srcAddress += c.srcDelta;
            destAddress += c.destDelta;
            len = (len - 1) & 0xFF_FFFF;
        } while (--steps > 0 && len >= 0);
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
            c.dreqLevel = false;
            updateOneDmaInProgress();
            dma68k.dmaEnd();
            //transfer ended normally, ie. TCR = 0
            if (normal) {
                int chcr = setDmaChannelBitVal(c.channel, DMA_CHCR0.addr + 2, SH2_CHCR_TRANSFER_END_BIT, 1, Size.WORD);
                DmaHelper.updateChannelControl(c, chcr);
                if (c.chcr_intEn) {
                    intControl.setOnChipDeviceIntPending(DMA, c.channel == 0 ? DMA_C0 : DMA_C1);
                }
            }
            if (verbose) LOG.info("{} DMA stop, aborted: {}, {}", cpu, !normal, c);
        }
    }

    private void updateOneDmaInProgress() {
        oneDmaInProgress = dmaChannelSetup[0].dmaInProgress || dmaChannelSetup[1].dmaInProgress;
    }

    @Override
    public void reset() {
        writeBufferForChannel(0, DMA_CHCR0.addr, 0, Size.LONG);
        writeBufferForChannel(1, DMA_CHCR0.addr, 0, Size.LONG);
        writeBuffer(regs, DMA_DRCR0.addr, 0, Size.BYTE);
        writeBuffer(regs, DMA_DRCR1.addr, 0, Size.BYTE);
        writeBuffer(regs, DMA_DMAOR.addr, 0, Size.LONG);
        oneDmaInProgress = false;
    }

    public DmaChannelSetup[] getDmaChannelSetup() {
        return dmaChannelSetup;
    }

    private void setDmaChannelBit(int channel, int regChan0, int bitPos, int bitVal, Size size) {
        setBit(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, bitPos, bitVal, size);
    }

    private int setDmaChannelBitVal(int channel, int regChan0, int bitPos, int bitVal, Size size) {
        return setBitVal(regs, (regChan0 + (channel << 4)) & Sh2MMREG.SH2_REG_MASK, bitPos, bitVal, size);
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