package sh2;

import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xMemAccessDelay;

import java.nio.ByteBuffer;

import static omegadrive.util.Util.th;
import static sh2.S32xUtil.*;
import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;

public final class Sh2Memory implements IMemory {

	private static final Logger LOG = LogManager.getLogger(Sh2Memory.class.getSimpleName());

	private static final int BOOT_ROM_SIZE = 0x4000; // 16kb
	private static final int SDRAM_SIZE = 0x4_0000; // 256kb
	private static final int MAX_ROM_SIZE = 0x40_0000; // 256kb
	private static final int SDRAM_MASK = SDRAM_SIZE - 1;
	private static final int ROM_MASK = MAX_ROM_SIZE - 1;

	public static final int CACHE_THROUGH_OFFSET = 0x2000_0000;
	public static final int CACHE_PURGE_OFFSET = 0x4000_0000;

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
	public static final int START_ONCHIP_MOD = 0xFFFF_FE00;
	public static final int START_DRAM_MODE = 0xFFFF_8000;
	public static final int END_DRAM_MODE = 0xFFFF_C000;

	public ByteBuffer[] bios = new ByteBuffer[2];
	private ByteBuffer sdram;
	public ByteBuffer rom;

	public int romSize = SDRAM_SIZE,
			romMask = SDRAM_MASK;

	private Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private S32XMMREG s32XMMREG;
	private int deviceAccessType;

	public Sh2Memory(S32XMMREG s32XMMREG, ByteBuffer rom) {
		this();
		this.s32XMMREG = s32XMMREG;
		this.rom = rom;
		romSize = rom.capacity();
		romMask = (int) Math.pow(2, Util.log2(romSize - 1) + 1) - 1;
		LOG.info("Rom size: {}, mask: {}", th(romSize), th(romMask));
	}

	private Sh2Memory() {
		bios[MASTER.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		bios[SLAVE.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		sdram = ByteBuffer.allocateDirect(SDRAM_SIZE);
		rom = ByteBuffer.allocateDirect(SDRAM_SIZE);
		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE);
	}

	@Override
	public int read(int address, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
		int res = 0;
		if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			res = readBuffer(sdram, address & SDRAM_MASK, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= START_ROM && address < END_ROM) {
			res = readBuffer(rom, address & romMask, size);
			deviceAccessType = S32xMemAccessDelay.ROM;
		} else if (address >= START_ROM_CACHE && address < END_ROM_CACHE) {
			res = readBuffer(rom, address & romMask, size);
			deviceAccessType = S32xMemAccessDelay.ROM;
		} else if (address >= S32XMMREG.START_32X_SYSREG_CACHE && address < S32XMMREG.END_32X_COLPAL_CACHE) {
			res = s32XMMREG.read(address, size);
		} else if (address >= S32XMMREG.START_32X_SYSREG && address < S32XMMREG.END_32X_COLPAL) {
			res = s32XMMREG.read(address, size);
		} else if (address >= START_SDRAM && address < END_SDRAM) {
			res = readBuffer(sdram, address & SDRAM_MASK, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			res = readBuffer(sdram, address & SDRAM_MASK, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= S32XMMREG.START_DRAM && address < S32XMMREG.END_DRAM) {
			res = s32XMMREG.read(address, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_DRAM_CACHE && address < S32XMMREG.END_DRAM_CACHE) {
			res = s32XMMREG.read(address, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_OVER_IMAGE && address < S32XMMREG.END_OVER_IMAGE) {
			res = s32XMMREG.read(address, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_OVER_IMAGE_CACHE && address < S32XMMREG.END_OVER_IMAGE_CACHE) {
			res = s32XMMREG.read(address, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if ((address & 0xfffff000) == START_DATA_ARRAY) {
			res = sh2MMREGS[cpuAccess.ordinal()].readCache(address, size);
		} else if ((address & START_ONCHIP_MOD) == START_ONCHIP_MOD) {
			res = sh2MMREGS[cpuAccess.ordinal()].read(address & 0xFFFF, size);
		} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
			res = sh2MMREGS[cpuAccess.ordinal()].readDramMode(address & 0xFFFF, size);
		} else if (address >= 0 && address < BOOT_ROM_SIZE) {
			res = readBuffer(bios[cpuAccess.ordinal()], address, size);
			deviceAccessType = S32xMemAccessDelay.BOOT_ROM;
		} else {
			LOG.error("{} read from addr: {}, {}", cpuAccess, Integer.toHexString(address), size);
		}
		S32xMemAccessDelay.addReadCpuDelay(deviceAccessType);
		return (int) (res & size.getMask());
	}

	@Override
	public void write(int address, int val, Size size) {
		CpuDeviceAccess cpuAccess = Md32xRuntimeData.getAccessTypeExt();
		val &= size.getMask();
		if (address >= S32XMMREG.START_DRAM_CACHE && address < S32XMMREG.END_DRAM_CACHE) {
			s32XMMREG.write(address, val, size);
		} else if (address >= S32XMMREG.START_DRAM && address < S32XMMREG.END_DRAM) {
			s32XMMREG.write(address, val, size);
		} else if (address >= START_SDRAM && address < END_SDRAM) {
			writeBuffer(sdram, address & SDRAM_MASK, val, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			writeBuffer(sdram, address & SDRAM_MASK, val, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= S32XMMREG.START_OVER_IMAGE && address < S32XMMREG.END_OVER_IMAGE) {
			s32XMMREG.write(address, val, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_OVER_IMAGE_CACHE && address < S32XMMREG.END_OVER_IMAGE_CACHE) {
			s32XMMREG.write(address, val, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_32X_SYSREG && address < S32XMMREG.END_32X_COLPAL) {
			s32XMMREG.write(address, val, size);
		} else if (address >= S32XMMREG.START_32X_SYSREG_CACHE && address < S32XMMREG.END_32X_COLPAL_CACHE) {
			s32XMMREG.write(address, val, size);
		} else if ((address & 0xfffff000) == START_DATA_ARRAY) {
			sh2MMREGS[cpuAccess.ordinal()].writeCache(address, val, size);
		} else if ((address & START_ONCHIP_MOD) == START_ONCHIP_MOD) {
			sh2MMREGS[cpuAccess.ordinal()].write(address & 0xFFFF, val, size);
		} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
			sh2MMREGS[cpuAccess.ordinal()].writeDramMode(address & 0xFFFF, val, size);
		} else if ((address & CACHE_PURGE_OFFSET) == CACHE_PURGE_OFFSET) { //cache purge
//			LOG.info("Cache purge: {}", th(address));
		} else if (address >= START_CACHE_FLUSH && address < END_CACHE_FLUSH) {
			LOG.info("Cache flush: {}", th(address));
		} else {
			LOG.error("{} write to addr: {}, {} {}", cpuAccess, th(address), th(val), size);
		}
		S32xMemAccessDelay.addWriteCpuDelay(deviceAccessType);
	}

	private PrefetchContext[] prefetchContexts;

	public void setPrefetchContexts(PrefetchContext... prefetchContexts) {
		this.prefetchContexts = prefetchContexts;
	}

	public static class PrefetchContext {
		public static final int PREFETCH_LOOKAHEAD = 0x16; //0x12;

		public int pc, start, end, prefetchPc, pcMasked;
		public int memAccessDelay;
		public ByteBuffer buf;
		public final int[] prefetchWords = new int[PREFETCH_LOOKAHEAD << 1];
	}

	@Override
	public void prefetch(int pc, CpuDeviceAccess cpu) {
//		LOG.info("{} Prefetch: {}", Md32xRuntimeData.getAccessTypeExt(), th(pc));
		final PrefetchContext pctx = prefetchContexts[cpu.ordinal()];
		pctx.start = (pc & 0xFF_FFFF) + (-PrefetchContext.PREFETCH_LOOKAHEAD << 1);
		pctx.end = (pc & 0xFF_FFFF) + (PrefetchContext.PREFETCH_LOOKAHEAD << 1);
		switch (pc >> 24) {
			case 6:
				pctx.start = Math.max(0, pctx.start) & SDRAM_MASK;
				pctx.end = Math.min(romSize - 1, pctx.end) & SDRAM_MASK;
				pctx.pcMasked = pc & SDRAM_MASK;
				pctx.memAccessDelay = S32xMemAccessDelay.SDRAM;
				pctx.buf = sdram;
				break;
			case 2:
				pctx.start = Math.max(0, pctx.start) & romMask;
				pctx.end = Math.min(romSize - 1, pctx.end) & romMask;
				pctx.pcMasked = pc & romMask;
				pctx.memAccessDelay = S32xMemAccessDelay.ROM;
				pctx.buf = rom;
				break;
			case 0:
				pctx.buf = bios[Md32xRuntimeData.getAccessTypeExt().ordinal()];
				pctx.start = Math.max(0, pctx.start);
				pctx.end = Math.min(pctx.buf.capacity() - 1, pctx.end);
				pctx.pcMasked = pc;
				pctx.memAccessDelay = S32xMemAccessDelay.BOOT_ROM;
				break;
			default:
				if ((pc >>> 28) == 0xC) {
					pctx.start = Math.max(0, pctx.start) & Sh2MMREG.DATA_ARRAY_MASK;
					pctx.end = Math.min(Sh2MMREG.DATA_ARRAY_SIZE - 1, pctx.end) & Sh2MMREG.DATA_ARRAY_MASK;
					pctx.memAccessDelay = S32xMemAccessDelay.SYS_REG;
					pctx.buf = sh2MMREGS[cpu.ordinal()].getDataArray();
					pctx.pcMasked = pc & Sh2MMREG.DATA_ARRAY_MASK;
				} else {
					LOG.error("{} Unhandled prefetch: {}", cpu, th(pc));
					throw new RuntimeException("Unhandled prefetch: " + th(pc));
				}
				break;
		}

		for (int bytePos = pctx.start; bytePos < pctx.end; bytePos += 2) {
			int w = ((bytePos - pctx.pcMasked) >> 1) + PrefetchContext.PREFETCH_LOOKAHEAD;
			pctx.prefetchWords[w] = pctx.buf.getShort(bytePos) & 0xFFFF;
		}
		pctx.prefetchPc = pc;
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
