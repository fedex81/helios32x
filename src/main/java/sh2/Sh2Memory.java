package sh2;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.slf4j.Logger;
import sh2.BiosHolder.BiosData;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.Md32x.SH2_ENABLE_CACHE;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xMemAccessDelay.*;
import static sh2.sh2.cache.Sh2Cache.*;

public final class Sh2Memory implements IMemory {

	private static final Logger LOG = LogHelper.getLogger(Sh2Memory.class.getSimpleName());

	public BiosData[] bios = new BiosData[2];
	public ByteBuffer sdram;
	public ByteBuffer rom;

	public final Sh2Cache[] cache = new Sh2Cache[2];
	private Sh2Prefetch prefetch;

	public int romSize, romMask;

	private final Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private final S32XMMREG s32XMMREG;

	public Sh2Memory(S32XMMREG s32XMMREG, ByteBuffer rom, BiosHolder biosHolder) {
		this.s32XMMREG = s32XMMREG;
		init(rom, biosHolder, SH2_ENABLE_CACHE);
	}

	private void init(ByteBuffer rom, BiosHolder biosHolder, boolean enableCache) {
		this.rom = rom;
		bios[MASTER.ordinal()] = biosHolder.getBiosData(MASTER);
		bios[SLAVE.ordinal()] = biosHolder.getBiosData(SLAVE);
		sdram = ByteBuffer.allocateDirect(SH2_SDRAM_SIZE);
		romSize = rom.capacity();
		romMask = Util.getRomMask(romSize);
		LOG.info("Rom size: {}, mask: {}", th(romSize), th(romMask));
		reInitCache(enableCache);
	}

	public void reInitCache(boolean enableCache) {
		cache[MASTER.ordinal()] = enableCache ? new Sh2CacheImpl(MASTER, this) : Sh2Cache.createNoCacheInstance(MASTER, this);
		cache[SLAVE.ordinal()] = enableCache ? new Sh2CacheImpl(SLAVE, this) : Sh2Cache.createNoCacheInstance(SLAVE, this);
		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER, cache[MASTER.ordinal()]);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE, cache[SLAVE.ordinal()]);
		prefetch = new Sh2Prefetch(this, cache);
	}

	@Override
	public int read(int address, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
		address &= 0xFFFF_FFFF;
		assert size == Size.LONG ? (address & 3) == 0 : true;
		assert size == Size.WORD ? (address & 1) == 0 : true;
		int res = 0;
		switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
			case CACHE_USE_H3:
            case CACHE_PURGE_H3: //chaotix, bit 27,28 are ignored -> 4
            case CACHE_ADDRESS_ARRAY_H3: //chaotix
            case CACHE_DATA_ARRAY_H3: //vr
                return cache[cpuAccess.ordinal()].cacheMemoryRead(address, size);
            case CACHE_THROUGH_H3:
                if (address >= SH2_START_ROM && address < SH2_END_ROM) {
                    //TODO RV bit, sh2 should stall
					if (DmaFifo68k.rv) {
						LOG.warn("{} sh2 access to ROM when RV={}, addr: {} {}", cpuAccess, DmaFifo68k.rv, th(address), size);
					}
					res = readBuffer(rom, address & romMask, size);
					S32xMemAccessDelay.addReadCpuDelay(ROM);
				} else if (address >= S32xDict.START_32X_SYSREG && address < S32xDict.END_32X_COLPAL) {
					res = s32XMMREG.read(address, size);
				} else if (address >= SH2_START_SDRAM && address < SH2_END_SDRAM) {
					res = readBuffer(sdram, address & SH2_SDRAM_MASK, size);
					S32xMemAccessDelay.addReadCpuDelay(SDRAM);
				} else if (address >= S32xDict.START_DRAM && address < S32xDict.END_DRAM) {
					res = s32XMMREG.read(address, size);
					S32xMemAccessDelay.addReadCpuDelay(FRAME_BUFFER);
				} else if (address >= START_OVER_IMAGE && address < END_OVER_IMAGE) {
					res = s32XMMREG.read(address, size);
					S32xMemAccessDelay.addReadCpuDelay(FRAME_BUFFER);
				} else if (address >= SH2_START_BOOT_ROM && address < SH2_END_BOOT_ROM) {
					res = bios[cpuAccess.ordinal()].readBuffer(address, size);
					S32xMemAccessDelay.addReadCpuDelay(BOOT_ROM);
				}
				break;
			case CACHE_IO_H3: //0xF
				if ((address & SH2_ONCHIP_REG_MASK) == SH2_ONCHIP_REG_MASK) {
					res = sh2MMREGS[cpuAccess.ordinal()].read(address & 0xFFFF, size);
				} else if (address >= SH2_START_DRAM_MODE && address < SH2_END_DRAM_MODE) {
					res = sh2MMREGS[cpuAccess.ordinal()].readDramMode(address & 0xFFFF, size);
				} else {
					LOG.error("{} read from addr: {}, {}", cpuAccess, th(address), size);
					if (true) throw new RuntimeException();
				}
				break;
			default:
				res = (int) size.getMask();
				LOG.error("{} read from addr: {}, {}", cpuAccess, th(address), size);
				if (true) throw new RuntimeException();
				break;
		}
		return (int) (res & size.getMask());
	}

	@Override
	public void write(int address, int val, Size size) {
        CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
        val &= size.getMask();
        assert size == Size.LONG ? (address & 3) == 0 : true;
        assert size == Size.WORD ? (address & 1) == 0 : true;

        switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
            case CACHE_USE_H3:
            case CACHE_PURGE_H3:
            case CACHE_ADDRESS_ARRAY_H3:
            case CACHE_DATA_ARRAY_H3: //vr
                //NOTE: vf slave writes to sysReg 0x401c, 0x4038 via cache
                cache[cpuAccess.ordinal()].cacheMemoryWrite(address, val, size);
                break;
            case CACHE_THROUGH_H3:
				if (address >= START_DRAM && address < END_DRAM) {
					if (s32XMMREG.fm > 0) {
						s32XMMREG.write(address, val, size);
					} else {
						LOG.warn("{} sh2 ignoring access to FB when FM={}, addr: {} {}", cpuAccess, s32XMMREG.fm, th(address), size);
					}
				} else if (address >= SH2_START_SDRAM && address < SH2_END_SDRAM) {
					writeBuffer(sdram, address & SH2_SDRAM_MASK, val, size);
					S32xMemAccessDelay.addWriteCpuDelay(SDRAM);
				} else if (address >= START_OVER_IMAGE && address < END_OVER_IMAGE) {
					if (s32XMMREG.fm > 0) {
						s32XMMREG.write(address, val, size);
					} else {
						LOG.warn("{} sh2 ignoring access to overwrite FB when FM={}, addr: {} {}", cpuAccess, s32XMMREG.fm, th(address), size);
					}
				} else if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
					s32XMMREG.write(address, val, size);
				} else if (address >= START_32X_VDPREG && address < END_32X_COLPAL) {
					if (s32XMMREG.fm > 0) {
						s32XMMREG.write(address, val, size);
					} else {
						LOG.warn("{} sh2 ignoring access to VDP regs when FM={}, addr: {} {}", cpuAccess, s32XMMREG.fm, th(address), size);
					}
				} else {
					LOG.error("{} write to addr: {}, {} {}", cpuAccess, th(address), th(val), size);
				}
				break;
			case CACHE_IO_H3: //0xF
				if ((address & SH2_ONCHIP_REG_MASK) == SH2_ONCHIP_REG_MASK) {
					sh2MMREGS[cpuAccess.ordinal()].write(address & 0xFFFF, val, size);
				} else if (address >= SH2_START_DRAM_MODE && address < SH2_END_DRAM_MODE) {
					sh2MMREGS[cpuAccess.ordinal()].writeDramMode(address & 0xFFFF, val, size);
				} else {
					LOG.error("{} write from addr: {}, {}", cpuAccess, th(address), size);
				}
				break;
			default:
				LOG.error("{} write to addr: {}, {} {}", cpuAccess, th(address), th(val), size);
				if (true) throw new RuntimeException();
				break;
		}
		prefetch.dataWrite(cpuAccess, address, val, size);
	}

	@Override
	public void invalidateCachePrefetch(CacheInvalidateContext ctx) {
		prefetch.invalidateCachePrefetch(ctx);
	}

	@Override
	public void prefetch(int pc, CpuDeviceAccess cpu) {
		prefetch.prefetch(pc, cpu);
	}

	@Override
	public int fetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
		return prefetch.fetch(pc, cpu);
	}

	public Sh2Prefetch getPrefetch() {
		return prefetch;
	}

	@Override
	public int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu) {
		return prefetch.fetchDelaySlot(pc, cpu);
	}

	public Sh2MMREG getSh2MMREGS(CpuDeviceAccess cpu) {
		return sh2MMREGS[cpu.ordinal()];
	}
}