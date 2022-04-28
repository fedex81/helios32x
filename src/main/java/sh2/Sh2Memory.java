package sh2;

import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.Sh2;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;
import sh2.sh2.prefetch.Sh2Prefetch;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xMemAccessDelay.*;
import static sh2.sh2.cache.Sh2Cache.*;

public final class Sh2Memory implements IMemory {

	private static final Logger LOG = LogManager.getLogger(Sh2Memory.class.getSimpleName());

	private static final int BOOT_ROM_SIZE = 0x4000; // 16kb
	private static final int BOOT_ROM_MASK = BOOT_ROM_SIZE - 1;
	public static final int SDRAM_SIZE = 0x4_0000; // 256kb
	private static final int MAX_ROM_SIZE = 0x40_0000; // 256kb
	public static final int SDRAM_MASK = SDRAM_SIZE - 1;
	private static final int ROM_MASK = MAX_ROM_SIZE - 1;

	public static final int CACHE_THROUGH_OFFSET = 0x2000_0000;

	public static final int START_SDRAM_CACHE = 0x600_0000;
	public static final int START_SDRAM = CACHE_THROUGH_OFFSET + START_SDRAM_CACHE;
	public static final int END_SDRAM_CACHE = START_SDRAM_CACHE + SDRAM_SIZE;
	public static final int END_SDRAM = START_SDRAM + SDRAM_SIZE;

	public static final int START_ROM_CACHE = 0x200_0000;
	public static final int START_ROM = CACHE_THROUGH_OFFSET + START_ROM_CACHE;
	public static final int END_ROM_CACHE = START_ROM_CACHE + 0x40_0000; //4 Mbit window;
	public static final int END_ROM = START_ROM + 0x40_0000; //4 Mbit window;

	public static final int START_CACHE_FLUSH = 0x6000_0000;
	public static final int END_CACHE_FLUSH = 0x8000_0000;
	public static final int START_DATA_ARRAY = 0xC000_0000;
	public static final int ONCHIP_REG_MASK = 0xE000_4000;
	public static final int START_DRAM_MODE = 0xFFFF_8000;
	public static final int END_DRAM_MODE = 0xFFFF_C000;

	private static final boolean SH2_ENABLE_CACHE = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.cache", "true"));

	public ByteBuffer[] bios = new ByteBuffer[2];
	public ByteBuffer sdram;
	public ByteBuffer rom;

	private final Sh2Cache[] cache = new Sh2Cache[2];
	private final Sh2Prefetch prefetch;

	public int romSize, romMask;

	private Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private S32XMMREG s32XMMREG;

	public Sh2Memory(S32XMMREG s32XMMREG, ByteBuffer rom, BiosHolder biosHolder, Sh2Prefetch.Sh2DrcContext... drcCtx) {
		this.s32XMMREG = s32XMMREG;
		this.rom = rom;
		bios[MASTER.ordinal()] = biosHolder.getBiosData(MASTER);
		bios[SLAVE.ordinal()] = biosHolder.getBiosData(SLAVE);
		sdram = ByteBuffer.allocateDirect(SDRAM_SIZE);
		cache[MASTER.ordinal()] = SH2_ENABLE_CACHE ? new Sh2CacheImpl(MASTER, this) : Sh2Cache.createNoCacheInstance(MASTER, this);
		cache[SLAVE.ordinal()] = SH2_ENABLE_CACHE ? new Sh2CacheImpl(SLAVE, this) : Sh2Cache.createNoCacheInstance(SLAVE, this);
		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER, cache[MASTER.ordinal()]);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE, cache[SLAVE.ordinal()]);

		romSize = rom.capacity();
		romMask = Util.getRomMask(romSize);
		prefetch = new Sh2Prefetch(this, cache, drcCtx);
		LOG.info("Rom size: {}, mask: {}", th(romSize), th(romMask));
	}

	@Override
	public int read(int address, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
		address &= 0xFFFF_FFFF;
		int res = 0;
		switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
			case CACHE_USE_H3:
			case CACHE_PURGE_H3: //chaotix, bit 27,28 are ignored -> 4
			case CACHE_ADDRESS_ARRAY_H3: //chaotix
			case CACHE_DATA_ARRAY_H3: //vr
				return cache[cpuAccess.ordinal()].cacheMemoryRead(address, size);
			case CACHE_THROUGH_H3:
				if (address >= START_ROM && address < END_ROM) {
					//TODO RV bit, sh2 should stall
					if (DmaFifo68k.rv) {
						LOG.warn("{} sh2 access to ROM when RV={}, addr: {} {}", cpuAccess, DmaFifo68k.rv, th(address), size);
					}
					res = readBuffer(rom, address & romMask, size);
					S32xMemAccessDelay.addReadCpuDelay(ROM);
				} else if (address >= S32xDict.START_32X_SYSREG && address < S32xDict.END_32X_COLPAL) {
					res = s32XMMREG.read(address, size);
				} else if (address >= START_SDRAM && address < END_SDRAM) {
					res = readBuffer(sdram, address & SDRAM_MASK, size);
					S32xMemAccessDelay.addReadCpuDelay(SDRAM);
				} else if (address >= S32xDict.START_DRAM && address < S32xDict.END_DRAM) {
					res = s32XMMREG.read(address, size);
					S32xMemAccessDelay.addReadCpuDelay(FRAME_BUFFER);
				} else if (address >= START_OVER_IMAGE && address < END_OVER_IMAGE) {
					res = s32XMMREG.read(address, size);
					S32xMemAccessDelay.addReadCpuDelay(FRAME_BUFFER);
				} else if (address >= CACHE_THROUGH_OFFSET && address < CACHE_THROUGH_OFFSET + BOOT_ROM_SIZE) {
					res = readBuffer(bios[cpuAccess.ordinal()], address & BOOT_ROM_MASK, size);
					S32xMemAccessDelay.addReadCpuDelay(BOOT_ROM);
				}
				break;
			case CACHE_IO_H3: //0xF
				if ((address & ONCHIP_REG_MASK) == ONCHIP_REG_MASK) {
					res = sh2MMREGS[cpuAccess.ordinal()].read(address & 0xFFFF, size);
				} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
					res = sh2MMREGS[cpuAccess.ordinal()].readDramMode(address & 0xFFFF, size);
				} else {
					LOG.error("{} read from addr: {}, {}", cpuAccess, th(address), size);
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
		switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
			case CACHE_USE_H3:
			case CACHE_PURGE_H3:
			case CACHE_ADDRESS_ARRAY_H3:
			case CACHE_DATA_ARRAY_H3: //vr
				cache[cpuAccess.ordinal()].cacheMemoryWrite(address, val, size);
				break;
			case CACHE_THROUGH_H3:
				if (address >= START_DRAM && address < END_DRAM) {
					if (s32XMMREG.fm > 0) {
						s32XMMREG.write(address, val, size);
					} else {
						LOG.warn("{} sh2 ignoring access to FB when FM={}, addr: {} {}", cpuAccess, s32XMMREG.fm, th(address), size);
					}
				} else if (address >= START_SDRAM && address < END_SDRAM) {
					writeBuffer(sdram, address & SDRAM_MASK, val, size);
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
				}
				break;
			case CACHE_IO_H3: //0xF
				if ((address & ONCHIP_REG_MASK) == ONCHIP_REG_MASK) {
					sh2MMREGS[cpuAccess.ordinal()].write(address & 0xFFFF, val, size);
				} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
					sh2MMREGS[cpuAccess.ordinal()].writeDramMode(address & 0xFFFF, val, size);
				} else {
					LOG.error("{} read from addr: {}, {}", cpuAccess, th(address), size);
				}
				break;
			default:
				LOG.error("{} write to addr: {}, {} {}", cpuAccess, th(address), th(val), size);
				if (true) throw new RuntimeException();
				break;
		}
		prefetch.dataWrite(cpuAccess, address, val, size);
	}

	public void fetch(Sh2.FetchResult fetchResult, S32xUtil.CpuDeviceAccess cpu) {
		prefetch.fetch(fetchResult, cpu);
	}

	@Override
	public int fetchDelaySlot(int pc, Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
		return prefetch.fetchDelaySlot(pc, ft, cpu);
	}

	public Sh2MMREG getSh2MMREGS(CpuDeviceAccess cpu) {
		return sh2MMREGS[cpu.ordinal()];
	}

	@Override
	public void resetSh2() {
		sh2MMREGS[MASTER.ordinal()].reset();
		sh2MMREGS[SLAVE.ordinal()].reset();
	}
}