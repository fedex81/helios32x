package sh2;

import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.cache.Sh2Cache;
import sh2.sh2.cache.Sh2CacheImpl;

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
	private static final int SDRAM_SIZE = 0x4_0000; // 256kb
	private static final int MAX_ROM_SIZE = 0x40_0000; // 256kb
	private static final int SDRAM_MASK = SDRAM_SIZE - 1;
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
	private static final boolean SH2_ENABLE_PREFETCH = Boolean.parseBoolean(System.getProperty("helios.32x.sh2.prefetch", "true"));

	public ByteBuffer[] bios = new ByteBuffer[2];
	private ByteBuffer sdram;
	public ByteBuffer rom;

	private Sh2Cache[] cache = new Sh2Cache[2];
	private final PrefetchContext[] prefetchContexts = {new PrefetchContext(), new PrefetchContext()};

	public int romSize = SDRAM_SIZE,
			romMask = SDRAM_MASK;

	private Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private S32XMMREG s32XMMREG;

	public Sh2Memory(S32XMMREG s32XMMREG, ByteBuffer rom) {
		this();
		this.s32XMMREG = s32XMMREG;
		this.rom = rom;
		romSize = rom.capacity();
		romMask = Util.getRomMask(romSize);
		LOG.info("Rom size: {}, mask: {}", th(romSize), th(romMask));
	}

	private Sh2Memory() {
		bios[MASTER.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		bios[SLAVE.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		sdram = ByteBuffer.allocateDirect(SDRAM_SIZE);
		rom = ByteBuffer.allocateDirect(SDRAM_SIZE);
		cache[MASTER.ordinal()] = SH2_ENABLE_CACHE ? new Sh2CacheImpl(MASTER, this) : Sh2Cache.createNoCacheInstance(MASTER, this);
		cache[SLAVE.ordinal()] = SH2_ENABLE_CACHE ? new Sh2CacheImpl(SLAVE, this) : Sh2Cache.createNoCacheInstance(SLAVE, this);
		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER, cache[MASTER.ordinal()]);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE, cache[SLAVE.ordinal()]);
	}

	@Override
	public int read(int address, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
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
					address &= bios[cpuAccess.ordinal()].capacity() - 1; //TODO t-mek
					res = readBuffer(bios[cpuAccess.ordinal()], address, size);
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
					checkPrefetch(address, val, size);
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
					checkPrefetch(address, val, size);
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
	}

	private void checkPrefetch(int writeAddr, int val, Size size) {
		writeAddr &= 0xFFF_FFFF; //drop cached vs uncached
		for (int i = 0; i < 2; i++) {
			int start = Math.max(0, prefetchContexts[i].prefetchPc - (prefetchContexts[i].prefetchLookahead << 1));
			int end = prefetchContexts[i].prefetchPc + (prefetchContexts[i].prefetchLookahead << 1);
			if (writeAddr >= start && writeAddr <= end) {
				CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
				LOG.warn("{} write, addr: {} val: {} {}, {} PF window: [{},{}]", cpuAccess,
						th(writeAddr), th(val), size, CpuDeviceAccess.cdaValues[i], th(start), th(end));
				prefetch(prefetchContexts[i].prefetchPc, CpuDeviceAccess.cdaValues[i]);
			}
		}
	}

	@Override
	public void prefetch(int pc, CpuDeviceAccess cpu) {
		if (!SH2_ENABLE_PREFETCH) return;

		final PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
		pctx.start = (pc & 0xFF_FFFF) + (-pctx.prefetchLookahead << 1);
		pctx.end = (pc & 0xFF_FFFF) + (pctx.prefetchLookahead << 1);
		switch (pc >> 24) {
			case 6:
			case 0x26:
				pctx.start = Math.max(0, pctx.start) & SDRAM_MASK;
				pctx.end = Math.min(romSize - 1, pctx.end) & SDRAM_MASK;
				pctx.pcMasked = pc & SDRAM_MASK;
				pctx.memAccessDelay = SDRAM;
				pctx.buf = sdram;
				break;
			case 2:
			case 0x22:
				pctx.start = Math.max(0, pctx.start) & romMask;
				pctx.end = Math.min(romSize - 1, pctx.end) & romMask;
				pctx.pcMasked = pc & romMask;
				pctx.memAccessDelay = S32xMemAccessDelay.ROM;
				pctx.buf = rom;
				break;
			case 0:
			case 0x20:
				pctx.buf = bios[cpu.ordinal()];
				pctx.start = Math.max(0, pctx.start);
				pctx.end = Math.min(pctx.buf.capacity() - 1, pctx.end);
				pctx.pcMasked = pc;
				pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
				break;
			default:
				if ((pc >>> 28) == 0xC) {
					pctx.start = Math.max(0, pctx.start) & Sh2Cache.DATA_ARRAY_MASK;
					pctx.end = Math.min(Sh2Cache.DATA_ARRAY_SIZE - 1, pctx.end) & Sh2Cache.DATA_ARRAY_MASK;
					pctx.memAccessDelay = S32xMemAccessDelay.SYS_REG;
					pctx.buf = cache[cpu.ordinal()].getDataArray();
					pctx.pcMasked = pc & Sh2Cache.DATA_ARRAY_MASK;
				} else {
					LOG.error("{} Unhandled prefetch: {}", cpu, th(pc));
					throw new RuntimeException("Unhandled prefetch: " + th(pc));
				}
				break;
		}
		pctx.prefetchPc = pc;
//		final Sh2Cache sh2Cache = cache[cpu.ordinal()];
//		int cacheOn = sh2Cache.getCacheContext().cacheEn;
//		boolean isCachedAccess = (cacheOn > 0) && (pc & CACHE_THROUGH_OFFSET) != CACHE_THROUGH_OFFSET;
//		final int pcBase = pc & 0xFF00_0000;
		for (int bytePos = pctx.start; bytePos < pctx.end; bytePos += 2) {
			int w = ((bytePos - pctx.pcMasked) >> 1) + pctx.prefetchLookahead;
			pctx.prefetchWords[w] = pctx.buf.getShort(bytePos) & 0xFFFF;
//			pctx.prefetchWords[w] = isCachedAccess ? sh2Cache.readDirect(pcBase + bytePos, Size.WORD) : pctx.buf.getShort(bytePos) & 0xFFFF;
//			pctx.prefetchWords[w] = sh2Cache.readDirect(pcBase + bytePos, Size.WORD);
		}
	}

	@Override
	public int fetch(int pc, S32xUtil.CpuDeviceAccess cpu) {
		if (!SH2_ENABLE_PREFETCH) {
			return read(pc, Size.WORD);
		}
		final Sh2Memory.PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
		int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
		if (Math.abs(pcDeltaWords) >= pctx.prefetchLookahead) {
			prefetch(pc, cpu);
			pcDeltaWords = 0;
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
		}
//		pfTotal++;
		S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
		return pctx.prefetchWords[pctx.prefetchLookahead + pcDeltaWords];
	}

	@Override
	public int fetchDelaySlot(int pc, S32xUtil.CpuDeviceAccess cpu) {
		if (!SH2_ENABLE_PREFETCH) {
			return read(pc, Size.WORD);
		}
		final Sh2Memory.PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
		int pcDeltaWords = (pc - pctx.prefetchPc) >> 1;
//		pfTotal++;
		int res;
		if (Math.abs(pcDeltaWords) < pctx.prefetchLookahead) {
			S32xMemAccessDelay.addReadCpuDelay(pctx.memAccessDelay);
			res = pctx.prefetchWords[pctx.prefetchLookahead + pcDeltaWords];
		} else {
			res = read(pc, Size.WORD);
//			if ((pfMiss++ & 0x7F_FFFF) == 0) {
//				LOG.info("pfTot: {}, pfMiss%: {}", pfTotal, 1.0 * pfMiss / pfTotal);
//			}
		}
		return res;
	}

	public Sh2MMREG getSh2MMREGS(CpuDeviceAccess cpu) {
		return sh2MMREGS[cpu.ordinal()];
	}

	@Override
	public void resetSh2() {
		sh2MMREGS[MASTER.ordinal()].reset();
		sh2MMREGS[SLAVE.ordinal()].reset();
	}

	@Override
	public int read8i(int address) {
		return read(address, Size.BYTE);
	}

	@Override
	public int read16i(int address) {
		return read(address, Size.WORD);
	}

	@Override
	public int read32i(int address) {
		return read(address, Size.LONG);
	}

	@Override
	public void write8i(int address, byte val) {
		write(address, val, Size.BYTE);
	}

	@Override
	public void write16i(int address, int val) {
		write(address, val, Size.WORD);
	}

	@Override
	public void write32i(int address, int val) {
		write(address, val, Size.LONG);
	}
}
