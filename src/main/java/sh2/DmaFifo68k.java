package sh2;

import omegadrive.util.Fifo;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.dict.S32xDict.RegSpecS32x;
import sh2.sh2.device.DmaC;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * 32X Hardware Manual Supplement 1
 */
public class DmaFifo68k {

    private static final Logger LOG = LogHelper.getLogger(DmaFifo68k.class.getSimpleName());
    public static final int M68K_FIFO_FULL_BIT = 7;
    public static final int M68K_68S_BIT_POS = 2;
    public static final int SH2_FIFO_FULL_BIT = 15;
    public static final int SH2_FIFO_EMPTY_BIT = 14;
    public static final int DREQ0_CHANNEL = 0;
    public static final int DMA_FIFO_SIZE = 8;
    public static final int M68K_DMA_FIFO_LEN_MASK = 0xFFFC;
    private final ByteBuffer sysRegsMd, sysRegsSh2;
    private DmaC[] dmac;
    private final Fifo<Integer> fifo = Fifo.createIntegerFixedSizeFifo(DMA_FIFO_SIZE);
    private boolean m68S = false;
    public static boolean rv = false;
    private static final boolean verbose = false;

    public DmaFifo68k(S32XMMREG.RegContext regContext) {
        this.sysRegsMd = regContext.sysRegsMd;
        this.sysRegsSh2 = regContext.sysRegsSh2;
        rv = false;
    }

    public int read(RegSpecS32x regSpec, CpuDeviceAccess cpu, int address, Size size) {
        int res = switch (cpu.regSide) {
            case MD -> readMd(address, size);
            case SH2 -> readSh2(regSpec, address, size);
        };
        if (verbose) LOG.info("{} DMA read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }


    public void write(RegSpecS32x regSpec, CpuDeviceAccess cpu, int address, int value, Size size) {
        if (verbose) LOG.info("{} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
        if (size == Size.LONG) {
            LOG.error("{} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
            throw new RuntimeException();
        }
        switch (cpu.regSide) {
            case MD:
                writeMd(regSpec, address, value, size);
                break;
            default:
                LOG.error("Invalid {} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
                break;
        }
    }

    private void writeMd(RegSpecS32x regSpec, int address, int value, Size size) {
        assert size != Size.LONG;
        switch (regSpec) {
            case MD_DMAC_CTRL:
                handleDreqCtlWriteMd(address, value, size);
                break;
            case MD_FIFO_REG:
                assert Md32xRuntimeData.getAccessTypeExt() != Z80;
                handleFifoRegWriteMd(value, size);
                writeBuffer(sysRegsMd, address, value, size);
                break;
            case MD_DREQ_LEN:
                value &= M68K_DMA_FIFO_LEN_MASK;
                //fall-through
            case MD_DREQ_DEST_ADDR_H:
            case MD_DREQ_DEST_ADDR_L:
            case MD_DREQ_SRC_ADDR_H:
            case MD_DREQ_SRC_ADDR_L:
                assert Md32xRuntimeData.getAccessTypeExt() != Z80;
                //NOTE after burner 68k byte writes: (MD_DREQ_DEST_ADDR_H +1) 0xd,2,BYTE
                writeBufferHasChangedWithMask(regSpec, sysRegsMd, address, value, size);
                writeBufferHasChangedWithMask(regSpec, sysRegsSh2, address, value, size);
                break;
            default:
                LOG.error("{} check DMA write {}: {} {}", M68K, regSpec.name, th(value), size);
                break;
        }
    }

    private void handleDreqCtlWriteMd(int reg, int value, Size size) {
        assert size != Size.LONG;
        boolean changed = writeBufferHasChangedWithMask(MD_DMAC_CTRL, sysRegsMd, reg, value, size);
        if (changed) {
            int res = readBuffer(sysRegsMd, MD_DMAC_CTRL.addr, Size.WORD);
            boolean wasDmaOn = m68S;
            m68S = (res & 4) > 0;
            rv = (res & 1) > 0;
            //sync sh2 reg
            writeBuffer(sysRegsSh2, SH2_DREQ_CTRL.addr + 1, res & 7, Size.BYTE);
            //TODO bit 1 is unused? picodrive calls it DMA
            if (verbose) LOG.info("{} write DREQ_CTL, dmaOn: {} , RV: {}", M68K, m68S, rv);
            if (wasDmaOn && !m68S) {
                LOG.info("{} Setting 68S = 0, stops DMA while running", M68K);
                dmaEnd();
            }
            updateFifoState();
        }
    }

    private void handleFifoRegWriteMd(int value, Size size) {
        assert size == Size.WORD;
        if (m68S) {
            if (!fifo.isFull()) {
                fifo.push(value);
                updateFifoState();
            } else {
                LOG.error("DMA Fifo full, discarding data");
            }
        } else {
            LOG.error("DMA off, ignoring FIFO write: {}", th(value));
        }
    }

    public void dmaEnd() {
        fifo.clear();
        updateFifoState();
        evaluateDreqTrigger(true); //force clears the dreqLevel in DMAC
        //set 68S to 0
        setBit(sysRegsMd, sysRegsSh2, SH2_DREQ_CTRL.addr + 1, M68K_68S_BIT_POS, 0, Size.BYTE);
        m68S = false;
    }

    public void updateFifoState() {
        boolean changed = setBit(sysRegsMd, MD_DMAC_CTRL.addr, M68K_FIFO_FULL_BIT, fifo.isFullBit(), Size.WORD);
        if (changed) {
            setBit(sysRegsSh2, SH2_DREQ_CTRL.addr, SH2_FIFO_FULL_BIT, fifo.isFull() ? 1 : 0, Size.WORD);
            if (verbose) {
                LOG.info("68k DMA Fifo FULL state changed: {}", toHexString(sysRegsMd, MD_DMAC_CTRL.addr, Size.WORD));
                LOG.info("Sh2 DMA Fifo FULL state changed: {}", toHexString(sysRegsSh2, SH2_DREQ_CTRL.addr, Size.WORD));
            }
        }
        changed = setBit(sysRegsSh2, SH2_DREQ_CTRL.addr, SH2_FIFO_EMPTY_BIT, fifo.isEmptyBit(), Size.WORD);
        if (changed) {
            if (verbose)
                LOG.info("Sh2 DMA Fifo empty state changed: {}", toHexString(sysRegsSh2, SH2_DREQ_CTRL.addr, Size.WORD));
        }
        evaluateDreqTrigger(m68S);
    }

    //NOTE: there are two fifos of size 4, dreq is triggered when at least one fifo is full
    private void evaluateDreqTrigger(boolean pm68S) {
        final int lev = fifo.getLevel();
        if (pm68S && (lev & 3) == 0) { //lev can be 0,4,8
            boolean enable = lev > 0; //lev can be 4,8
            dmac[MASTER.ordinal()].dmaReqTrigger(DREQ0_CHANNEL, enable);
            dmac[SLAVE.ordinal()].dmaReqTrigger(DREQ0_CHANNEL, enable);
            if (verbose) LOG.info("DMA fifo dreq: {}", enable);
        }
    }

    private int readMd(int reg, Size size) {
        return readBuffer(sysRegsMd, reg, size);
    }


    private int readSh2(RegSpecS32x regSpec, int address, Size size) {
        if (regSpec == SH2_DREQ_CTRL) {
            return readBuffer(sysRegsSh2, address, size);
        } else if (regSpec == SH2_FIFO_REG) {
            assert size == Size.WORD;
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

    public void setDmac(DmaC... dmac) {
        this.dmac = dmac;
    }

    public DmaC[] getDmac() {
        return dmac;
    }
}