package sh2;

import omegadrive.util.Size;
import omegadrive.vdp.md.VdpFifo;
import omegadrive.vdp.model.GenesisVdpProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.*;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1
 */
public class DmaFifo68k {

    private static final Logger LOG = LogManager.getLogger(DmaFifo68k.class.getSimpleName());

    private static final int FIFO_REG_M68K = 0xA15100 + FIFO_REG;
    private static final int M68K_FIFO_FULL_BIT = 7;
    private static final int M68K_68S_BIT_POS = 2;
    private static final int SH2_FIFO_FULL_BIT = 15;
    private static final int SH2_FIFO_EMPTY_BIT = 14;

    private final ByteBuffer sysRegsMd, sysRegsSh2;
    private final VdpFifo fifo = VdpFifo.createInstance();
    private boolean dmaOn = false;

    public DmaFifo68k(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
    }

    public int read(CpuDeviceAccess cpu, int reg, Size size) {
        int res = (int) size.getMask();
        switch (cpu) {
            case M68K:
                res = read68k(reg, size);
                break;
            case MASTER:
            case SLAVE:
                res = readSh2(reg, size);
                break;
        }
        LOG.info("{} DMA read {}: {} {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1],
                Integer.toHexString(res), size);
        return res;
    }


    public void write(CpuDeviceAccess cpu, int reg, int value, Size size) {
        LOG.info("{} DMA write {}: {} {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1],
                Integer.toHexString(value), size);
        if (size == Size.LONG) {
            LOG.error("{} DMA write {}: {} {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1],
                    Integer.toHexString(value), size);
            throw new RuntimeException();
        }
        switch (cpu) {
            case M68K:
                write68k(reg, value, size);
                break;
            default:
                LOG.error("Invalid {} DMA write {}: {} {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1],
                        Integer.toHexString(value), size);
                break;
        }
    }

    private void write68k(int reg, int value, Size size) {
        final int regEven = reg & ~1; //even
        switch (regEven) {
            case DREQ_CTRL:
                handleDreqCtlWrite68k(reg, value, size);
                break;
            case FIFO_REG:
                handleFifoRegWrite68k(value);
                break;
            case DREQ_DEST_ADDR_H:
            case DREQ_DEST_ADDR_L:
            case DREQ_SRC_ADDR_H:
            case DREQ_SRC_ADDR_L:
            case DREQ_LEN:
                writeBuffer(sysRegsMd, reg, value, size);
                writeBuffer(sysRegsSh2, reg, value, size);
                break;

        }
        writeBuffer(sysRegsMd, reg, value, size);
    }

    private void handleDreqCtlWrite68k(int reg, int value, Size size) {
        boolean changed = writeBufferHasChanged(sysRegsMd, reg, value, size);
        if (changed) {
            int res = readBuffer(sysRegsMd, DREQ_CTRL, Size.WORD);
            boolean wasDmaOn = dmaOn;
            dmaOn = (res & 4) > 0;
            //sync sh2 reg
            writeBuffer(sysRegsSh2, DMAC_CTRL + 1, res & 7, Size.BYTE);
            LOG.info("{} write DREQ_CTL, dmaOn: {} , RV: {}", M68K, dmaOn, res & 1);
            if (wasDmaOn && !dmaOn) {
                LOG.error("TODO check, stop DMA");
                stopDma();
            }
        }
    }

    private void handleFifoRegWrite68k(int value) {
        if (dmaOn) {
            if (!fifo.isFull()) {
                fifo.push(GenesisVdpProvider.VramMode.vramWrite, 0, value);
                updateFifoState();
            } else {
                LOG.error("DMA Fifo full, discarding data");
            }
        } else {
            LOG.error("DMA off");
        }
    }

    public void dmaEnd() {
        updateFifoState();
        //set 68S to 0
        setBit(sysRegsMd, sysRegsSh2, DREQ_CTRL + 1, M68K_68S_BIT_POS, 0, Size.BYTE);
    }

    private void stopDma() {
        dmaOn = false;
        fifo.clear();
        dmaEnd();
    }

    public void updateFifoState() {
        boolean changed = setBit(sysRegsMd, DREQ_CTRL, M68K_FIFO_FULL_BIT, fifo.isFull() ? 1 : 0, Size.WORD);
        if (changed) {
            setBit(sysRegsSh2, DMAC_CTRL, SH2_FIFO_FULL_BIT, fifo.isFull() ? 1 : 0, Size.WORD);
            LOG.info("68k DMA Fifo FULL state changed: {}", toHexString(sysRegsMd, DREQ_CTRL, Size.WORD));
            LOG.info("Sh2 DMA Fifo FULL state changed: {}", toHexString(sysRegsSh2, DMAC_CTRL, Size.WORD));
        }
        changed = setBit(sysRegsSh2, DMAC_CTRL, SH2_FIFO_EMPTY_BIT, fifo.isEmpty() ? 1 : 0, Size.WORD);
        if (changed) {
            LOG.info("Sh2 DMA Fifo empty state changed: {}", toHexString(sysRegsSh2, DMAC_CTRL, Size.WORD));
        }
    }

    private int read68k(int reg, Size size) {
        return readBuffer(sysRegsMd, reg, size);
    }


    private int readSh2(int reg, Size size) {
        int regEven = reg & ~1;
        if (regEven == DMAC_CTRL) {
            return readBuffer(sysRegsSh2, reg, size);
        }
        return readBuffer(sysRegsMd, reg, size);
    }

    public VdpFifo getFifo() {
        return fifo;
    }
}