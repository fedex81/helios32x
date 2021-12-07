package sh2;

import omegadrive.system.BaseSystem;
import omegadrive.util.Size;
import omegadrive.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.dict.S32xMemAccessDelay;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static sh2.Sh2Util.CpuDeviceAccess.MASTER;
import static sh2.Sh2Util.CpuDeviceAccess.SLAVE;

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

	public static final int START_DATA_ARRAY = 0xC000_0000;
	public static final int START_ONCHIP_MOD = 0xFFFF_FE00;
	public static final int START_DRAM_MODE = 0xFFFF_8000;
	public static final int END_DRAM_MODE = 0xFFFF_C000;

	public ByteBuffer[] bios = new ByteBuffer[2];
	private IntBuffer[] biosViewDWORD = new IntBuffer[2];
	private ShortBuffer[] biosViewWord = new ShortBuffer[2];

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
		romMask = (int) Math.pow(2, Util.log2(romSize) + 1) - 1;
		System.out.println("Rom size: " + Integer.toHexString(romSize) + ", mask: " + Integer.toHexString(romMask));
	}

	private Sh2Memory() {
		bios[MASTER.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		bios[SLAVE.ordinal()] = ByteBuffer.allocate(BOOT_ROM_SIZE);
		sdram = ByteBuffer.allocateDirect(SDRAM_SIZE);
		rom = ByteBuffer.allocateDirect(SDRAM_SIZE);
		biosViewDWORD[MASTER.ordinal()] = bios[MASTER.ordinal()].asIntBuffer();
		biosViewDWORD[SLAVE.ordinal()] = bios[SLAVE.ordinal()].asIntBuffer();
		biosViewWord[MASTER.ordinal()] = bios[MASTER.ordinal()].asShortBuffer();
		biosViewWord[SLAVE.ordinal()] = bios[SLAVE.ordinal()].asShortBuffer();

		sh2MMREGS[MASTER.ordinal()] = new Sh2MMREG(MASTER);
		sh2MMREGS[SLAVE.ordinal()] = new Sh2MMREG(SLAVE);
	}

	private int read(int address, Size size) {
		Sh2Util.CpuDeviceAccess sh2Access = BaseSystem.getAccessType();
		int res = 0;
		if (address >= 0 && address < BOOT_ROM_SIZE) {
			res = Sh2Util.readBuffer(bios[sh2Access.ordinal()], address, size);
			deviceAccessType = S32xMemAccessDelay.BOOT_ROM;
		} else if (address >= START_ROM && address < END_ROM) {
			address &= romMask;
			address = address > romSize - 1 ? address - (romSize) : address;
			res = Sh2Util.readBuffer(rom, address & romMask, size);
			deviceAccessType = S32xMemAccessDelay.ROM;
		} else if (address >= START_ROM_CACHE && address < END_ROM_CACHE) {
			address &= romMask;
			address = address > romSize - 1 ? address - (romSize) : address;
			res = Sh2Util.readBuffer(rom, address & romMask, size);
			deviceAccessType = S32xMemAccessDelay.ROM;
		} else if (address >= S32XMMREG.START_32X_SYSREG_CACHE && address < S32XMMREG.END_32X_COLPAL_CACHE) {
			res = s32XMMREG.read(address, size);
		} else if (address >= S32XMMREG.START_32X_SYSREG && address < S32XMMREG.END_32X_COLPAL) {
			res = s32XMMREG.read(address, size);
		} else if (address >= START_SDRAM && address < END_SDRAM) {
			res = Sh2Util.readBuffer(sdram, address & SDRAM_MASK, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			res = Sh2Util.readBuffer(sdram, address & SDRAM_MASK, size);
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
			res = sh2MMREGS[sh2Access.ordinal()].readCache(address, size);
		} else if ((address & START_ONCHIP_MOD) == START_ONCHIP_MOD) {
			res = sh2MMREGS[sh2Access.ordinal()].read(address & 0xFFFF, size);
		} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
			res = sh2MMREGS[sh2Access.ordinal()].read(address & 0xFFFF, size);
		} else {
			LOG.error("{} read from addr: {}, {}", sh2Access, Integer.toHexString(address), size);
			throw new RuntimeException(sh2Access + ", read : " + size + " " + Integer.toHexString(address));
		}
		S32xMemAccessDelay.addReadCpuDelay(deviceAccessType);
		return res;
	}

	private void write(int address, int val, Size size) {
		Sh2Util.CpuDeviceAccess sh2Access = BaseSystem.getAccessType();
		if (address >= S32XMMREG.START_32X_SYSREG && address < S32XMMREG.END_32X_COLPAL) {
			s32XMMREG.write(address, val, size);
		} else if (address >= S32XMMREG.START_32X_SYSREG_CACHE && address < S32XMMREG.END_32X_COLPAL_CACHE) {
			s32XMMREG.write(address, val, size);
		} else if (address >= S32XMMREG.START_DRAM_CACHE && address < S32XMMREG.END_DRAM_CACHE) {
			s32XMMREG.write(address, val, size);
		} else if (address >= S32XMMREG.START_DRAM && address < S32XMMREG.END_DRAM) {
			s32XMMREG.write(address, val, size);
		} else if (address >= START_SDRAM && address < END_SDRAM) {
			Sh2Util.writeBuffer(sdram, address & SDRAM_MASK, val, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			Sh2Util.writeBuffer(sdram, address & SDRAM_MASK, val, size);
			deviceAccessType = S32xMemAccessDelay.SDRAM;
		} else if (address >= S32XMMREG.START_OVER_IMAGE && address < S32XMMREG.END_OVER_IMAGE) {
			s32XMMREG.write(address, val, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if (address >= S32XMMREG.START_OVER_IMAGE_CACHE && address < S32XMMREG.END_OVER_IMAGE_CACHE) {
			s32XMMREG.write(address, val, size);
			deviceAccessType = S32xMemAccessDelay.FRAME_BUFFER;
		} else if ((address & 0xfffff000) == START_DATA_ARRAY) {
			sh2MMREGS[sh2Access.ordinal()].writeCache(address, val, size);
		} else if ((address & START_ONCHIP_MOD) == START_ONCHIP_MOD) {
			sh2MMREGS[sh2Access.ordinal()].write(address & 0xFFFF, val, size);
		} else if (address >= START_DRAM_MODE && address < END_DRAM_MODE) {
			sh2MMREGS[sh2Access.ordinal()].write(address & 0xFFFF, val, size);
		} else {
			LOG.error("{} write to addr: {}, {} {}", sh2Access, Integer.toHexString(address),
					Integer.toHexString(val), size);
			throw new RuntimeException(sh2Access + ", write : " + size + " " + Integer.toHexString(address));
		}
		S32xMemAccessDelay.addWriteCpuDelay(deviceAccessType);
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
