package sh2;

import omegadrive.util.Fifo;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xDict.RegSpecS32x;
import sh2.sh2.device.DmaC;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.M68K;
import static sh2.dict.S32xDict.RegSpecS32x.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1
 */
public class DmaFifo68k {

    private static final Logger LOG = LogManager.getLogger(DmaFifo68k.class.getSimpleName());

    private static final int FIFO_REG_M68K = 0xA15100 + M68K_FIFO_REG.addr;
    private static final int M68K_FIFO_FULL_BIT = 7;
    private static final int M68K_68S_BIT_POS = 2;
    private static final int SH2_FIFO_FULL_BIT = 15;
    private static final int SH2_FIFO_EMPTY_BIT = 14;
    private static final int DREQ0_CHANNEL = 0;
    private static final int DMA_FIFO_SIZE = 4;

    private final ByteBuffer sysRegsMd, sysRegsSh2;
    private final Fifo<Integer> fifo = Fifo.createIntegerFixedSizeFifo(DMA_FIFO_SIZE);
    private boolean m68S = false;
    private static final boolean verbose = false;

    public DmaFifo68k(S32XMMREG s32XMMREG) {
        this.sysRegsMd = s32XMMREG.sysRegsMd;
        this.sysRegsSh2 = s32XMMREG.sysRegsSh2;
    }

    public int read(RegSpecS32x regSpec, CpuDeviceAccess cpu, int address, Size size) {
        int res = (int) size.getMask();
        switch (cpu) {
            case M68K:
                res = read68k(address, size);
                break;
            case MASTER:
            case SLAVE:
                res = readSh2(regSpec, address, size);
                break;
        }
        if (verbose) LOG.info("{} DMA read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }


    public void write(RegSpecS32x regSpec, CpuDeviceAccess cpu, int address, int value, Size size) {
        if (verbose) LOG.info("{} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
        if (size == Size.LONG) {
            LOG.error("{} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
            throw new RuntimeException();
        }
        switch (cpu) {
            case M68K:
                write68k(regSpec, address, value, size);
                break;
            default:
                LOG.error("Invalid {} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
                break;
        }
    }

    private void write68k(RegSpecS32x regSpec, int address, int value, Size size) {
        switch (regSpec) {
            case M68K_DMAC_CTRL:
                handleDreqCtlWrite68k(address, value, size);
                break;
            case M68K_FIFO_REG:
                handleFifoRegWrite68k(value);
                break;
            case M68K_DREQ_DEST_ADDR_H:
            case M68K_DREQ_DEST_ADDR_L:
            case M68K_DREQ_SRC_ADDR_H:
            case M68K_DREQ_SRC_ADDR_L:
            case M68K_DREQ_LEN:
                writeBuffer(sysRegsMd, address, value, size);
                writeBuffer(sysRegsSh2, address, value, size);
                break;
            default:
                LOG.error("{} check DMA write {}: {} {}", M68K, regSpec.name, th(value), size);
                break;
        }
        writeBuffer(sysRegsMd, address, value, size);
    }

    private void handleDreqCtlWrite68k(int reg, int value, Size size) {
        boolean changed = writeBufferHasChanged(sysRegsMd, reg, value, size);
        if (changed) {
            int res = readBuffer(sysRegsMd, M68K_DMAC_CTRL.addr, Size.WORD);
            boolean wasDmaOn = m68S;
            m68S = (res & 4) > 0;
            //sync sh2 reg
            writeBuffer(sysRegsSh2, SH2_DREQ_CTRL.addr + 1, res & 7, Size.BYTE);
            if (verbose) LOG.info("{} write DREQ_CTL, dmaOn: {} , RV: {}", M68K, m68S, res & 1);
            if (wasDmaOn && !m68S) {
                LOG.error("TODO check, 68S = 0, stops DMA while running");
                stopDma();
            }
            updateFifoState();
        }
    }

    private void handleFifoRegWrite68k(int value) {
        if (m68S) {
            if (!fifo.isFull()) {
                fifo.push(value);
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
        setBit(sysRegsMd, sysRegsSh2, SH2_DREQ_CTRL.addr + 1, M68K_68S_BIT_POS, 0, Size.BYTE);
    }

    private void stopDma() {
        m68S = false;
        fifo.clear();
        dmaEnd();
    }

    public void updateFifoState() {
        boolean changed = setBit(sysRegsMd, M68K_DMAC_CTRL.addr, M68K_FIFO_FULL_BIT, fifo.isFullBit(), Size.WORD);
        if (changed) {
            setBit(sysRegsSh2, SH2_DREQ_CTRL.addr, SH2_FIFO_FULL_BIT, fifo.isFull() ? 1 : 0, Size.WORD);
            if (verbose) {
                LOG.info("68k DMA Fifo FULL state changed: {}", toHexString(sysRegsMd, M68K_DMAC_CTRL.addr, Size.WORD));
                LOG.info("Sh2 DMA Fifo FULL state changed: {}", toHexString(sysRegsSh2, SH2_DREQ_CTRL.addr, Size.WORD));
            }
            evaluateDreqTrigger();
        }
        changed = setBit(sysRegsSh2, SH2_DREQ_CTRL.addr, SH2_FIFO_EMPTY_BIT, fifo.isEmptyBit(), Size.WORD);
        if (changed) {
            if (verbose)
                LOG.info("Sh2 DMA Fifo empty state changed: {}", toHexString(sysRegsSh2, SH2_DREQ_CTRL.addr, Size.WORD));
            evaluateDreqTrigger();
        }
    }

    private void evaluateDreqTrigger() {
        if (m68S && (fifo.isFull() || fifo.isEmpty())) {
            boolean enable = fifo.isFull();
            DmaC.dmaC[0].dmaReqTrigger(DREQ0_CHANNEL, enable);
            DmaC.dmaC[1].dmaReqTrigger(DREQ0_CHANNEL, enable);
            if (verbose) LOG.info("DMA fifo dreq: {}", enable);
        }
    }

    private int read68k(int reg, Size size) {
        return readBuffer(sysRegsMd, reg, size);
    }


    private int readSh2(RegSpecS32x regSpec, int address, Size size) {
        if (regSpec == SH2_DREQ_CTRL) {
            return readBuffer(sysRegsSh2, address, size);
        } else if (regSpec == SH2_FIFO_REG) {
            int res = 0;
            if (m68S && !fifo.isEmpty()) {
                res = fifo.pop();
                updateFifoState();
            } else {
                LOG.error("Dreq0: {}, fifoEmpty: {}", m68S, fifo.isEmpty());
            }
            return res;
        }
        return readBuffer(sysRegsMd, address, size);
    }
}