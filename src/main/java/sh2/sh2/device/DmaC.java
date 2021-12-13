package sh2.sh2.device;

import omegadrive.util.Size;
import omegadrive.vdp.md.VdpFifo;
import omegadrive.vdp.model.GenesisVdpProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32XMMREG;
import sh2.S32xBus;
import sh2.S32xUtil;
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
public class DmaC {

    private static final Logger LOG = LogManager.getLogger(DmaC.class.getSimpleName());

    private static final int FIFO_REG_M68K = 0xA15100 + FIFO_REG;
    private static final int M68K_FIFO_FULL_BIT = 7;
    private static final int M68K_68S_BIT_MASK = 1 << 2;
    private static final int SH2_FIFO_FULL_BIT = 15;
    private static final int SH2_FIFO_EMPTY_BIT = 14;

    private final ByteBuffer sysRegsMd, sysRegsSh2;
    private final S32xBus bus;
    private final VdpFifo fifo = new VdpFifo();

    private boolean dmaOn = false;

    public DmaC(S32xBus bus, S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
        this.bus = bus;
    }

    public int read(CpuDeviceAccess cpu, int reg, Size size) {
        if (size == Size.LONG) {
            LOG.error("{} DMA read {}: {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1], size);
            throw new RuntimeException();
        }
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
            case MASTER:
            case SLAVE:
                writeSh2(cpu, reg, value, size);
                break;
        }
    }

    private void writeSh2(CpuDeviceAccess cpu, int reg, int value, Size size) {
        LOG.error("Invalid {} DMA write {}: {} {}", cpu, s32xRegNames[cpu.ordinal()][reg & ~1],
                Integer.toHexString(value), size);
        throw new RuntimeException();
    }

    private void write68k(int reg, int value, Size size) {
        final int regEven = reg & ~1; //even
        switch (regEven) {
            case DREQ_CTRL:
                if (size == Size.WORD || (size == Size.BYTE && reg != regEven)) {
                    int prev = S32xUtil.readBuffer(sysRegsMd, DREQ_CTRL, Size.WORD);
                    int rv = value & 1;
                    if ((prev & 1) != rv) {
                        LOG.info(M68K + " DMA RV bit {} -> {}", prev & 1, rv);
                    }
                    if (((prev & 4) == 0) && ((value & 4) > 0)) {
                        LOG.info(M68K + " DMA on");
                        dmaOn = true;
                    } else if (((prev & 4) > 0) && ((value & 4) == 0)) {
                        LOG.info(M68K + " DMA off");
                        stopDma();
                    } else if ((prev & 4) == (value & 4)) {
                        LOG.info("Ignoring DMA 68S rewrite: " + (value & 4));
                    }
                    S32xUtil.writeBuffer(sysRegsMd, reg, value, size);
                    int lsb = S32xUtil.readBuffer(sysRegsMd, DREQ_CTRL + 1, Size.BYTE);
                    S32xUtil.writeBuffer(sysRegsSh2, DMAC_CTRL + 1, lsb & 7, Size.BYTE);
                }
                break;
            case FIFO_REG:
                if (dmaOn) {
                    if (!fifo.isFull()) {
                        fifo.push(GenesisVdpProvider.VramMode.vramWrite, 0, value);
                        updateFifoState();
                    } else {
                        LOG.error("DMA Fifo full, discarding data");
                    }
                    if (fifo.isFull()) {
                        dmaOneStep();
                    }
                } else {
                    LOG.error("DMA off");
                }
                break;
        }
        S32xUtil.writeBuffer(sysRegsMd, reg, value, size);
    }

    private void dmaEnd() {
        updateFifoState();

        int value = S32xUtil.readBuffer(sysRegsMd, DREQ_CTRL, Size.WORD) & ~(M68K_68S_BIT_MASK);
        writeBuffer(sysRegsMd, DREQ_LEN, value, Size.WORD);
    }

    private void stopDma() {
        dmaOn = false;
        fifo.clear();
        dmaEnd();
    }

    private int dmaOneStep() {
        int srcAddress = S32xUtil.readBuffer(sysRegsMd, DREQ_SRC_ADDR_H, Size.LONG);
        int destAddress = S32xUtil.readBuffer(sysRegsMd, DREQ_DEST_ADDR_H, Size.LONG);
        int len = S32xUtil.readBuffer(sysRegsMd, DREQ_LEN, Size.WORD);
        int currDestAddr = destAddress;
        while (!fifo.isEmpty()) {
            long val = fifo.pop().data;
            bus.write(currDestAddr, val, Size.WORD);
            currDestAddr += 2;
        }
        S32xUtil.writeBuffer(sysRegsMd, DREQ_DEST_ADDR_H, currDestAddr, Size.LONG);
        len = (len - 4) & 0xFFFF;
        if (len == 0) {
            dmaEnd();
        }
        writeBuffer(sysRegsMd, DREQ_LEN, len, Size.WORD);
        updateFifoState();
        return len;
    }

    private void updateFifoState() {
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
        return S32xUtil.readBuffer(sysRegsMd, reg, size);
    }


    private int readSh2(int reg, Size size) {
        int regEven = reg & ~1;
        if (regEven == DMAC_CTRL) {
            return S32xUtil.readBuffer(sysRegsSh2, reg, size);
        }
        return S32xUtil.readBuffer(sysRegsMd, reg, size);
    }
}