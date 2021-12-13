package sh2.sh2.device;

import omegadrive.Device;
import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil;
import sh2.S32xUtil.CpuDeviceAccess;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.readBuffer;
import static sh2.Sh2MMREG.SH2_REG_MASK;
import static sh2.dict.Sh2Dict.*;

/**
 * SCI
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class SerialCommInterface implements Device {

    private static final Logger LOG = LogManager.getLogger(SerialCommInterface.class.getSimpleName());

    //    private final S32xBus bus;
    private ByteBuffer regs;
    private CpuDeviceAccess cpu;

    public SerialCommInterface(CpuDeviceAccess cpu, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
    }

    public int read(int reg, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI read {}: {}", cpu, sh2RegNames[reg], size);
            throw new RuntimeException();
        }
        int res = (int) size.getMask();
        LOG.info("{} SCI read {}: {} {}", cpu, sh2RegNames[reg],
                Integer.toHexString(res), size);
        return res;
    }

    public void write(int reg, int value, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI write {}: {} {}", cpu, sh2RegNames[reg],
                    Integer.toHexString(value), size);
            throw new RuntimeException();
        }
        LOG.info("{} SCI write {}: {} {}", cpu, sh2RegNames[reg],
                Integer.toHexString(value), size);
        switch (reg) {
            case SCI_SCR:
                if ((value & 8) > 0) {
                    LOG.info(cpu + " " + sh2RegNames[reg] + " MPIE enabled");
                }
                if ((value & 4) > 0) {
                    LOG.info(cpu + " " + sh2RegNames[reg] + " TEIE (tx) enabled");
                }
                if ((value & 0x10) > 0) {
                    LOG.info(cpu + " " + sh2RegNames[reg] + " RE (rx) enabled");
                }
                if ((value & 0x20) > 0) {
                    LOG.info(cpu + " " + sh2RegNames[reg] + " TE (tx) enabled");
                }
                if ((value & 3) > 0) {
                    LOG.info(cpu + " " + sh2RegNames[reg] + " CKE (clock): {}", value & 3);
                    if ((value & 3) != 2) {
                        LOG.error("Unsupported");
                    }
                }
                break;
            case SCI_SSR:
                if ((value & 0x80) == 0) {
                    LOG.info("{} {} TDRE valid data, TDR: {}", cpu, sh2RegNames[reg],
                            readBuffer(regs, SCI_TDR & SH2_REG_MASK, Size.BYTE));
                }
                break;
            case SCI_SMR:
                String s = (value & 0x80) == 0 ? "a" : "clock ";
                LOG.info("{} {} communication mode: {}", cpu, sh2RegNames[reg], s + "sync");
                break;
        }
    }

    @Override
    public void reset() {
        S32xUtil.writeBuffer(regs, SCI_SMR & SH2_REG_MASK, 0, Size.BYTE);
        S32xUtil.writeBuffer(regs, SCI_BRR & SH2_REG_MASK, 0xFF, Size.BYTE);
        S32xUtil.writeBuffer(regs, SCI_SCR & SH2_REG_MASK, 0, Size.BYTE);
        S32xUtil.writeBuffer(regs, SCI_TDR & SH2_REG_MASK, 0xFF, Size.BYTE);
        S32xUtil.writeBuffer(regs, SCI_SSR & SH2_REG_MASK, 0x84, Size.BYTE);
        S32xUtil.writeBuffer(regs, SCI_RDR & SH2_REG_MASK, 0, Size.BYTE);
    }
}
