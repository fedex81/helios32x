package sh2;


/*
 *  Revision 1 -  port the code from Dcemu and use information provided by dark||raziel (done)
 *  Revision 2 -  add terminal recursivity to the interpreter and bugfix everything (hopefully done)
 *  Revision 3 -  add cycle "accurate" timing (Table 8.3 Execution Cycles Pag 183 on the Sh4 hardware manual)
 *  Revision 4 -  add idle loop detection
 *  
 *  Thanks to drk||raziel, the Mame crew,to Ivan Toledo :)
 *  
 *  
 *  In terms of cycle timing this is what the SH-4 hardware manual states
 *  
 *    Issue rate: Interval between the issue of an instruction and that of the next instruction
	• Latency: Interval between the issue of an instruction and the generation of its result
  		(completion)
	• Instruction execution pattern (see figure 8.2)
	• Locked pipeline stages
	• Interval between the issue of an instruction and the start of locking
	• Lock time: Period of locking in machine cycle units
 *  
 *  What i considered was that for instructions without lock time on table 8.3 the number of cycles of that
 *  instruction in this core is the Issue Rate field on that table.
 *  For instructions with lock time that will be the number of cycles it takes to run.
 *  This is not as accurate as it could be as we are not taking into consideration the pipeline,
 *  simultaneous execution of instructions,etc..
 */

public class Sh2 {

	/* this is a bit of an hack :)
	 * Basically what it means its that when a jump is made to a syscall function
	 * 0xFFFF will be read and handled in the interpreter accordingly
	 */
	public static final int BIOS_OPCODE = 0xFFFF;
	public static final int flagT = 0x00000001;
	public static final int flagS = 0x00000002;
	public static final int flagIMASK = 0x000000f0;
	public static final int flagQ = 0x00000100;
	public static final int flagM = 0x00000200;
	public static final int flagFD = 0x00008000;

	/*
	 * from mame to use in memory acesses
	 */
	public static final int flagBL = 0x10000000;
	public static final int flagsRB = 0x20000000;
	public static final int flagMD = 0x40000000;

	/* General Purpose Registers */
	static final int rtcnt_div[] = {0, 4, 16, 64, 256, 1024, 2048, 4096};
	static final int daysmonth[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
	public static int burstCycles = 1;
	/*
	 * defines the number of cycles we can ran before stopping the interpreter
	 */
	public int cycles;

	/* Control Registers */
	public int cycles_ran;

	/* System Registers */
	public int registers[];
	public String sh2TypeCode;

	/* Flags to access the bit fields in SR and FPSCR
	 * Sintax : flagRegName
	 */
	public int GBR, SSR, VBR, SR;
	public int MACH, MACL, PR;
	public int PC;
	public Sh2Disassembler disassembler;
	public boolean debugging = false;
	protected IMemory memory;
	private Sh2Emu.Sh2Access sh2Access;

	public Sh2(Sh2Emu.Sh2Access sh2Access, IMemory memory) {
		// GPR registers
		registers = new int[24];
		this.memory = memory;
		this.sh2Access = sh2Access;
		this.sh2TypeCode = sh2Access.name().substring(0, 1);
		// setting system registers to their initial values
		reset();
	}

	public static final int RN(int x) {
		return ((x >> 8) & 0xf);
	}

	public static final int RM(int x) {
		return ((x >> 4) & 0xf);
	}

	public static final boolean is_flag_set(int register, int flag) {
		return ((register & flag) != 0);
	}

	// get interrupt masks bits int the SR register
	public int getIMASK() {
		return (SR & flagIMASK) >>> 4;
	}

	/*
	 * Switches the general purpose register bank.
	 * There are 16 general purpose registers and the first 8 are mapped to
	 * a different bank depending on the RB bit on SR
	 * Page 26 - Sh4 Programming Model
	 */
	public void switch_gpr_banks() {
		int temp[] = new int[8];
		System.arraycopy(registers, 0, temp, 0, 8);
		System.arraycopy(registers, 16, registers, 0, 8);
		System.arraycopy(temp, 0, registers, 16, 8);
	}

	public void reset() {
		VBR = 0;
		SR = 0x700000f0;
		PC = memory.read32i(0);
		registers[15] = memory.read32i(4); //SP
		cycles = burstCycles;
		System.out.println("reset");
	}

	public void setDisassembler(Sh2Disassembler s) {
		disassembler = s;
	}

	public void NOIMP(int code) {
		cycles--;
		PC += 2;
	}

	private final void MOVI(int code) {
		int i = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		if ((i & 0x80) == 0) registers[n] = (0x000000FF & i);
		else registers[n] = (0xFFFFFF00 | i);


		cycles--;
		PC += 2;
	}

	private final void MOVWI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		registers[n] = memory.read16i(PC + 4 + (d << 1));

		if ((registers[n] & 0x8000) == 0) registers[n] &= 0x0000FFFF;
		else registers[n] |= 0xFFFF0000;


		cycles--;
		PC += 2;

	}

	private final void MOVLI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		registers[n] = memory.read32i((PC & 0xfffffffc) + 4 + (d << 2));

		cycles--;
		PC += 2;

	}

	private final void MOV(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = registers[m];

		cycles--;
		PC += 2;
	}

	private final void MOVBS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8i(registers[n], (byte) registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVWS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16i(registers[n], registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVLS(int code) {
		int m = RM(code);
		int n = RN(code);

		//	System.out.println("MOVLS source " + Integer.toHexString(registers[n]) + "destination " + Integer.toHexString(registers[m]));

		memory.write32i(registers[n], registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVBL(int code) {
		int m = RM(code);
		int n = RN(code);
		byte c;

		c = (byte) (memory.read8i(registers[m]) & 0xFF);

		registers[n] = (int) c;

		//Logger.log(Logger.CPU, String.format("movb7: r[%d]=%x,%d r[%d]=%x,%d\r", m, registers[m], registers[m], n, registers[n], registers[n]));

		//System.out.println("MOVBL @" + Integer.toHexString(registers[m]) + " value read " + registers[n] + " PC " + Integer.toHexString(PC));

		cycles--;
		PC += 2;
	}

	private final void MOVWL(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) (memory.read16i(registers[m]) & 0xFFFF);

		registers[n] = (int) w;

		cycles--;
		PC += 2;
	}

	private final void MOVLL(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = memory.read32i(registers[m]);


		cycles--;
		PC += 2;
	}

	private final void MOVBM(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] -= 1;
		memory.write8i(registers[n], (byte) registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVWM(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] -= 2;

		memory.write16i(registers[n], registers[m]);

		cycles--;
		PC += 2;

	}

	private final void MOVLM(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] -= 4;

		memory.write32i(registers[n], registers[m]);

		cycles--;
		PC += 2;

	}

	private final void MOVBP(int code) {
		int m = RM(code);
		int n = RN(code);

		byte b = (byte) (memory.read8i(registers[m]) & 0xFF);
		registers[n] = (int) b;
		if (n != m) registers[m] += 1;

		cycles--;
		PC += 2;

	}

	private final void MOVWP(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) memory.read16i(registers[m]);
		registers[n] = (int) w;
		if (n != m) registers[m] += 2;

		cycles--;
		PC += 2;

	}

	private final void MOVLP(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = memory.read32i(registers[m]);
		if (n != m) registers[m] += 4;

		cycles--;
		PC += 2;

	}

	private final void MOVBS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write8i(registers[n] + (d << 0), (byte) registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVWS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write16i(registers[n] + (d << 1), registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVLS4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		memory.write32i(registers[n] + (d << 2), registers[m]);

		//System.out.println("MOVLS4 " + Integer.toHexString(registers[n]));

		cycles--;
		PC += 2;

	}

	private final void MOVBL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		byte b = (byte) (memory.read8i(registers[m] + d) & 0xFF);
		registers[0] = (int) b;

		cycles--;
		PC += 2;
	}

	private final void MOVWL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		short w = (short) memory.read16i(registers[m] + (d << 1));
		registers[0] = (int) w;


		cycles--;
		PC += 2;
	}

	private final void MOVLL4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		registers[n] = memory.read32i(registers[m] + (d << 2));


		//System.out.println("MOVLL4 " + Integer.toHexString(registers[n]) + " @" + Integer.toHexString(registers[m] + (d *4)) );
		cycles--;
		PC += 2;
	}

	private final void MOVBS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8i(registers[n] + registers[0], (byte) registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVWS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16i(registers[n] + registers[0], registers[m]);

		cycles--;
		PC += 2;
	}

	private final void MOVLS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write32i(registers[n] + registers[0], registers[m]);

		cycles--;
		PC += 2;

	}

	private final void MOVBL0(int code) {
		int m = RM(code);
		int n = RN(code);

		byte b = (byte) memory.read8i(registers[m] + registers[0]);
		registers[n] = (int) b;

		cycles--;
		PC += 2;

	}

	private final void MOVWL0(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) memory.read16i(registers[m] + registers[0]);
		registers[n] = (int) w;

		cycles--;
		PC += 2;

	}

	private final void MOVLL0(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = memory.read32i(registers[m] + registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVBSG(int code) {
		int d = (code & 0xff);

		memory.write8i(GBR + d, (byte) registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVWSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write16i(GBR + (d << 1), registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVLSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write32i(GBR + (d << 2), registers[0]);

		cycles--;
		PC += 2;

	}

	private final void MOVBLG(int code) {
		int d = ((code >> 0) & 0xff);

		byte b = (byte) memory.read8i(GBR + (d << 0));
		registers[0] = (int) b;

		cycles--;
		PC += 2;

	}

	private final void MOVWLG(int code) {
		int d = ((code >> 0) & 0xff);

		short w = (short) memory.read16i(GBR + (d << 1));
		registers[0] = (int) w;

		cycles--;
		PC += 2;

	}

	private final void MOVLLG(int code) {
		int d = ((code >> 0) & 0xff);

		registers[0] = memory.read32i(GBR + (d << 2));


		cycles--;
		PC += 2;
	}

	private final void MOVA(int code) {
		int d = (code & 0x000000ff);

		registers[0] = ((PC & 0xfffffffc) + 4 + (d << 2));

		cycles--;
		PC += 2;
	}

	private final void MOVT(int code) {
		int n = RN(code);

		registers[n] = (SR & flagT);

		cycles--;
		PC += 2;

	}

	private final void SWAPB(int code) {
		int m = RM(code);
		int n = RN(code);

		int temp0, temp1;
		temp0 = registers[m] & 0xFFFF0000;
		temp1 = (registers[m] & 0x000000FF) << 8;
		registers[n] = (registers[m] & 0x0000FF00) >> 8;
		registers[n] = registers[n] | temp1 | temp0;

		cycles--;
		PC += 2;
	}

	private final void SWAPW(int code) {
		int m = RM(code);
		int n = RN(code);
		int temp = 0;
		temp = (registers[m] >>> 16) & 0x0000FFFF;
		registers[n] = registers[m] << 16;
		registers[n] |= temp;

		cycles--;
		PC += 2;

	}

	private final void XTRCT(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = ((registers[n] & 0xffff0000) >>> 16) |
				((registers[m] & 0x0000ffff) << 16);

		cycles--;
		PC += 2;

	}

	private final void ADD(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] += registers[m]; 
		

		/*Logger.log(Logger.CPU,String.format("add39: r[%d]=%x,%d r[%d]=%x,%d\r\n",
	            n, registers[n], registers[n],
	            m, registers[m], registers[m]));*/
		cycles--;
		PC += 2;
	}

	private final void ADDI(int code) {
		int n = RN(code);
		byte b = (byte) (code & 0xff);
		//System.out.println("ADDI before " + Integer.toHexString(registers[n]) + "value to be added " + Integer.toHexString(b));

		registers[n] += (int) b;

		/*Logger.log(Logger.CPU,String.format("add40: r[%d]=%x,%d\r\n",
	            n, registers[n], registers[n])); */

		cycles--;
		PC += 2;
	}

	private final void ADDC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0 = registers[n];
		int tmp1 = registers[n] + registers[m];
		registers[n] = tmp1 + (SR & flagT);
		if (tmp0 > tmp1)
			SR |= flagT;
		else SR &= (~flagT);
		if (tmp1 > registers[n]) SR |= flagT;

		cycles--;
		PC += 2;
		
		/*Logger.log(Logger.CPU,String.format("addc41: r[%d]=%x,%d r[%d]=%x,%d tmp0=%x,%d, tmp1=%x,%d\r\n",
	            m, registers[m], registers[m],
	            n, registers[n], registers[n],
	            tmp0, tmp0, tmp1, tmp1)); */
	}

	private final void ADDV(int code) {
		int ans;
		int m = RM(code);
		int n = RN(code);
		int dest = 0, src = 0;

		if (registers[n] >= 0) dest = 1;
		else dest = 0;
		if (registers[m] >= 0) src = 1;
		else src = 0;

		src += dest;
		registers[n] += registers[m];

		if (registers[n] >= 0)
			ans = 0;
		else ans = 1;

		ans += dest;

		if ((src == 0) || (src == 2)) {
			if (ans == 1)
				SR |= ans;
			else SR &= (~flagT);
		} else
			SR &= (~flagT);

		cycles--;
		PC += 2;

	}

	private final void CMPIM(int code) {
		int i = 0;

		if ((code & 0x80) == 0) i = (0x000000FF & code);
		else i = (0xFFFFFF00 | code);

		if (registers[0] == i)
			SR |= flagT;
		else SR &= (~flagT);
		

		/*Logger.log(Logger.CPU,String.format("cmp/eq: r[0]=%x,%d,'%c' == #%x,%d,'%c' ?\r\n",
				registers[0],registers[0], registers[0],
	        i, i,i));*/


		cycles--;
		PC += 2;

	}

	private final void CMPEQ(int code) {
		int m = RM(code);
		int n = RN(code);

		if (registers[n] == registers[m])
			SR |= flagT;
		else SR &= (~flagT);


		/*Logger.log(Logger.CPU,String.format("cmp/eq: r[%d]=%x,%d,'%c' == r[%d]=%x,%d,'%c' \r",
	        n,  registers[n],  registers[n],  registers[n],
	        m,  registers[m],  registers[m],  registers[m]));
*/

		cycles--;
		PC += 2;
	}

	private final void CMPHS(int code) {
		int m = RM(code);
		int n = RN(code);

		if (((long) (registers[n] & 0xFFFFFFFFL)) >= ((long) (registers[m] & 0xFFFFFFFFL)))
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hs: r[%d]=%x >= r[%d]=%x ?\r", n, registers[n], m, registers[m]));

		cycles--;
		PC += 2;
	}

	private final void CMPGE(int code) {
		int m = RM(code);
		int n = RN(code);

		if (registers[n] >= registers[m])
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/ge: r[%d]=%x >= r[%d]=%x ?\r\n", n, registers[n], m, registers[m]));

		//Logger.log(Logger.CPU,"CMPGE " + registers[n] + " >= " + registers[m]);
		cycles--;
		PC += 2;
	}

	private final void CMPHI(int code) {
		int m = RM(code);
		int n = RN(code);

		// AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH
		if (((long) (registers[n] & 0xFFFFFFFFL)) > ((long) (registers[m] & 0xFFFFFFFFL)))
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hi: r[%d]=%x >= r[%d]=%x ?\r", n, registers[n], m, registers[m]));

		cycles--;
		PC += 2;
	}

	private final void CMPGT(int code) {
		int m = RM(code);
		int n = RN(code);

		if (registers[n] > registers[m])
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/gt: r[%d]=%x >= r[%d]=%x ?\r", n, registers[n], m, registers[m]));

		cycles--;
		PC += 2;

	}

	private final void CMPPZ(int code) {
		int n = RN(code);

		if (registers[n] >= 0)
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x >= 0 ?\r", n, registers[n]));

		cycles--;
		PC += 2;
	}

	private final void CMPPL(int code) {
		int n = RN(code);

		if (registers[n] > 0)
			SR |= flagT;
		else SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x > 0 ?\r", n, registers[n]));

		cycles--;
		PC += 2;

		//Logger.log(Logger.CPU,"CMPPL " + registers[n]);
	}

	private final void CMPSTR(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp = registers[n] ^ registers[m];

		int HH = (tmp >>> 24) & 0xff;
		int HL = (tmp >>> 16) & 0xff;
		int LH = (tmp >>> 8) & 0xff;
		int LL = tmp & 0xff;
		if ((HH & HL & LH & LL) != 0)
			SR &= ~flagT;
		else
			SR |= flagT;

		//	Logger.log(Logger.CPU,String.format("cmp/str: r[%d]=%x >= r[%d]=%x ?\r", n, registers[n], m, registers[m]));
		cycles--;
		PC += 2;
	}

	private final void DIV1(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0, tmp2;
		int tmp1;
		int old_q;

		old_q = SR & flagQ;
		if ((0x80000000 & registers[n]) != 0)
			SR |= flagQ;
		else
			SR &= ~flagQ;

		tmp2 = registers[m];
		registers[n] <<= 1;

		registers[n] |= flagT;

		if (old_q == 0) {
			if ((SR & flagM) == 0) {
				tmp0 = registers[n];
				registers[n] -= tmp2;
				tmp1 = (registers[n] > tmp0 ? 1 : 0);
				if ((SR & flagQ) == 0)
					if (tmp1 == 1)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				else if (tmp1 == 0)
					SR |= flagQ;
				else
					SR &= ~flagQ;
			} else {
				tmp0 = registers[n];
				registers[n] += tmp2;
				tmp1 = (registers[n] < tmp0 ? 1 : 0);
				if ((SR & flagQ) == 0) {
					if (tmp1 == 0)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				} else {
					if (tmp1 == 1)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				}
			}
		} else {
			if ((SR & flagM) == 0) {
				tmp0 = registers[n];
				registers[n] += tmp2;
				tmp1 = (registers[n] < tmp0 ? 1 : 0);
				if ((SR & flagQ) == 0) {
					if (tmp1 == 1)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				} else {
					if (tmp1 == 0)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				}
			} else {
				tmp0 = registers[n];
				registers[n] -= tmp2;
				tmp1 = (registers[n] > tmp0 ? 1 : 0);
				if ((SR & flagQ) == 0) {
					if (tmp1 == 0)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				} else {
					if (tmp1 == 1)
						SR |= flagQ;
					else
						SR &= ~flagQ;
				}
			}
		}

		tmp0 = (SR & (flagQ | flagM));
		if (((tmp0) == 0) || (tmp0 == 0x300)) /* if Q == M set T else clear T */
			SR |= flagT;
		else
			SR &= ~flagT;

		//Logger.log(Logger.CPU,String.format("div1s: r[%d]=%x >= r[%d]=%x ?\r", n, registers[n], m, registers[m]));

		cycles--;
		PC += 2;
	}

	private final void DIV0S(int code) {
		int m = RM(code);
		int n = RN(code);

		if ((registers[n] & 0x80000000) == 0)
			SR &= ~flagQ;
		else
			SR |= flagQ;
		if ((registers[m] & 0x80000000) == 0)
			SR &= ~flagM;
		else
			SR |= flagM;
		if (((registers[m] ^ registers[n]) & 0x80000000) != 0)
			SR |= flagT;
		else
			SR &= ~flagT;

		cycles--;
		PC += 2;

	}

	private final void DIV0U(int code) {
		SR &= (~flagQ);
		SR &= (~flagM);
		SR &= (~flagT);

		cycles--;
		PC += 2;
	}

	private final void DMULS(int code) {
		int m = RM(code);
		int n = RN(code);

		long mult = (long) registers[n] * (long) registers[m];

//		System.out.println("DMULS " + mult);

		MACL = (int) (mult & 0xffffffff);
		MACH = (int) ((mult >>> 32) & 0xffffffff);

		cycles -= 2;
		PC += 2;
	}

	private final void DMULU(int code) {
		int m = RM(code);
		int n = RN(code);

		// this should be unsigned but oh well :/
		long mult = (long) (registers[n] & 0xffffffffL) * (long) (registers[m] & 0xffffffffL);

		System.out.println("DMULU" + Long.toHexString(mult));

		MACL = (int) (mult & 0xffffffff);
		MACH = (int) ((mult >>> 32) & 0xffffffff);

		cycles -= 2;
		PC += 2;
	}

	private final void DT(int code) {
		int n = RN(code);
		//Logger.log(Logger.CPU,"DT: R[" + n + "] = " + Integer.toHexString(registers[n]));
		registers[n]--;
		if (registers[n] == 0) {
			SR |= flagT;
		} else {
			SR &= (~flagT);
		}
		cycles--;
		PC += 2;

	}

	private final void EXTSB(int code) {
		int m = RM(code);
		int n = RN(code);


		registers[n] = registers[m];
		if ((registers[m] & 0x00000080) == 0) registers[n] &= 0x000000FF;
		else registers[n] |= 0xFFFFFF00;

		cycles--;
		PC += 2;
	}

	private final void EXTSW(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = registers[m];
		if ((registers[m] & 0x00008000) == 0) registers[n] &= 0x0000FFFF;
		else registers[n] |= 0xFFFF0000;


		cycles--;
		PC += 2;
	}

	private final void EXTUB(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = (registers[m] & 0x000000FF);

		cycles--;
		PC += 2;
	}

	private final void EXTUW(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = registers[m];
		registers[n] &= 0x0000FFFF;

		cycles--;
		PC += 2;
	}

	private final void MACL(int code) {
		int RnL, RnH, RmL, RmH, Res0, Res1, Res2;
		int temp0, temp1, temp2, temp3;
		int tempm, tempn, fnLmL;

		int m = RM(code);
		int n = RN(code);

		tempn = memory.read32i(registers[n]);
		registers[n] += 4;
		tempm = memory.read32i(registers[m]);
		registers[m] += 4;

		if ((tempn ^ tempm) < 0)
			fnLmL = -1;
		else
			fnLmL = 0;

		if (tempn < 0)
			tempn = 0 - tempn;
		if (tempm < 0)
			tempm = 0 - tempm;

		temp1 = (int) tempn;
		temp2 = (int) tempm;

		RnL = (temp1 >> 0) & 0x0000FFFF;
		RnH = (temp1 >> 16) & 0x0000FFFF;
		RmL = (temp2 >> 0) & 0x0000FFFF;
		RmH = (temp2 >> 16) & 0x0000FFFF;

		temp0 = RmL * RnL;
		temp1 = RmH * RnL;
		temp2 = RmL * RnH;
		temp3 = RmH * RnH;

		Res2 = 0;
		Res1 = temp1 + temp2;

		if (Res1 < temp1)
			Res2 += 0x00010000;

		temp1 = (Res1 << 16) & 0xFFFF0000;
		Res0 = temp0 + temp1;

		if (Res0 < temp0)
			Res2++;

		Res2 = Res2 + ((Res1 >>> 16) & 0x0000FFFF) + temp3;

		if (fnLmL < 0) {
			Res2 = ~Res2;
			if (Res0 == 0)
				Res2++;
			else
				Res0 = (~Res0) + 1;
		}

		if ((SR & flagS) == flagS) {
			Res0 = MACL + Res0;
			if (MACL > Res0)
				Res2++;

			if ((MACH & 0x00008000) != 0) ;
			else Res2 += MACH | 0xFFFF0000;

			Res2 += (MACL & 0x0000FFFF);

			if ((Res2 < 0) && (Res2 < 0xFFFF8000)) {
				Res2 = 0x00008000;
				Res0 = 0x00000000;
			}

			if ((Res2 > 0) && (Res2 > 0x00007FFF)) {
				Res2 = 0x00007FFF;
				Res0 = 0xFFFFFFFF;
			}
			;

			MACH = Res2;
			MACL = Res0;
		} else {
			Res0 = MACL + Res0;

			if (MACL > Res0)
				Res2++;

			Res2 += MACH;

			MACH = Res2;
			MACL = Res0;
		}
		cycles -= 2;
		PC += 2;
	}

	private final void MACW(int code) {
		int tempm, tempn, dest, src, ans;
		int templ;

		int m = RM(code);
		int n = RN(code);

		tempn = memory.read16i(registers[n]);
		registers[n] += 2;
		tempm = memory.read16i(registers[m]);
		registers[m] += 2;

		templ = MACL;
		tempm = ((int) (short) tempn * (int) (short) tempm);

		if (MACL >= 0)
			dest = 0;
		else
			dest = 1;

		if (tempm >= 0) {
			src = 0;
			tempn = 0;
		} else {
			src = 1;
			tempn = 0xFFFFFFFF;
		}

		src += dest;

		MACL += tempm;

		if (MACL >= 0)
			ans = 0;
		else
			ans = 1;

		ans += dest;

		if ((SR & flagS) == 0) {
			if (ans == 1) {
				if (src == 0) MACL = 0x7FFFFFFF;
				if (src == 2) MACL = 0x80000000;
			}
		} else {
			MACH += tempn;
			if (templ > MACL)
				MACH += 1;
		}
		cycles -= 2;
		PC += 2;
	}

	private final void MULL(int code) {
		int m = RM(code);
		int n = RN(code);

		MACL = registers[n] * registers[m];

		cycles -= 2;
		PC += 2;
	}

	private final void MULSW(int code) {
		int m = RM(code);
		int n = RN(code);


		MACL = (int) (short) registers[n] * (int) (short) registers[m];

		//System.out.println("MULLSW " + Integer.toHexString(MACL) + "R[n]=" + registers[n] + " R[m]=" + registers[m] );

		cycles -= 2;
		PC += 2;
	}

	private final void MULSU(int code) {
		int m = RM(code);
		int n = RN(code);

		MACL = (((int) registers[n] & 0xFFFF) * ((int) registers[m] & 0xFFFF));

		cycles -= 2;
		PC += 2;
	}

	private final void NEG(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = 0 - registers[m];

		cycles--;
		PC += 2;
	}

	private final void NEGC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp = 0 - registers[m];
		registers[n] = tmp - (SR & flagT);
		if (0 < tmp)
			SR |= flagT;
		else SR &= (~flagT);
		if (tmp < registers[n])
			SR |= flagT;

		cycles--;
		PC += 2;
	}

	private final void SUB(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] -= registers[m];

		cycles--;
		PC += 2;
	}

	private final void SUBC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0 = registers[n];
		int tmp1 = registers[n] - registers[m];
		registers[n] = tmp1 - (SR & flagT);
		if (tmp0 < tmp1)
			SR |= flagT;
		else SR &= (~flagT);

		if (tmp1 < registers[n])
			SR |= flagT;

		cycles--;
		PC += 2;

	}

	private final void SUBV(int code) {
		int ans;
		int m = RM(code);
		int n = RN(code);

		int dest = (registers[n] >> 31) & 1;
		int src = (registers[m] >> 31) & 1;

		src += dest;
		registers[n] -= registers[m];

		ans = (registers[n] >> 31) & 1;
		ans += dest;

		if (src == 1)
			if (ans == 1)
				SR |= flagT;
			else SR &= (~flagT);
		else
			SR &= (~flagT);

		cycles--;
		PC += 2;
	}

	private final void AND(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] &= registers[m];

		cycles--;
		PC += 2;
	}

	private final void ANDI(int code) {
		int i = ((code >> 0) & 0xff);

		registers[0] &= i;

		cycles--;
		PC += 2;
	}

	private final void ANDM(int code) {
		int i = (byte) ((code >> 0) & 0xff);

		int value = (byte) memory.read8i(GBR + registers[0]);
		memory.write8i(GBR + registers[0], ((byte) (value & i)));

		cycles -= 4;
		PC += 2;
	}

	private final void NOT(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] = ~registers[m];


		cycles--;
		PC += 2;
	}

	private final void OR(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] |= registers[m];


		cycles--;
		PC += 2;
	}

	private final void ORI(int code) {
		int i = ((code >> 0) & 0xff);

		registers[0] |= i;

		cycles--;
		PC += 2;

	}

	private final void ORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(GBR + registers[0]);
		memory.write8i(GBR + registers[0], ((byte) (value | i)));

		cycles -= 4;
	}

	private final void TAS(int code) {
		int n = RN(code);

		byte value = (byte) memory.read8i(registers[n]);
		if (value == 0)
			SR |= 0x1;
		else SR &= ~0x1;
		memory.write8i(registers[n], ((byte) (value | 0x80)));

		cycles -= 5;

		PC += 2;
	}

	private final void TST(int code) {
		int m = RM(code);
		int n = RN(code);

		if ((registers[n] & registers[m]) == 0)
			SR |= flagT;
		else SR &= (~flagT);


		cycles--;
		PC += 2;
	}

	private final void TSTI(int code) {
		int i = ((code >> 0) & 0xff);

		if ((registers[0] & i) != 0)
			SR |= flagT;
		else SR &= (~flagT);

		cycles--;
		PC += 2;
	}

	private final void TSTM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(GBR + registers[0]);
		if ((value & i) == 0)
			SR |= flagT;
		else SR &= (~flagT);
		cycles -= 3;
		PC += 2;
	}

	private final void XOR(int code) {
		int m = RM(code);
		int n = RN(code);

		registers[n] ^= registers[m];

		cycles--;
		PC += 2;
	}

	private final void XORI(int code) {
		int i = ((code >> 0) & 0xff);

		registers[0] ^= i;

		cycles--;
		PC += 2;
	}

	private final void XORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(GBR + registers[0]);
		memory.write8i(GBR + registers[0], ((byte) (value ^ i)));

		cycles -= 4;
		PC += 2;
	}

	private final void ROTR(int code) {
		int n = RN(code);

		if ((registers[n] & flagT) != 0) {
			SR |= flagT;
		} else SR &= ~flagT;

		registers[n] >>>= 1;

		if ((SR & flagT) != 0)
			registers[n] |= 0x80000000;
		else
			registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("rotr: despues %x\r", registers[n]));

		cycles--;
		PC += 2;
	}

	private final void ROTCR(int code) {
		int n = RN(code);
		int temp = 0;

		if ((registers[n] & 0x00000001) == 0) temp = 0;
		else temp = 1;
		registers[n] >>= 1;
		if ((SR & flagT) != 0)
			registers[n] |= 0x80000000;
		else
			registers[n] &= 0x7FFFFFFF;
		if (temp == 1) {
			SR |= flagT;
		} else SR &= ~flagT;


		//Logger.log(Logger.CPU,String.format("rotcr89: r[%d]=%x\r", n, registers[n]));

		cycles--;
		PC += 2;
	}

	private final void ROTL(int code) {
		int n = RN(code);

		if ((registers[n] & 0x80000000) != 0)
			SR |= flagT;
		else
			SR &= ~flagT;

		registers[n] <<= 1;

		if ((SR & flagT) != 0) {
			registers[n] |= 0x1;
		} else {
			registers[n] &= 0xFFFFFFFE;
		}

		//	Logger.log(Logger.CPU,String.format("rotl: despues %x\r", registers[n]));

		cycles--;
		PC += 2;
	}

	private final void ROTCL(int code) {
		int n = RN(code);
		int temp = 0;

		if ((registers[n] & 0x80000000) == 0) temp = 0;
		else temp = 1;

		registers[n] <<= 1;
		if ((SR & flagT) != 0) registers[n] |= 0x00000001;
		else registers[n] &= 0xFFFFFFFE;

		if (temp == 1)
			SR |= flagT;
		else
			SR &= ~flagT;


		// Logger.log(Logger.CPU,String.format("rotcl: r[%d]=%x\r", n, registers[n]));
		cycles--;
		PC += 2;
	}

	private final void SHAR(int code) {
		int n = RN(code);
		int temp = 0;
		if ((registers[n] & 0x00000001) == 0) SR &= (~flagT);
		else SR |= flagT;
		if ((registers[n] & 0x80000000) == 0) temp = 0;
		else temp = 1;
		registers[n] >>= 1;
		if (temp == 1) registers[n] |= 0x80000000;
		else registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("shar: despues %x\r", registers[n]));
		cycles--;
		PC += 2;

	}

	private final void SHAL(int code) {
		int n = RN(code);

		if ((registers[n] & 0x80000000) == 0)
			SR &= (~flagT);
		else SR |= flagT;
		registers[n] <<= 1;

		cycles--;
		PC += 2;

	}

	private final void SHLL(int code) {
		int n = RN(code);

		//	Logger.log(Logger.CPU,String.format("shll: antes %x\r", registers[n]));
		SR = (SR & ~flagT) | ((registers[n] >>> 31) & 1);
		registers[n] <<= 1;

		//Logger.log(Logger.CPU,String.format("shll: despues %x\r", registers[n]));
		cycles--;
		PC += 2;

	}

	private final void SHLR(int code) {
		int n = RN(code);

		SR = (SR & ~flagT) | (registers[n] & 1);
		registers[n] >>>= 1;
		registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("shlr: r[%d]=%x\r\n", n, registers[n]));

		cycles--;
		PC += 2;

	}

	private final void SHLL2(int code) {
		int n = RN(code);

		registers[n] <<= 2;

		cycles--;
		PC += 2;

	}

	private final void SHLR2(int code) {
		int n = RN(code);

		registers[n] >>>= 2;
		registers[n] &= 0x3FFFFFFF;

//		System.out.println(String.format("shlr2: r[%d]=%x\r\n", n, registers[n]));

		cycles--;
		PC += 2;

	}

	private final void SHLL8(int code) {
		int n = RN(code);

		registers[n] <<= 8;

		cycles--;
		PC += 2;

	}

	private final void SHLR8(int code) {
		int n = RN(code);

		registers[n] >>>= 8;

		cycles--;
		PC += 2;

	}

	private final void SHLL16(int code) {
		int n = RN(code);

		registers[n] <<= 16;

		cycles--;
		PC += 2;

	}

	private final void SHLR16(int code) {
		int n = RN(code);

		registers[n] >>>= 16;

		registers[n] &= 0x0000FFFF;

		cycles--;
		PC += 2;

	}

	private final void BF(int code) {
		if ((SR & flagT) == 0) {
			int d = (code & 0xff);

			if ((code & 0x80) == 0)
				d = (0x000000FF & code);
			else d = (0xFFFFFF00 | code);

			PC += (d << 1) + 4;

			cycles--;
		} else {
			cycles--;
			PC += 2;
		}
	}

	private final void BFS(int code) {
		if ((SR & flagT) == 0) {
			int d = 0;
			if ((code & 0x80) == 0)
				d = (0x000000FF & code);
			else d = (0xFFFFFF00 | code);

			int pc = PC + (d << 1) + 4;

			decode(memory.read16i(PC + 2));

			PC = pc;

			cycles--;
		} else {
			cycles--;
			PC += 2;
		}
	}

	private final void BT(int code) {
		if ((SR & flagT) != 0) {
			int d = 0;

			if ((code & 0x80) == 0)
				d = (0x000000FF & code);
			else d = (0xFFFFFF00 | code);

			PC = PC + (d << 1) + 4;

			cycles--;
		} else {
			cycles--;
			PC += 2;
		}
	}

	private final void BTS(int code) {
		if ((SR & flagT) != 0) {
			int d = 0;

			if ((code & 0x80) == 0)
				d = (0x000000FF & code);
			else d = (0xFFFFFF00 | code);

			int pc = PC + (d << 1) + 4;

			decode(memory.read16i(PC + 2));

			PC = pc;
			cycles--;
		} else {
			cycles--;
			PC += 2;
		}
	}

	private final void BRA(int code) {

		int disp;

		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);

		int pc = PC + 4 + (disp << 1);

		decode(memory.read16i(PC + 2));

		PC = pc;

		cycles -= 2;
	}

	private final void BSR(int code) {
		int disp = 0;
		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);


		PR = PC + 4;

		int pc = PC + (disp << 1) + 4;

		decode(memory.read16i(PC + 2));

		PC = pc;

		cycles -= 2;
	}

	private final void BRAF(int code) {
		int n = RN(code);

		int pc = PC + registers[n] + 4;

		decode(memory.read16i(PC + 2));

		PC = pc;
		cycles -= 2;
	}

	private final void BSRF(int code) {
		int n = RN(code);

		PR = PC + 4;

		int pc = PC + registers[n] + 4;

		decode(memory.read16i(PC + 2));

		PC = pc;

		cycles -= 2;
	}

	private final void JMP(int code) {
		int n = RN(code);

		int target = registers[n];

		decode(memory.read16i(PC + 2));

		PC = target;

		cycles -= 2;
	}

	private final void JSR(int code) {
		int n = RN(code);

		PR = PC + 4;

		int target = registers[n];

		decode(memory.read16i(PC + 2));

		PC = target;

		cycles -= 2;
	}

	private final void RTS(int code) {
		int pc = PR;
		decode(memory.read16i(PC + 2));
		PC = pc;
		cycles -= 2;
	}

	//TODO check
	private final void RTE(int code) {
		int rb = (SR & flagsRB);
		SR = SSR & 0x700083f3;

		if ((SR & flagsRB) != rb) {
			switch_gpr_banks();
		}

		decode(memory.read16i(PC + 2));

//		PC = SPC;

		cycles -= 5;
	}

	private final void CLRMAC(int code) {
		MACL = MACH = 0;

		cycles--;
		PC += 2;

	}

	private final void CLRT(int code) {
		SR &= (~flagT);

		cycles--;
		PC += 2;
	}

	private final void LDCSR(int code) {
		int m = RN(code);

		int rb = (SR & flagsRB);

		SR = registers[m] & 0x700083f3;

		if (((SR & flagsRB) != rb) && ((SR & flagMD) != 0)) {
			switch_gpr_banks();
		}
		cycles -= 4;
		PC += 2;

	}

	private final void LDCGBR(int code) {
		int m = RN(code);

		GBR = registers[m];

		cycles -= 3;
		PC += 2;
	}

	private final void LDCVBR(int code) {
		int m = RN(code);

		VBR = registers[m];

		cycles -= 2;
		PC += 2;

	}

	private final void LDCSSR(int code) {
		int m = RN(code);

		SSR = registers[m];

		cycles -= 2;
		PC += 2;

	}

	private final void LDCMSR(int code) {
		int m = RN(code);

		int rb = (SR & flagsRB);

		SR = memory.read32i(registers[m]) & 0x700083f3;

		if (((SR & flagsRB) != rb) && ((SR & flagMD) != 0)) {
			switch_gpr_banks();
		}

		registers[m] += 4;

		cycles -= 4;
		PC += 2;
	}

	private final void LDCMGBR(int code) {
		int m = RN(code);

		GBR = memory.read32i(registers[m]);
		registers[m] += 4;

		cycles -= 3;
		PC += 2;
	}

	private final void LDCMVBR(int code) {
		int m = RN(code);

		VBR = memory.read32i(registers[m]);
		registers[m] += 4;

		cycles -= 2;
		PC += 2;

	}

	private final void LDCMSSR(int code) {
		int m = RN(code);

		SSR = memory.read32i(registers[m]);
		registers[m] += 4;

		cycles -= 2;
		PC += 2;

	}

	private final void LDCMRBANK(int code) {
		int b = ((code >> 4) & 0x07) + 16;
		int m = RN(code);

		registers[b] = memory.read32i(registers[m]);
		registers[m] += 4;

		cycles -= 2;
		PC += 2;

	}

	private final void LDSMACH(int code) {
		int m = RN(code);

		MACH = registers[m];

		cycles -= 2;
		PC += 2;

	}

	private final void LDSMACL(int code) {
		int m = RN(code);

		MACL = registers[m];

		cycles -= 2;
		PC += 2;

	}

	private final void LDSPR(int code) {
		int m = RN(code);

		PR = registers[m];

//		System.out.println("LDS " + Integer.toHexString(PR));

		PC += 2;

		cycles -= 2;

	}

	private final void LDSMMACH(int code) {
		int m = RN(code);

		MACH = memory.read32i(registers[m]);
		registers[m] += 4;


		cycles -= 2;
		PC += 2;
	}

	private final void LDSMMACL(int code) {
		int m = RN(code);

		MACL = memory.read32i(registers[m]);
		registers[m] += 4;

		cycles -= 2;
		PC += 2;

	}


	private final void LDSMPR(int code) {
		int m = RN(code);

		PR = memory.read32i(registers[m]);

		//	System.out.println("LSMPR Register[ " + m + "] to MACL " + Integer.toHexString(PR));

		registers[m] += 4;

		cycles -= 2;
		PC += 2;
	}

	private final void LDTLB(int code) {
		cycles--;
		PC += 2;
	}

	private final void NOP(int code) {
		cycles--;
		PC += 2;

	}

	private final void SETT(int code) {
		SR |= flagT;

		cycles--;
		PC += 2;

	}

	private final void SLEEP(int code) {
		cycles -= 4;
		PC += 2;
	}

	private final void STCSR(int code) {
		int n = RN(code);

		registers[n] = SR;

		cycles -= 2;
		PC += 2;
	}

	private final void STCGBR(int code) {
		int n = RN(code);

		registers[n] = GBR;
		PC += 2;
		cycles -= 2;

	}

	private final void STCVBR(int code) {
		int n = RN(code);

		registers[n] = VBR;

		cycles -= 2;
		PC += 2;
	}

	private final void STCSSR(int code) {
		int n = RN(code);

		registers[n] = VBR;

		cycles -= 2;
		PC += 2;
	}

	private final void STCRBANK(int code) {
		int b = ((code >> 4) & 0x07) + 16;
		int n = RN(code);

		registers[n] = registers[b];

		cycles -= 2;
		PC += 2;

	}

	private final void STCMSR(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], SR);

		cycles -= 2;
		PC += 2;

	}

	private final void STCMGBR(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], GBR);

		cycles -= 2;
		PC += 2;

	}

	private final void STCMVBR(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], VBR);

		cycles -= 2;
		PC += 2;

	}

	private final void STCMSSR(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], SSR);

		cycles -= 2;
		PC += 2;

	}

	private final void STCMRBANK(int code) {
		int b = ((code >> 4) & 0x07) + 16;
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], registers[b]);

		cycles -= 2;
		PC += 2;

	}

	private final void STSMACH(int code) {
		int n = RN(code);

		registers[n] = MACH;

		cycles--;
		PC += 2;

	}

	private final void STSMACL(int code) {
		int n = RN(code);

		registers[n] = MACL;


		cycles--;
		PC += 2;

	}

	private final void STSPR(int code) {
		int n = RN(code);


		registers[n] = PR;

		cycles -= 2;
		PC += 2;

	}

	private final void STSMMACH(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], MACH);

		cycles--;
		PC += 2;

	}

	private final void STSMMACL(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], MACL);

		cycles--;
		PC += 2;

	}

	private final void STSMPR(int code) {
		int n = RN(code);

		registers[n] -= 4;
		memory.write32i(registers[n], PR);

		cycles -= 2;
		PC += 2;
	}

	//TODO sh2
	private final void TRAPA(int code) {
		int imm;
		imm = (0x000000FF & code);

//		memory.regmapWritehandle32Inst(MMREG.TRA,imm<<2);
		SSR = SR;
//		SPC=PC+2;
//		SGR=registers[15];
		SR |= flagMD;
		SR |= flagBL;
		SR |= flagsRB;
//		memory.regmapWritehandle32Inst(MMREG.EXPEVT,0x00000160);
		PC = VBR + 0x00000100;
		cycles -= 7;
		PC += 2;
	}

	/*
	 * Because an instruction in a delay slot cannot alter the PC we can do this.
	 */
	public void run() {
		memory.setSh2Access(sh2Access);
		int opcode;
		for (; cycles >= 0; ) {
			opcode = memory.read16i(PC);
			printDebugMaybe(opcode);
			decode(opcode);
			//	Emu.interruptController.acceptInterrupts();
		}
		cycles_ran = burstCycles - cycles;
		cycles = burstCycles;
	}

	public void Step(int pc) {
		decode(memory.read16i(pc));
		PC += 2;
	}

	protected void printDebugMaybe(int instruction) {
		if (debugging) {
			Sh2Helper.printInst(this, instruction);
		}
	}


	private final void decode(int instruction) {
		switch ((instruction >>> 12) & 0xf) {
			case 0:
				switch ((instruction >>> 0) & 0xf) {
					case 2:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								STCSR(instruction);
								return;
							case 1:
								STCGBR(instruction);
								return;
							case 2:
								STCVBR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 3:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								BSRF(instruction);
								return;
							case 2:
								BRAF(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 4:
						MOVBS0(instruction);
						return;
					case 5:
						MOVWS0(instruction);
						return;
					case 6:
						MOVLS0(instruction);
						return;
					case 7:
						MULL(instruction);
						return;

					case 8:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								CLRT(instruction);
								return;
							case 1:
								SETT(instruction);
								return;
							case 2:
								CLRMAC(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 9:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								NOP(instruction);
								return;
							case 1:
								DIV0U(instruction);
								return;
							case 2:
								MOVT(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 10:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								STSMACH(instruction);
								return;
							case 1:
								STSMACL(instruction);
								return;
							case 2:
								STSPR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 11:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								RTS(instruction);
								return;
							case 1:
								SLEEP(instruction);
								return;
							case 2:
								RTE(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 12:
						MOVBL0(instruction);
						return;
					case 13:
						MOVWL0(instruction);
						return;
					case 14:
						MOVLL0(instruction);
						return;
					case 15:
						MACL(instruction);
						return;
					default:
						NOIMP(instruction);
						return;
				}

			case 1:
				MOVLS4(instruction);
				return;

			case 2:
				switch ((instruction >>> 0) & 0xf) {
					case 0:
						MOVBS(instruction);
						return;
					case 1:
						MOVWS(instruction);
						return;
					case 2:
						MOVLS(instruction);
						return;
					case 4:
						MOVBM(instruction);
						return;
					case 5:
						MOVWM(instruction);
						return;
					case 6:
						MOVLM(instruction);
						return;
					case 7:
						DIV0S(instruction);
						return;
					case 8:
						TST(instruction);
						return;
					case 9:
						AND(instruction);
						return;
					case 10:
						XOR(instruction);
						return;
					case 11:
						OR(instruction);
						return;
					case 12:
						CMPSTR(instruction);
						return;
					case 13:
						XTRCT(instruction);
						return;
					case 14:
						MULSU(instruction);
						return;
					case 15:
						MULSW(instruction);
						return;
					default:
						NOIMP(instruction);
						return;
				}

			case 3:
				switch ((instruction >>> 0) & 0xf) {
					case 0:
						CMPEQ(instruction);
						return;
					case 2:
						CMPHS(instruction);
						return;
					case 3:
						CMPGE(instruction);
						return;
					case 4:
						DIV1(instruction);
						return;
					case 5:
						DMULU(instruction);
						return;
					case 6:
						CMPHI(instruction);
						return;
					case 7:
						CMPGT(instruction);
						return;
					case 8:
						SUB(instruction);
						return;
					case 10:
						SUBC(instruction);
						return;
					case 11:
						SUBV(instruction);
						return;
					case 12:
						ADD(instruction);
						return;
					case 13:
						DMULS(instruction);
						return;
					case 14:
						ADDC(instruction);
						return;
					case 15:
						ADDV(instruction);
						return;
					default:
						NOIMP(instruction);
						return;
				}

			case 4:
				switch ((instruction >>> 0) & 0xf) {
					case 0:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								SHLL(instruction);
								return;
							case 1:
								DT(instruction);
								return;
							case 2:
								SHAL(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 1:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								SHLR(instruction);
								return;
							case 1:
								CMPPZ(instruction);
								return;
							case 2:
								SHAR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 2:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								STSMMACH(instruction);
								return;
							case 1:
								STSMMACL(instruction);
								return;
							case 2:
								STSMPR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 3:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								STCMSR(instruction);
								return;
							case 1:
								STCMGBR(instruction);
								return;
							case 2:
								STCMVBR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 4:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								ROTL(instruction);
								return;
							case 2:
								ROTCL(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 5:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								ROTR(instruction);
								return;
							case 1:
								CMPPL(instruction);
								return;
							case 2:
								ROTCR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 6:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								LDSMMACH(instruction);
								return;
							case 1:
								LDSMMACL(instruction);
								return;
							case 2:
								LDSMPR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 7:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								LDCMSR(instruction);
								return;
							case 1:
								LDCMGBR(instruction);
								return;
							case 2:
								LDCMVBR(instruction);
								return;
							case 3:
								LDCMSSR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 8:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								SHLL2(instruction);
								return;
							case 1:
								SHLL8(instruction);
								return;
							case 2:
								SHLL16(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 9:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								SHLR2(instruction);
								return;
							case 1:
								SHLR8(instruction);
								return;
							case 2:
								SHLR16(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 10:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								LDSMACH(instruction);
								return;
							case 1:
								LDSMACL(instruction);
								return;
							case 2:
								LDSPR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 11:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								JSR(instruction);
								return;
							case 1:
								TAS(instruction);
								return;
							case 2:
								JMP(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}
					case 14:
						switch ((instruction >>> 4) & 0xf) {
							case 0:
								LDCSR(instruction);
								return;
							case 1:
								LDCGBR(instruction);
								return;
							case 2:
								LDCVBR(instruction);
								return;
							default:
								NOIMP(instruction);
								return;
						}

					case 15:
						MACW(instruction);
						return;
					default:
						NOIMP(instruction);
						return;
				}

			case 5:
				MOVLL4(instruction);
				return;

			case 6:
				switch ((instruction >>> 0) & 0xf) {
					case 0:
						MOVBL(instruction);
						return;
					case 1:
						MOVWL(instruction);
						return;
					case 2:
						MOVLL(instruction);
						return;
					case 3:
						MOV(instruction);
						return;
					case 4:
						MOVBP(instruction);
						return;
					case 5:
						MOVWP(instruction);
						return;
					case 6:
						MOVLP(instruction);
						return;
					case 7:
						NOT(instruction);
						return;
					case 8:
						SWAPB(instruction);
						return;
					case 9:
						SWAPW(instruction);
						return;
					case 10:
						NEGC(instruction);
						return;
					case 11:
						NEG(instruction);
						return;
					case 12:
						EXTUB(instruction);
						return;
					case 13:
						EXTUW(instruction);
						return;
					case 14:
						EXTSB(instruction);
						return;
					case 15:
						EXTSW(instruction);
						return;
				}

			case 7:
				ADDI(instruction);
				return;

			case 8:
				switch ((instruction >>> 8) & 0xf) {
					case 0:
						MOVBS4(instruction);
						return;
					case 1:
						MOVWS4(instruction);
						return;
					case 4:
						MOVBL4(instruction);
						return;
					case 5:
						MOVWL4(instruction);
						return;
					case 8:
						CMPIM(instruction);
						return;
					case 9:
						BT(instruction);
						return;
					case 11:
						BF(instruction);
						return;
					case 13:
						BTS(instruction);
						return;
					case 15:
						BFS(instruction);
						return;
					default:
						NOIMP(instruction);
						return;
				}

			case 9:
				MOVWI(instruction);
				return;
			case 10:
				BRA(instruction);
				return;
			case 11:
				BSR(instruction);
				return;

			case 12:
				switch ((instruction >>> 8) & 0xf) {
					case 0:
						MOVBSG(instruction);
						return;
					case 1:
						MOVWSG(instruction);
						return;
					case 2:
						MOVLSG(instruction);
						return;
					case 3:
						TRAPA(instruction);
						return;
					case 4:
						MOVBLG(instruction);
						return;
					case 5:
						MOVWLG(instruction);
						return;
					case 6:
						MOVLLG(instruction);
						return;
					case 7:
						MOVA(instruction);
						return;
					case 8:
						TSTI(instruction);
						return;
					case 9:
						ANDI(instruction);
						return;
					case 10:
						XORI(instruction);
						return;
					case 11:
						ORI(instruction);
						return;
					case 12:
						TSTM(instruction);
						return;
					case 13:
						ANDM(instruction);
						return;
					case 14:
						XORM(instruction);
						return;
					case 15:
						ORM(instruction);
						return;
				}

			case 13:
				MOVLI(instruction);
				return;
			case 14:
				MOVI(instruction);
				return;
		}
		Sh2Helper.printState(this, instruction);
		throw new RuntimeException("Unknown inst: " + Integer.toHexString(instruction));
	}
}
