package sh2;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.slf4j.Logger;
import sh2.BiosHolder.BiosData;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.event.SysEventManager;
import sh2.sh2.Sh2;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.sh2.prefetch.Sh2PrefetchSimple;
import sh2.sh2.prefetch.Sh2Prefetcher;
import sh2.util.SdramSyncTester;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xMemAccessDelay.*;
import static sh2.sh2.cache.Sh2Cache.*;

public final class Sh2Memory implements IMemory {

	private static final Logger LOG = LogHelper.getLogger(Sh2Memory.class.getSimpleName());

	private static final String ILLEGAL_ACCESS_STR = "{} sh2 {} access to {} when {}={}, addr: {} {}";

	private static final boolean SDRAM_SYNC_TESTER = false;
	public BiosData[] bios = new BiosData[2];
	public ByteBuffer sdram;
	public ByteBuffer rom;

	public final Sh2Cache[] cache = new Sh2Cache[2];
	private final Sh2Prefetcher prefetch;
	private final MemAccessStats memAccessStats = MemAccessStats.NO_STATS;

	public int romSize, romMask;

	private final Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private final S32XMMREG s32XMMREG;
	private final MemoryDataCtx memoryDataCtx;
	private final Sh2.Sh2Config config;

	private final SdramSyncTester sdramSyncTester;

	public Sh2Memory(S32XMMREG s32XMMREG, ByteBuffer rom, BiosHolder biosHolder, Sh2Prefetch.Sh2DrcContext... drcCtx) {
		memoryDataCtx = new MemoryDataCtx();
		this.s32XMMREG = s32XMMREG;
		memoryDataCtx.rom = this.rom = rom;
		bios[MASTER.ordinal()] = biosHolder.getBiosData(MASTER);
		bios[SLAVE.ordinal()] = biosHolder.getBiosData(SLAVE);
		memoryDataCtx.bios = bios;
		memoryDataCtx.sdram = sdram = ByteBuffer.allocateDirect(SH2_SDRAM_SIZE);
		Sh2.Sh2Config sh2Config = Sh2.Sh2Config.get();
		cache[MASTER.ordinal()] = new Sh2CacheImpl(MASTER, this);
		cache[SLAVE.ordinal()] = new Sh2CacheImpl(SLAVE, this);
		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER, cache[MASTER.ordinal()]);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE, cache[SLAVE.ordinal()]);

		memoryDataCtx.romSize = romSize = rom.capacity();
		memoryDataCtx.romMask = romMask = Util.getRomMask(romSize);
		prefetch = sh2Config.drcEn ? new Sh2Prefetch(this, cache, drcCtx) : new Sh2PrefetchSimple(this, cache);
		config = Sh2.Sh2Config.get();
		sdramSyncTester = SDRAM_SYNC_TESTER ? new SdramSyncTester(sdram) : SdramSyncTester.NO_OP;
		LOG.info("Rom size: {}, mask: {}", th(romSize), th(romMask));
	}

	@Override
	public int read(int address, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
		address &= 0xFFFF_FFFF;
		assert size == Size.LONG ? (address & 3) == 0 : true;
		assert size == Size.WORD ? (address & 1) == 0 : true;
		int res = 0;
		if (SH2_MEM_ACCESS_STATS) {
			memAccessStats.addMemHit(true, address, size);
		}
		switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
			case CACHE_USE_H3:
			case CACHE_PURGE_H3: //chaotix, bit 27,28 are ignored -> 4
			case CACHE_ADDRESS_ARRAY_H3: //chaotix
			case CACHE_DATA_ARRAY_H3: //vr
				return cache[cpuAccess.ordinal()].cacheMemoryRead(address, size);
			case CACHE_THROUGH_H3:
				if (address >= SH2_START_ROM && address < SH2_END_ROM) {
					//TODO RV bit, sh2 should stall
					assert DmaFifo68k.rv ? logWarnIllegalAccess(cpuAccess, "read", "ROM", "rv",
							DmaFifo68k.rv, address, size) : true;
					res = readBuffer(rom, address & romMask, size);
					S32xMemAccessDelay.addReadCpuDelay(ROM);
				} else if (address >= S32xDict.START_32X_SYSREG && address < S32xDict.END_32X_COLPAL) {
					if (ENFORCE_FM_BIT_ON_READS && s32XMMREG.fm == 0 && address >= START_32X_VDPREG) {
						logWarnIllegalAccess(cpuAccess, "read", "VDP regs", "FM",
								s32XMMREG.fm, address, size);
						return size.getMask();
					}
					res = s32XMMREG.read(address, size);
				} else if (address >= SH2_START_SDRAM && address < SH2_END_SDRAM) {
					res = readBuffer(sdram, address & SH2_SDRAM_MASK, size);
					S32xMemAccessDelay.addReadCpuDelay(SDRAM);
					if (SDRAM_SYNC_TESTER) {
						sdramSyncTester.readSyncCheck(cpuAccess, address, size);
					}
				} else if (address >= S32xDict.START_DRAM && address < S32xDict.END_DRAM) {
					if (ENFORCE_FM_BIT_ON_READS && s32XMMREG.fm == 0) {
						logWarnIllegalAccess(cpuAccess, "read", "FB", "FM",
								s32XMMREG.fm, address, size);
						return size.getMask();
					}
					res = s32XMMREG.read(address, size);
					S32xMemAccessDelay.addReadCpuDelay(FRAME_BUFFER);
				} else if (address >= START_OVER_IMAGE && address < END_OVER_IMAGE) {
					if (ENFORCE_FM_BIT_ON_READS && s32XMMREG.fm == 0) {
						logWarnIllegalAccess(cpuAccess, "read", "overw FB", "FM",
								s32XMMREG.fm, address, size);
						return size.getMask();
					}
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
					throw new RuntimeException();
				}
				break;
			default:
				res = size.getMask();
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
		if (SH2_MEM_ACCESS_STATS) {
			memAccessStats.addMemHit(false, address, size);
		}
		//flag to check if code memory has been changed
		boolean hasMemoryChanged = false;
		switch ((address >>> CACHE_ADDRESS_BITS) & 0xFF) {
			case CACHE_USE_H3:
			case CACHE_DATA_ARRAY_H3: //vr
			case CACHE_PURGE_H3:
			case CACHE_ADDRESS_ARRAY_H3:
				//NOTE: vf slave writes to sysReg 0x401c, 0x4038 via cache
				hasMemoryChanged = cache[cpuAccess.ordinal()].cacheMemoryWrite(address, val, size);
				//NOTE if not in cache we need to invalidate any block containing it,
				//NOTE as the next cache access will reload the data from MEM
				break;
			case CACHE_THROUGH_H3:
				if (address >= START_DRAM && address < END_DRAM) {
					if (s32XMMREG.fm == 0) {
						logWarnIllegalAccess(cpuAccess, "write", "FB", "FM",
								s32XMMREG.fm, address, size);
						return;
					}
					s32XMMREG.write(address, val, size);

				} else if (address >= SH2_START_SDRAM && address < SH2_END_SDRAM) {
					if (SDRAM_SYNC_TESTER) {
						sdramSyncTester.writeSyncCheck(cpuAccess, address, val, size);
					}
					hasMemoryChanged = writeBuffer(sdram, address & SH2_SDRAM_MASK, val, size);
					S32xMemAccessDelay.addWriteCpuDelay(SDRAM);
				} else if (address >= START_OVER_IMAGE && address < END_OVER_IMAGE) {
					if (s32XMMREG.fm == 0) {
						logWarnIllegalAccess(cpuAccess, "write", "overw FB", "FM",
								s32XMMREG.fm, address, size);
						return;
					}
					s32XMMREG.write(address, val, size);
				} else if (address >= START_32X_SYSREG && address < END_32X_SYSREG) {
					s32XMMREG.write(address, val, size);
				} else if (address >= START_32X_VDPREG && address < END_32X_COLPAL) {
					if (s32XMMREG.fm == 0) {
						logWarnIllegalAccess(cpuAccess, "write", " VDP regs", "FM",
								s32XMMREG.fm, address, size);
						return;
					}
					s32XMMREG.write(address, val, size);
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
		if (hasMemoryChanged) {
			prefetch.dataWrite(cpuAccess, address, val, size);
		}
		if (config.pollDetectEn) {
			Sh2Prefetch.checkPoller(cpuAccess, SysEventManager.SysEvent.SDRAM, address, val, size);
		}
	}

	@Override
	public void invalidateCachePrefetch(CacheInvalidateContext ctx) {
		prefetch.invalidateCachePrefetch(ctx);
	}

	public void fetch(Sh2.FetchResult fetchResult, S32xUtil.CpuDeviceAccess cpu) {
		prefetch.fetch(fetchResult, cpu);
	}

	@Override
	public int fetchDelaySlot(int pc, Sh2.FetchResult ft, S32xUtil.CpuDeviceAccess cpu) {
		return prefetch.fetchDelaySlot(pc, ft, cpu);
	}

	@Override
	public Sh2MMREG getSh2MMREGS(CpuDeviceAccess cpu) {
		return sh2MMREGS[cpu.ordinal()];
	}

	@Override
	public MemoryDataCtx getMemoryDataCtx() {
		return memoryDataCtx;
	}

	@Override
	public void newFrame() {
		prefetch.newFrame();
		if (SDRAM_SYNC_TESTER) {
			sdramSyncTester.newFrameSync();
		}
	}

	@Override
	public void resetSh2() {
		sh2MMREGS[MASTER.ordinal()].reset();
		sh2MMREGS[SLAVE.ordinal()].reset();
	}

	private static boolean logWarnIllegalAccess(CpuDeviceAccess cpu, String rw, String memType, String accessType,
												Object val, int address, Size size) {
		LOG.warn(ILLEGAL_ACCESS_STR, cpu, rw, memType, accessType, val, th(address), size);
		return true;
	}
}