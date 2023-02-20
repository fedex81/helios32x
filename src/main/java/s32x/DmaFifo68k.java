package s32x;

import omegadrive.util.Fifo;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import s32x.dict.S32xDict;
import s32x.sh2.device.DmaC;
import s32x.util.Md32xRuntimeData;
import s32x.util.S32xUtil;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;

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

    public int read(S32xDict.RegSpecS32x regSpec, S32xUtil.CpuDeviceAccess cpu, int address, Size size) {
        int res = switch (cpu.regSide) {
            case MD -> readMd(address, size);
            case SH2 -> readSh2(regSpec, address, size);
        };
        if (verbose) LOG.info("{} DMA read {}: {} {}", cpu, regSpec.name, th(res), size);
        return res;
    }


    public void write(S32xDict.RegSpecS32x regSpec, S32xUtil.CpuDeviceAccess cpu, int address, int value, Size size) {
        if (verbose) LOG.info("{} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
        switch (cpu.regSide) {
            case MD -> writeMd(regSpec, address, value, size);
            default -> LOG.error("Invalid {} DMA write {}: {} {}", cpu, regSpec.name, th(value), size);
        }
    }

    private void writeMd(S32xDict.RegSpecS32x regSpec, int address, int value, Size size) {
        assert size != Size.LONG;
        switch (regSpec) {
            case MD_DMAC_CTRL:
                handleDreqCtlWriteMd(address, value, size);
                break;
            case MD_FIFO_REG:
                assert Md32xRuntimeData.getAccessTypeExt() != S32xUtil.CpuDeviceAccess.Z80;
                handleFifoRegWriteMd(value, size);
                S32xUtil.writeBuffer(sysRegsMd, address, value, size);
                break;
            case MD_DREQ_LEN:
                value &= M68K_DMA_FIFO_LEN_MASK;
                //fall-through
            case MD_DREQ_DEST_ADDR_H:
            case MD_DREQ_DEST_ADDR_L:
            case MD_DREQ_SRC_ADDR_H:
            case MD_DREQ_SRC_ADDR_L:
                assert Md32xRuntimeData.getAccessTypeExt() != S32xUtil.CpuDeviceAccess.Z80;
                //NOTE after burner 68k byte writes: (MD_DREQ_DEST_ADDR_H +1) 0xd,2,BYTE
                S32xUtil.writeBufferHasChangedWithMask(regSpec, sysRegsMd, address, value, size);
                S32xUtil.writeBufferHasChangedWithMask(regSpec, sysRegsSh2, address, value, size);
                break;
            default:
                LOG.error("{} check DMA write {}: {} {}", S32xUtil.CpuDeviceAccess.M68K, regSpec.name, th(value), size);
                break;
        }
    }

    private void handleDreqCtlWriteMd(int reg, int value, Size size) {
        assert size != Size.LONG;
        boolean changed = S32xUtil.writeBufferHasChangedWithMask(S32xDict.RegSpecS32x.MD_DMAC_CTRL, sysRegsMd, reg, value, size);
        if (changed) {
            int res = S32xUtil.readBufferWord(sysRegsMd, S32xDict.RegSpecS32x.MD_DMAC_CTRL.addr);
            boolean wasDmaOn = m68S;
            m68S = (res & 4) > 0;
            rv = (res & 1) > 0;
            //sync sh2 reg
            S32xUtil.writeBuffer(sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr + 1, res & 7, Size.BYTE);
            //NOTE bit 1 is called DMA, only relevant when using SEGA CD (see picodrive)
//            assert (res & 2) == 0;
            if (verbose)
                LOG.info("{} write DREQ_CTL, dmaOn: {} , RV: {}", Md32xRuntimeData.getAccessTypeExt(), m68S, rv);
            if (wasDmaOn && !m68S) {
                LOG.info("{} Setting 68S = 0, stops DMA while running", Md32xRuntimeData.getAccessTypeExt());
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
        S32xUtil.setBit(sysRegsMd, sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr + 1, M68K_68S_BIT_POS, 0, Size.BYTE);
        m68S = false;
    }

    public void updateFifoState() {
        boolean changed = S32xUtil.setBit(sysRegsMd, S32xDict.RegSpecS32x.MD_DMAC_CTRL.addr, M68K_FIFO_FULL_BIT, fifo.isFullBit(), Size.WORD);
        if (changed) {
            S32xUtil.setBit(sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr, SH2_FIFO_FULL_BIT, fifo.isFull() ? 1 : 0, Size.WORD);
            if (verbose) {
                LOG.info("68k DMA Fifo FULL state changed: {}", S32xUtil.toHexString(sysRegsMd, S32xDict.RegSpecS32x.MD_DMAC_CTRL.addr, Size.WORD));
                LOG.info("Sh2 DMA Fifo FULL state changed: {}", S32xUtil.toHexString(sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr, Size.WORD));
            }
        }
        changed = S32xUtil.setBit(sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr, SH2_FIFO_EMPTY_BIT, fifo.isEmptyBit(), Size.WORD);
        if (changed) {
            if (verbose)
                LOG.info("Sh2 DMA Fifo empty state changed: {}", S32xUtil.toHexString(sysRegsSh2, S32xDict.RegSpecS32x.SH2_DREQ_CTRL.addr, Size.WORD));
        }
        evaluateDreqTrigger(m68S);
    }

    //NOTE: there are two fifos of size 4, dreq is triggered when at least one fifo is full
    private void evaluateDreqTrigger(boolean pm68S) {
        final int lev = fifo.getLevel();
        if (pm68S && (lev & 3) == 0) { //lev can be 0,4,8
            boolean enable = lev > 0; //lev can be 4,8
            dmac[S32xUtil.CpuDeviceAccess.MASTER.ordinal()].dmaReqTrigger(DREQ0_CHANNEL, enable);
            dmac[S32xUtil.CpuDeviceAccess.SLAVE.ordinal()].dmaReqTrigger(DREQ0_CHANNEL, enable);
            if (verbose) LOG.info("DMA fifo dreq: {}", enable);
        }
    }

    private int readMd(int reg, Size size) {
        return S32xUtil.readBuffer(sysRegsMd, reg, size);
    }


    private int readSh2(S32xDict.RegSpecS32x regSpec, int address, Size size) {
        if (regSpec == S32xDict.RegSpecS32x.SH2_DREQ_CTRL) {
            return S32xUtil.readBuffer(sysRegsSh2, address, size);
        } else if (regSpec == S32xDict.RegSpecS32x.SH2_FIFO_REG) {
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
        return S32xUtil.readBuffer(sysRegsMd, address, size);
    }

    public void setDmac(DmaC... dmac) {
        this.dmac = dmac;
    }

    public DmaC[] getDmac() {
        return dmac;
    }
}