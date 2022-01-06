package sh2;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.device.DivUnit;
import sh2.sh2.device.DmaC;
import sh2.sh2.device.SerialCommInterface;
import sh2.sh2.device.Sh2DeviceHelper;

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static sh2.S32xUtil.readBuffer;
import static sh2.S32xUtil.writeBuffer;
import static sh2.dict.Sh2Dict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2MMREG {

    private static final Logger LOG = LogManager.getLogger(Sh2MMREG.class.getSimpleName());

    public static final int DATA_ARRAY_SIZE = 0x1000;
    public static final int DATA_ARRAY_MASK = DATA_ARRAY_SIZE - 1;
    public static final int SH2_REG_SIZE = 0x1000;
    public static final int SH2_REG_MASK = SH2_REG_SIZE - 1;

    private ByteBuffer regs = ByteBuffer.allocateDirect(SH2_REG_SIZE);
    private ByteBuffer data_array = ByteBuffer.allocateDirect(DATA_ARRAY_SIZE); // cache (can be used as RAM)

    private SerialCommInterface sci;
    private DivUnit divUnit;
    private DmaC dmaC;

    private CpuDeviceAccess sh2Access;
    private static final boolean verbose = false;

    public Sh2MMREG(CpuDeviceAccess sh2Access) {
        this.sh2Access = sh2Access;
    }

    public void init(Sh2DeviceHelper.Sh2DeviceContext ctx) {
        this.dmaC = ctx.dmaC;
        this.divUnit = ctx.divUnit;
        this.sci = ctx.sci;
        reset();
    }

    public void writeCache(int address, int value, Size size) {
        writeBuffer(data_array, address & DATA_ARRAY_MASK, value, size);
    }

    public int readCache(int address, Size size) {
        return readBuffer(data_array, address & DATA_ARRAY_MASK, size);
    }

    public void write(int reg, int value, Size size) {
        if (verbose) {
            logAccess("write", reg, value, size);
        }
        checkName(reg);
        writeBuffer(regs, reg & SH2_REG_MASK, value, size);
        regWrite(reg, value, size);
    }

    private void regWrite(int reg, int value, Size size) {
        int regEven = reg & ~1;
        switch (regEven) {
            case DVDNTL:
            case DVDNTH:
            case DVCR:
            case DVDNT:
            case DVDNTUH:
            case DVDNTUL:
                divUnit.write(reg, value, size);
                break;
            case DMA_CHCR0:
            case DMA_CHCR1:
            case DMA_SAR0:
            case DMA_SAR1:
            case DMA_DAR0:
            case DMA_DAR1:
            case DMA_DRCR0:
            case DMA_DRCR1:
            case DMA_TCR0:
            case DMA_TCR1:
            case DMAOR:
            case VRCDMA0:
            case VRCDMA1:
                dmaC.write(sh2Access, reg, value, size);
                break;
            case SCI_BRR:
            case SCI_RDR:
            case SCI_SCR:
            case SCI_SSR:
            case SCI_TDR:
            case SCI_SMR:
                sci.write(reg, value, size);
                break;

        }
    }

    public int read(int reg, Size size) {
        int res = readBuffer(regs, reg & SH2_REG_MASK, size);
        if (verbose) {
            logAccess("read", reg, res, size);
        }
        checkName(reg);
        return res;
    }

    public ByteBuffer getRegs() {
        return regs;
    }

    public void reset() {
        //from picodrive
        IntStream.range(0, regs.capacity()).forEach(i -> regs.put(i, (byte) 0));
        sci.reset();
        divUnit.reset();
        writeBuffer(regs, TIER & SH2_REG_MASK, 0x11, Size.BYTE);
        writeBuffer(regs, TOCR & SH2_REG_MASK, 0x17, Size.BYTE);
    }
}
