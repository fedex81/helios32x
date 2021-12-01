package sh2;

import omegadrive.Device;
import omegadrive.bus.md.GenesisBus;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.util.Size;
import omegadrive.util.Util;
import omegadrive.util.VideoMode;
import omegadrive.vdp.model.BaseVdpAdapterEventSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import sh2.dict.S32xDict;

import java.nio.ByteBuffer;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xBus extends GenesisBus {

    private static final Logger LOG = LogManager.getLogger(S32xBus.class.getSimpleName());
    static final boolean verboseMd = false;

    public static final int START_HINT_VECTOR_WRITEABLE = 0x70;
    public static final int END_HINT_VECTOR_WRITEABLE = 0x74;
    public static final int START_ROM_MIRROR = 0x88_0000;
    public static final int END_ROM_MIRROR = 0x90_0000;
    public static final int START_ROM_MIRROR_BANK = END_ROM_MIRROR;
    public static final int END_ROM_MIRROR_BANK = 0xA0_0000;
    public static final int ROM_WINDOW_MASK = 0x7_FFFF; //according to docs, *NOT* 0xF_FFFF
    public static final int START_32X_SYSREG = 0xA1_5100;
    public static final int END_32X_SYSREG = START_32X_SYSREG + 0x80;
    public static final int START_32X_VDPREG = END_32X_SYSREG;
    public static final int END_32X_VDPREG = START_32X_VDPREG + 0x80;
    public static final int START_32X_COLPAL = END_32X_VDPREG;
    public static final int END_32X_COLPAL = START_32X_COLPAL + 0x200;

    private ByteBuffer rom;
    private ByteBuffer bios68k;
    private ByteBuffer writeableHintRom = ByteBuffer.allocate(4).putInt(-1);

    private S32XMMREG s32XMMREG;
    public Sh2Context masterCtx, slaveCtx;
    private Sh2 sh2;

    private int romMask, romSize;

    private int bankSetValue = 0;
    private int bankSetShift = bankSetValue << 20;

    @Override
    public GenesisBusProvider attachDevice(Device device) {
        if (device instanceof Sh2) {
            sh2 = (Sh2) device;
        } else if (device instanceof S32XMMREG) {
            s32XMMREG = (S32XMMREG) device;
        }
        return super.attachDevice(device);
    }

    public void setRom(ByteBuffer b) {
        rom = b;
        romSize = rom.capacity();
        romMask = (int) Math.pow(2, Util.log2(romSize) + 1) - 1;
        s32XMMREG.setCart(romSize > 0 ? 1 : 0);
    }

    @Override
    public long read(long address, Size size) {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.M68K;
        address &= 0xFF_FFFFF;
        if (s32XMMREG.aden > 0) {
            return readAdapterEnOn((int) address, size);
        } else {
            return readAdapterEnOff((int) address, size);
        }
    }

    @Override
    public void write(long address, long data, Size size) {
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.M68K;
        address &= 0xFF_FFFFF;
        logInfo("Write address: {}, data: {}, size: {}", Long.toHexString(address),
                Long.toHexString(data), size);
        if (s32XMMREG.aden > 0) {
            writeAdapterEnOn((int) address, (int) data, size);
        } else {
            writeAdapterEnOff((int) address, (int) data, size);
        }
    }

    private long readAdapterEnOn(int address, Size size) {
        long res = 0;
        S32XMMREG.sh2Access = Sh2Util.Sh2Access.M68K;
        if (address < 0x100) {
            if (address >= START_HINT_VECTOR_WRITEABLE && address < END_HINT_VECTOR_WRITEABLE) {
                res = writeableHintRom.getInt(0);
                if (res != -1) {
                    LOG.info("HINT vector read, address: {}, size: {}", Long.toHexString(address), size);
                    res = Sh2Util.readBuffer(writeableHintRom, address & 3, size);
                } else {
                    res = Sh2Util.readBuffer(bios68k, address, size);
                }
            }
            res = Sh2Util.readBuffer(bios68k, address, size);
        } else if (address >= START_ROM_MIRROR && address < END_ROM_MIRROR) {
            address &= ROM_WINDOW_MASK;
            address &= romMask;
            address = address > romSize - 1 ? address - (romSize) : address;
            res = Sh2Util.readBuffer(rom, address & romMask, size);
        } else if (address >= START_ROM_MIRROR_BANK && address < END_ROM_MIRROR_BANK) {
            int val = bankSetShift | (address & 0xF_FFFF);
            res = Sh2Util.readBuffer(rom, val, size);
        } else if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
            int addr = (address - START_32X_SYSREG + 0x4000); //START_32X_SYSREG_CACHE;
            res = read32xWord(addr, size);
        } else if (address >= START_32X_VDPREG && address < END_32X_VDPREG) {
            int addr = (address - START_32X_VDPREG + 0x4100); //START_32X_VDPREG_CACHE;
            res = read32xWord(addr, size);
        } else if (address >= START_32X_COLPAL && address < END_32X_COLPAL) {
            int addr = (address - START_32X_COLPAL + 0x4200); //START_32X_COLPAL_CACHE;
            res = read32xWord(addr, size);
        } else if (address >= 0xA130EC && address < 0xA130F0) {
            res = 0x4d415253; //'MARS'
        } else {
            res = super.read(address, size);
        }
        logInfo("Read address: {}, size: {}, result: {}",
                Long.toHexString(address), size, Long.toHexString(res));
        return res;
    }

    private long readAdapterEnOff(int address, Size size) {
        long res = 0;
        if (address >= 0xA130EC && address < 0xA130F0) {
            res = 0x4d415253; //'MARS'
        } else if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
            int addr = (address - START_32X_SYSREG + 0x4000); //START_32X_SYSREG_CACHE;
            res = read32xWord(addr, size);
        } else {
            res = super.read(address, size);
        }
        logInfo("Read address: {}, size: {}, result: {}",
                Long.toHexString(address), size, Long.toHexString(res));
        return res;
    }

    private void writeAdapterEnOn(int address, int data, Size size) {
        if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
            int addr = (address - START_32X_SYSREG + 0x4000); //START_32X_SYSREG_CACHE;
            if (((addr & 0xFF) & ~1) == S32xDict.BANK_SET_REG) {
                bankSetValue = (data & 3);
                bankSetShift = bankSetValue << 20;
            }
            write32xWord(addr, data, size);
        } else if (address >= START_32X_VDPREG && address < END_32X_VDPREG) {
            int addr = (address - START_32X_VDPREG + 0x4100); //START_32X_VDPREG_CACHE;
            write32xWord(addr, data, size);
        } else if (address >= START_32X_COLPAL && address < END_32X_COLPAL) {
            int addr = (address - START_32X_COLPAL + 0x4200); //START_32X_COLPAL_CACHE;
            write32xWord(addr, data, size);
        } else if (address >= START_HINT_VECTOR_WRITEABLE && address < END_HINT_VECTOR_WRITEABLE) {
            LOG.info("HINT vector write, address: {}, data: {}, size: {}", Long.toHexString(address),
                    Long.toHexString(data), size);
            Sh2Util.writeBuffer(writeableHintRom, address & 3, data, size);
        } else {
            super.write(address, data, size);
        }
    }

    private void writeAdapterEnOff(int address, int data, Size size) {
        if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
            int addr = (address - START_32X_SYSREG + 0x4000); //START_32X_SYSREG_CACHE;
            if (((addr & 0xFF) & ~1) == S32xDict.BANK_SET_REG) {
                bankSetValue = (data & 3);
                bankSetShift = bankSetValue << 20;
            }
            write32xWord(addr, data, size);
        } else {
            super.write(address, data, size);
        }
    }

    private void write32xWord(int address, int data, Size size) {
        if (size != Size.LONG) {
            s32XMMREG.write(address, data, size);
        } else {
            s32XMMREG.write(address, data >> 16, Size.WORD);
            s32XMMREG.write(address + 2, data & 0xFFFF, Size.WORD);
        }
    }

    private int read32xWord(int address, Size size) {
        if (size != Size.LONG) {
            return s32XMMREG.read(address, size);
        } else {
            int res = s32XMMREG.read(address, Size.WORD) << 16;
            return res | s32XMMREG.read(address + 2, Size.WORD);
        }
    }


    private static void logInfo(String str, Object... args) {
        if (verboseMd) {
            LOG.info(new ParameterizedMessage(str, args));
        }
    }

    public MarsVdp getMarsVdp() {
        return s32XMMREG.getVdp();
    }

    @Override
    public void onVdpEvent(BaseVdpAdapterEventSupport.VdpEvent event, Object value) {
        super.onVdpEvent(event, value);
        switch (event) {
            case V_BLANK_CHANGE:
                s32XMMREG.setVBlankOn((boolean) value);
                break;
            case H_BLANK_CHANGE:
                s32XMMREG.setHBlankOn((boolean) value);
                break;
            case VIDEO_MODE:
                s32XMMREG.updateVideoMode((VideoMode) value);
                break;
        }
    }

    public S32XMMREG getS32XMMREG() {
        return s32XMMREG;
    }

    public void setBios68k(ByteBuffer bios68k) {
        this.bios68k = bios68k;
    }

    public void resetSh2() {
        sh2.reset(masterCtx);
        sh2.reset(slaveCtx);
        sh2.memory.resetSh2();
    }
}