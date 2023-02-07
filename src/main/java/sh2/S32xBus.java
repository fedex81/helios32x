package sh2;

import omegadrive.Device;
import omegadrive.bus.md.GenesisBus;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.sound.PwmProvider;
import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import omegadrive.vdp.model.BaseVdpAdapterEventSupport;
import org.slf4j.Logger;
import sh2.dict.S32xDict;
import sh2.sh2.Sh2;
import sh2.sh2.Sh2Context;
import sh2.vdp.MarsVdp;

import java.nio.ByteBuffer;

import static m68k.cpu.Cpu.PC_MASK;
import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class S32xBus extends GenesisBus {

    private static final Logger LOG = LogHelper.getLogger(S32xBus.class.getSimpleName());
    static final boolean verboseMd = false;
    private BiosHolder.BiosData bios68k;
    private final ByteBuffer writeableHintRom = ByteBuffer.allocate(4).putInt(-1);

    private S32XMMREG s32XMMREG;
    public Sh2Context masterCtx, slaveCtx;
    private Sh2 sh2;
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
        s32XMMREG.setCart(b.capacity());
    }

    public int getBankSetValue() {
        return bankSetValue;
    }

    @Override
    public int read(int address, Size size) {
        int res = 0;
        if (s32XMMREG.aden > 0) {
            res = readAdapterEnOn(address & PC_MASK, size);
        } else {
            res = readAdapterEnOff(address & PC_MASK, size);
        }
        return res & size.getMask();
    }

    @Override
    public void write(int address, int data, Size size) {
        data &= size.getMask();
        address &= PC_MASK;
        if (verboseMd) {
            LOG.info("Write address: {}, data: {}, size: {}", th(address), th(data), size);
        }
        if (s32XMMREG.aden > 0) {
            writeAdapterEnOn(address, data, size);
        } else {
            writeAdapterEnOff(address, data, size);
        }
    }

    private int readAdapterEnOn(int address, Size size) {
        int res = 0;
        if (address < M68K_END_VECTOR_ROM) {
            res = bios68k.readBuffer(address, size);
            if (address >= M68K_START_HINT_VECTOR_WRITEABLE && address < M68K_END_HINT_VECTOR_WRITEABLE) {
                res = readHIntVector(address, size);
            }
        } else if (address >= M68K_START_ROM_MIRROR && address < M68K_END_ROM_MIRROR) {
            if (!DmaFifo68k.rv) {
                res = super.read(address & M68K_ROM_WINDOW_MASK, size);
            } else {
                LOG.warn("Ignoring read access to ROM mirror when RV={}, addr: {} {}", DmaFifo68k.rv, th(address), size);
            }
        } else if (address >= M68K_START_ROM_MIRROR_BANK && address < M68K_END_ROM_MIRROR_BANK) {
            if (!DmaFifo68k.rv) {
                res = super.read(bankSetShift | (address & M68K_ROM_MIRROR_MASK), size);
            } else {
                LOG.warn("Ignoring read access to ROM mirror bank when RV={}, addr: {} {}", DmaFifo68k.rv, th(address), size);
            }
        } else if (address >= M68K_START_FRAME_BUFFER && address < M68K_END_FRAME_BUFFER) {
            res = read32xWord((address & DRAM_MASK) | START_DRAM, size);
        } else if (address >= M68K_START_OVERWRITE_IMAGE && address < M68K_END_OVERWRITE_IMAGE) {
            res = read32xWord((address & DRAM_MASK) | START_OVER_IMAGE, size);
        } else if (address >= M68K_START_32X_SYSREG && address < M68K_END_32X_SYSREG) {
            res = read32xWord((address & M68K_MASK_32X_SYSREG) | SH2_SYSREG_32X_OFFSET, size);
        } else if (address >= M68K_START_32X_VDPREG && address < M68K_END_32X_VDPREG) {
            res = read32xWord((address & M68K_MASK_32X_VDPREG) | SH2_VDPREG_32X_OFFSET, size);
        } else if (address >= M68K_START_32X_COLPAL && address < M68K_END_32X_COLPAL) {
            res = read32xWord((address & M68K_MASK_32X_COLPAL) | SH2_COLPAL_32X_OFFSET, size);
        } else if (address >= M68K_START_MARS_ID && address < M68K_END_MARS_ID) {
            assert address == M68K_START_MARS_ID;
            res = 0x4d415253; //'MARS'
        } else {
            if (!DmaFifo68k.rv && address <= GenesisBus.DEFAULT_ROM_END_ADDRESS) {
                LOG.warn("Ignoring read access to ROM when RV={}, addr: {} {}", DmaFifo68k.rv, th(address), size);
                return size.getMask();
            }
            res = super.read(address, size);
        }
        if (verboseMd) {
            LOG.info("Read address: {}, size: {}, result: {}", th(address), size, th(res));
        }
        return res;
    }

    private int readAdapterEnOff(int address, Size size) {
        int res = 0;
        if (address >= M68K_START_MARS_ID && address < M68K_END_MARS_ID) {
            res = 0x4d415253; //'MARS'
        } else if (address >= M68K_START_32X_SYSREG && address < M68K_END_32X_SYSREG) {
            res = read32xWord((address & M68K_MASK_32X_SYSREG) | SH2_SYSREG_32X_OFFSET, size);
        } else {
            res = super.read(address, size);
        }
        if (verboseMd) {
            LOG.info("Read address: {}, size: {}, result: {}", th(address), size, th(res));
        }
        return res;
    }

    private void writeAdapterEnOn(int address, int data, Size size) {
        if (address >= M68K_START_FRAME_BUFFER && address < M68K_END_FRAME_BUFFER) {
            write32xWord((address & DRAM_MASK) | START_DRAM, data, size);
        } else if (address >= M68K_START_OVERWRITE_IMAGE && address < M68K_END_OVERWRITE_IMAGE) {
            write32xWord((address & DRAM_MASK) | START_OVER_IMAGE, data, size);
        } else if (address >= M68K_START_32X_SYSREG && address < M68K_END_32X_SYSREG) {
            int addr = (address & M68K_MASK_32X_SYSREG) | SH2_SYSREG_32X_OFFSET;
            write32xWordDirect(addr, data, size);
            checkBankSetRegister(addr, size);
        } else if (address >= M68K_START_32X_VDPREG && address < M68K_END_32X_VDPREG) {
            write32xWord((address & M68K_MASK_32X_VDPREG) | SH2_VDPREG_32X_OFFSET, data, size);
        } else if (address >= M68K_START_32X_COLPAL && address < M68K_END_32X_COLPAL) {
            write32xWord((address & M68K_MASK_32X_COLPAL) | SH2_COLPAL_32X_OFFSET, data, size);
        } else if (address >= M68K_START_ROM_MIRROR_BANK && address < M68K_END_ROM_MIRROR_BANK) {
            if (!DmaFifo68k.rv) {
                //NOTE it could be writing to SRAM via the rom mirror
                super.write((address & M68K_ROM_MIRROR_MASK) | bankSetShift, data, size);
            } else {
                LOG.warn("Ignoring write access to ROM mirror bank when RV={}, addr: {}, addr68k: {}, val: {} {}",
                        DmaFifo68k.rv, th(address), th(address & M68K_ROM_WINDOW_MASK), th(data), size);
            }
        } else if (address >= M68K_START_ROM_MIRROR && address < M68K_END_ROM_MIRROR) {
            //TODO should not happen, SoulStar
            if (!DmaFifo68k.rv) {
                super.write(address & M68K_ROM_WINDOW_MASK, data, size);
            } else {
                LOG.warn("Ignoring write access to ROM mirror when RV={}, addr: {}, addr68k: {}, val: {} {}",
                        DmaFifo68k.rv, th(address), th(address & M68K_ROM_WINDOW_MASK), th(data), size);
            }
        } else if (address >= M68K_START_HINT_VECTOR_WRITEABLE && address < M68K_END_HINT_VECTOR_WRITEABLE) {
            if (verboseMd) LOG.info("HINT vector write, address: {}, data: {}, size: {}", th(address),
                    th(data), size);
            writeBuffer(writeableHintRom, address & 3, data, size);
        } else {
            if (address < M68K_END_VECTOR_ROM) {
                LOG.warn("Ignoring write access to vector rom, RV={}, addr: {} {}", DmaFifo68k.rv, th(address), size);
                return;
            }
            if (!DmaFifo68k.rv && address <= GenesisBus.DEFAULT_ROM_END_ADDRESS) {
                LOG.warn("Ignoring write access to ROM when RV={}, addr: {} {}", DmaFifo68k.rv, th(address), size);
                return;
            }
            super.write(address, data, size);
        }
    }

    private void writeAdapterEnOff(int address, int data, Size size) {
        if (address >= M68K_START_32X_SYSREG && address < M68K_END_32X_SYSREG) {
            int addr = (address & M68K_MASK_32X_SYSREG) | SH2_SYSREG_32X_OFFSET;
            write32xWordDirect(addr, data, size);
            checkBankSetRegister(addr, size);
        } else {
            super.write(address, data, size);
        }
    }

    private void checkBankSetRegister(int address, Size size) {
        RegSpecS32x r = S32xDict.getRegSpec(CpuDeviceAccess.M68K, address);
        if (r == RegSpecS32x.MD_BANK_SET) {
            int val = readWordFromBuffer(s32XMMREG.regContext, r);
            bankSetValue = (val & 3);
            bankSetShift = bankSetValue << 20;
            if (verboseMd) LOG.info("BankSet now: {}", bankSetValue);
        }
        assert r == RegSpecS32x.MD_INT_CTRL ? size != Size.LONG : true;
    }

    private void write32xWord(int address, int data, Size size) {
        if (s32XMMREG.fm > 0) {
            LOG.warn("Ignoring access to S32X memory from MD when FM={}, addr: {} {}", s32XMMREG.fm, th(address), size);
            return;
        }
        write32xWordDirect(address, data, size);
    }

    private void write32xWordDirect(int address, int data, Size size) {
        if (size != Size.LONG) {
            s32XMMREG.write(address, data, size);
        } else {
            s32XMMREG.write(address, (data >> 16) & 0xFFFF, Size.WORD);
            s32XMMREG.write(address + 2, data & 0xFFFF, Size.WORD);
        }
    }

    private int read32xWord(int address, Size size) {
        //TODO needs to be more granular, ie. md can access sysRegs when fm = 1
//        if (ENFORCE_FM_BIT_ON_READS && s32XMMREG.fm > 0) {
//            LOG.warn("Ignoring access to S32X memory from MD when FM={}, addr: {} {}", s32XMMREG.fm, th(address), size);
//            return size.getMask();
//        }
        if (size != Size.LONG) {
            return s32XMMREG.read(address, size);
        } else {
            int res = s32XMMREG.read(address, Size.WORD) << 16;
            return res | (s32XMMREG.read(address + 2, Size.WORD) & 0xFFFF);
        }
    }

    private int readHIntVector(int address, Size size) {
        int res = writeableHintRom.getInt(0);
        if (res != -1) {
            res = readBuffer(writeableHintRom, address & 3, size);
            if (verboseMd) LOG.info("HINT vector read, rv {}, address: {}, {} {}", DmaFifo68k.rv,
                    th(address), th(res), size);
        } else {
            res = bios68k.readBuffer(address, size);
        }
        return res;
    }

    public MarsVdp getMarsVdp() {
        return s32XMMREG.getVdp();
    }

    @Override
    public void onVdpEvent(BaseVdpAdapterEventSupport.VdpEvent event, Object value) {
        super.onVdpEvent(event, value);
        switch (event) {
            case V_BLANK_CHANGE -> s32XMMREG.setVBlank((boolean) value);
            case H_BLANK_CHANGE -> s32XMMREG.setHBlank((boolean) value);
            case VIDEO_MODE -> s32XMMREG.updateVideoMode((VideoMode) value);
        }
    }

    public S32XMMREG getS32XMMREG() {
        return s32XMMREG;
    }

    public void setBios68k(BiosHolder.BiosData bios68k) {
        this.bios68k = bios68k;
    }

    public PwmProvider getPwm() {
        return soundProvider.getPwm();
    }

    public void resetSh2() {
        CpuDeviceAccess cpu = Md32xRuntimeData.getAccessTypeExt();
        //NOTE this changes the access type
        sh2.reset(masterCtx);
        sh2.reset(slaveCtx);
        masterCtx.devices.sh2MMREG.reset();
        slaveCtx.devices.sh2MMREG.reset();
        getS32XMMREG().fm = 0;
        Md32xRuntimeData.setAccessTypeExt(cpu);
    }
}
