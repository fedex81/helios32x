package sh2.sh2.device;

import omegadrive.util.Size;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.device.Sh2DeviceHelper.Sh2Device;

import java.nio.ByteBuffer;

import static sh2.S32xUtil.readBuffer;
import static sh2.S32xUtil.writeBuffer;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;

/**
 * SCI
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class SerialCommInterface implements Sh2Device {

    private static final Logger LOG = LogManager.getLogger(SerialCommInterface.class.getSimpleName());

    private ByteBuffer regs;
    private CpuDeviceAccess cpu;

    public SerialCommInterface(CpuDeviceAccess cpu, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
    }

    public int read(RegSpec regSpec, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI read {}: {}", cpu, regSpec.name, size);
            throw new RuntimeException();
        }
        int res = (int) size.getMask();
        LOG.info("{} SCI read {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(res), size);
        return res;
    }

    public void write(RegSpec regSpec, int value, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI write {}: {} {}", cpu, regSpec.name,
                    Integer.toHexString(value), size);
            throw new RuntimeException();
        }
        LOG.info("{} SCI write {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(value), size);
        switch (regSpec) {
            case SCI_SCR:
                if ((value & 8) > 0) {
                    LOG.info(cpu + " " + regSpec.name + " MPIE enabled");
                }
                if ((value & 4) > 0) {
                    LOG.info(cpu + " " + regSpec.name + " TEIE (tx) enabled");
                }
                if ((value & 0x10) > 0) {
                    LOG.info(cpu + " " + regSpec.name + " RE (rx) enabled");
                }
                if ((value & 0x20) > 0) {
                    LOG.info(cpu + " " + regSpec.name + " TE (tx) enabled");
                }
                if ((value & 3) > 0) {
                    LOG.info(cpu + " " + regSpec.name + " CKE (clock): {}", value & 3);
                    if ((value & 3) != 2) {
                        LOG.error(" CKE (clock) unsupported! {}", value & 3);
                    }
                }
                break;
            case SCI_SSR:
                if ((value & 0x80) == 0) {
                    LOG.info("{} {} TDRE valid data, TDR: {}", cpu, regSpec.name,
                            readBuffer(regs, SCI_TDR.addr, Size.BYTE));
                }
                break;
            case SCI_SMR:
                String s = (value & 0x80) == 0 ? "a" : "clock ";
                LOG.info("{} {} communication mode: {}", cpu, regSpec.name, s + "sync");
                break;
        }
    }

    @Override
    public void reset() {
        writeBuffer(regs, SCI_SMR.addr, 0, Size.BYTE);
        writeBuffer(regs, SCI_BRR.addr, 0xFF, Size.BYTE);
        writeBuffer(regs, SCI_SCR.addr, 0, Size.BYTE);
        writeBuffer(regs, SCI_TDR.addr, 0xFF, Size.BYTE);
        writeBuffer(regs, SCI_SSR.addr, 0x84, Size.BYTE);
        writeBuffer(regs, SCI_RDR.addr, 0, Size.BYTE);
    }
}
