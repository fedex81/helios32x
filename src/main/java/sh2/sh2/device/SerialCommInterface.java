package sh2.sh2.device;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.Sh2Dict.RegSpec;
import static sh2.dict.Sh2Dict.RegSpec.*;
import static sh2.sh2.device.Sh2DeviceHelper.Sh2DeviceType.SCI;

/**
 * SCI
 * <p>
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class SerialCommInterface implements Sh2Device {

    private static final Logger LOG = LogHelper.getLogger(SerialCommInterface.class.getSimpleName());

    static class SciData {
        public CpuDeviceAccess sender;
        public int dataInTransit;
        public boolean isDataInTransit;
    }

    private static final int SCI_SSR_TDRE_BIT_POS = 7;
    private static final int SCI_SSR_TEND_BIT_POS = 2;
    private static final int SCI_SSR_RDRF_BIT_POS = 6;

    public final static int TIE = 0;
    public final static int RIE = 1;

    private final static boolean verbose = false;

    private final ByteBuffer regs;
    private final CpuDeviceAccess cpu;
    private final IntControl intControl;

    private int tdre, rdrf;
    private boolean txEn, rxEn;

    private SerialCommInterface other;

    public static final SciData sciData = new SciData();

    public SerialCommInterface(CpuDeviceAccess cpu, IntControl intControl, ByteBuffer regs) {
        this.cpu = cpu;
        this.regs = regs;
        this.intControl = intControl;
        reset();
    }

    public void setOther(SerialCommInterface other) {
        this.other = other;
        other.other = this;
        assert other.cpu != cpu;
    }

    @Override
    public int read(RegSpec regSpec, int pos, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI read {}: {}", cpu, regSpec.name, size);
            throw new RuntimeException();
        }
        assert pos == regSpec.addr : th(pos) + ", " + th(regSpec.addr);
        int res = readBuffer(regs, regSpec.addr, Size.BYTE);
        if (verbose) LOG.info("{} SCI read {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(res), size);
        return res;
    }

    @Override
    public void write(RegSpec regSpec, int pos, int value, Size size) {
        if (size != Size.BYTE) {
            LOG.error("{} SCI write {}: {} {}", cpu, regSpec.name,
                    Integer.toHexString(value), size);
            throw new RuntimeException();
        }
        if (verbose) LOG.info("{} SCI write {}: {} {}", cpu, regSpec.name,
                Integer.toHexString(value), size);
        boolean write = true;
        assert pos == regSpec.addr : th(pos) + ", " + th(regSpec.addr);

        switch (regSpec) {
            case SCI_SCR:
                boolean prevTxEn = txEn;
                rxEn = (value & 0x10) > 0;
                txEn = (value & 0x20) > 0;
                if (verbose)
                    LOG.info("{} {}, rxEn {}, txEn {}, TIE {}, RIE: {}, TEIE(tx) {}, MPIE: {}", cpu, regSpec, rxEn, txEn,
                            (value & 0x80) > 0, (value & 0x40) > 0, (value & 4) > 0, (value & 8) > 0);
                if (prevTxEn && !txEn) {
                    setTdre(1);
                } else if (txEn && !prevTxEn) {
                    step(1);
                }
                break;
            case SCI_SSR:
                handleSsrWrite(value);
                write = false;
                break;
            case SCI_SMR:
                if (verbose)
                    LOG.info("{} {} communication mode: {}", cpu, regSpec.name, ((value & 0x80) == 0 ? "a" : "clock ") + "sync");
                break;
            case SCI_TDR:
                if (verbose) LOG.info("{} {} Data written TDR: {}", cpu, regSpec.name, th(value));
                setTdre(0);
                break;
            case SCI_RDR:
                LOG.warn("{} {} Data written RDR: {}", cpu, regSpec.name, th(value));
                write = true;
                break;
        }
        if (write) {
            writeBuffer(regs, pos, value, size);
        }
    }

    //most bits should be reset only when writing 0
    private void handleSsrWrite(int value) {
        //txDisabled locks TDRE to 1
        int wasTdre = tdre;
        int tdreVal = (value & 0x80) > 0 || !txEn ? tdre : 0;
        setTdre(tdreVal);
        if (wasTdre > 0 && (value & 0x80) == 0) {
            setBit(regs, SCI_SSR.addr, SCI_SSR_TEND_BIT_POS, 0, Size.BYTE);
            step(1);
        }
        int rdrfVal = (value & 0x40) == 0 ? 0 : rdrf;
        setRdrf(rdrfVal);
        for (int i = 3; i < 6; i++) {
            if ((value & (1 << i)) == 0) {
                setBit(regs, SCI_SSR.addr, i, 0, Size.BYTE);
            }
        }
        setBit(regs, SCI_SSR.addr, 0, value & 1, Size.BYTE);
        if (verbose)
            LOG.info("{} SSR write: {}, state: {}", cpu, th(value), th(readBuffer(regs, SCI_SSR.addr, Size.BYTE)));
    }

    @Override
    public void step(int cycles) {
        if (txEn && tdre == 0) {// && txDataReady) {
            int data = readBuffer(regs, SCI_TDR.addr, Size.BYTE);
            int scr = readBuffer(regs, SCI_SCR.addr, Size.BYTE);
            setTdre(1);
            setBit(regs, SCI_SSR.addr, SCI_SSR_TEND_BIT_POS, 1, Size.BYTE);
            sendData(data);
            if ((scr & 0x80) > 0) { //TIE
                intControl.setExternalIntPending(SCI, TIE, true);
            }
            other.step(1);
            return;
        } else if (rxEn && sciData.isDataInTransit && sciData.sender != cpu) {
            if (verbose) LOG.info("{} receiving data: {}", cpu, th(sciData.dataInTransit));
            writeBuffer(regs, SCI_RDR.addr, sciData.dataInTransit, Size.BYTE);
            setRdrf(1);
            int scr = readBuffer(regs, SCI_SCR.addr, Size.BYTE);
            sciData.isDataInTransit = false;
            if ((scr & 0x40) > 0) { //RIE
                intControl.setExternalIntPending(SCI, RIE, true);
            }
        }
    }

    private void setTdre(int value) {
        setBit(regs, SCI_SSR.addr, SCI_SSR_TDRE_BIT_POS, value, Size.BYTE);
        tdre = value;
    }

    private void setRdrf(int value) {
        setBit(regs, SCI_SSR.addr, SCI_SSR_RDRF_BIT_POS, value, Size.BYTE);
        rdrf = value;
    }

    private void sendData(int data) {
        if (verbose) LOG.info("{} sending data: {}", cpu, th(data));
        sciData.sender = cpu;
        sciData.dataInTransit = data;
        sciData.isDataInTransit = true;
    }

    @Override
    public void reset() {
        if (verbose) LOG.info("{} SCI reset start", cpu);
        writeBuffer(regs, SCI_SMR.addr, 0, Size.BYTE);
        writeBuffer(regs, SCI_BRR.addr, 0xFF, Size.BYTE);
        writeBuffer(regs, SCI_SCR.addr, 0, Size.BYTE);
        writeBuffer(regs, SCI_TDR.addr, 0xFF, Size.BYTE);
        writeBuffer(regs, SCI_SSR.addr, 0x84, Size.BYTE);
        writeBuffer(regs, SCI_RDR.addr, 0, Size.BYTE);

        tdre = 1;
        rdrf = 0;
        txEn = rxEn = false;
        if (verbose) LOG.info("{} SCI reset end", cpu);
    }
}
