package sh2;

import omegadrive.util.Size;
import omegadrive.util.Util;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static sh2.Sh2Util.BASE_SH2_MMREG;
import static sh2.Sh2Util.Sh2Access.MASTER;
import static sh2.Sh2Util.Sh2Access.SLAVE;

public final class Sh2Memory implements IMemory {

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

	public ByteBuffer[] bios = new ByteBuffer[2];
	private IntBuffer[] biosViewDWORD = new IntBuffer[2];
	private ShortBuffer[] biosViewWord = new ShortBuffer[2];

	private ByteBuffer sdram;
	public ByteBuffer rom;

	public int romSize = SDRAM_SIZE,
			romMask = SDRAM_MASK;

	private Sh2MMREG[] sh2MMREGS = new Sh2MMREG[2];
	private S32XMMREG s32XMMREG;

	public Sh2Util.Sh2Access sh2Access = MASTER;

	public Sh2Memory(ByteBuffer rom) {
		this();
		s32XMMREG = S32XMMREG.instance;
		this.rom = rom;
		romSize = rom.capacity();
		romMask = (int) Math.pow(2, Util.log2(romSize) + 1) - 1;
		System.out.println("Rom size: " + Integer.toHexString(romSize) + ", mask: " + Integer.toHexString(romMask));
	}

	public Sh2Memory() {
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
		try {
			if (address >= 0 && address < BOOT_ROM_SIZE) {
				return Sh2Util.readBuffer(bios[sh2Access.ordinal()], address, size);
			} else if (address >= START_ROM && address < END_ROM) {
				address &= romMask;
				address = address > romSize - 1 ? address - (romSize) : address;
				return Sh2Util.readBuffer(rom, address & romMask, size);
			} else if (address >= START_ROM_CACHE && address < END_ROM_CACHE) {
				address &= romMask;
				address = address > romSize - 1 ? address - (romSize) : address;
				return Sh2Util.readBuffer(rom, address & romMask, size);
			} else if (address >= S32XMMREG.START_32X_SYSREG_CACHE && address < S32XMMREG.END_32X_COLPAL_CACHE) {
				return s32XMMREG.read(address, size);
			} else if (address >= S32XMMREG.START_32X_SYSREG && address < S32XMMREG.END_32X_COLPAL) {
				return s32XMMREG.read(address, size);
			} else if (address >= START_SDRAM && address < END_SDRAM) {
				return Sh2Util.readBuffer(sdram, address & SDRAM_MASK, size);
			} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
				return Sh2Util.readBuffer(sdram, address & SDRAM_MASK, size);
			} else if ((address & 0xfffff000) == START_DATA_ARRAY) {
				return sh2MMREGS[sh2Access.ordinal()].readCache(address, size);
			} else if (address >= BASE_SH2_MMREG) {
				if ((address & 0xFF00_0000) != 0xFF00_0000) {
					throw new RuntimeException(sh2Access + ", read : " + size + " " + Integer.toHexString(address));
				}
				return sh2MMREGS[sh2Access.ordinal()].read(address & 0xFFFF, size);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		throw new RuntimeException(sh2Access + ", read : " + size + " " + Integer.toHexString(address));
	}

	private void write(int address, int val, Size size) {
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
		} else if (address >= START_SDRAM_CACHE && address < END_SDRAM_CACHE) {
			Sh2Util.writeBuffer(sdram, address & SDRAM_MASK, val, size);
		} else if (address >= S32XMMREG.START_OVER_IMAGE && address < S32XMMREG.END_OVER_IMAGE) {
			s32XMMREG.write(address, val, size);
		} else if ((address & 0xfffff000) == START_DATA_ARRAY) {
			sh2MMREGS[sh2Access.ordinal()].writeCache(address, val, size);
		} else if (address >= BASE_SH2_MMREG) {
			if ((address & 0xFF00_0000) != 0xFF00_0000) {
				throw new RuntimeException(sh2Access + ", write address: " + Integer.toHexString(address) + " " + size);
//				return;
			}
			sh2MMREGS[sh2Access.ordinal()].write(address & 0xFFFF, val, size);
		} else {
			throw new RuntimeException(sh2Access + ", write : " + size + " " + Integer.toHexString(address));
		}
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

	@Override
	public void setSh2Access(Sh2Util.Sh2Access sh2Access) {
		this.sh2Access = sh2Access;
		S32XMMREG.sh2Access = sh2Access;
	}

	@Override
	public void sqWriteTomemoryInst(int addr, int i) {
		throw new RuntimeException();
	}

	@Override
	public void regmapWritehandle32Inst(int tra, int i) {
		throw new RuntimeException();
	}

	@Override
	public void read64i(int register, float[] fRm, int i) {
		throw new RuntimeException();
	}

	@Override
	public void write64i(int i, float[] fRm, int i1) {
		throw new RuntimeException();
	}

	@Override
	public int regmapReadhandle32i(int qacr0) {
		throw new RuntimeException();
	}

	@Override
	public IntBuffer getSQ0() {
		throw new RuntimeException();
	}

	@Override
	public IntBuffer getSQ1() {
		throw new RuntimeException();
	}

	public static final int _dword_index(int address) {
		return address >>> 2;
	}

	public static final int _word_index(int address) {
		return address >>> 1;
	}
}
