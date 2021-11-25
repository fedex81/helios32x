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

	public static final int flagT = 0x00000001;
	public static final int flagS = 0x00000002;
	public static final int flagIMASK = 0x000000f0;
	public static final int flagQ = 0x00000100;
	public static final int flagM = 0x00000200;

	public static final int SR_MASK = 0x3F3;

	public static int burstCycles = 1;

	private Sh2Context ctx;
	protected IMemory memory;

	public Sh2(IMemory memory) {
		this.memory = memory;
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
	private int getIMASK() {
		return (ctx.SR & flagIMASK) >>> 4;
	}

	public void reset(Sh2Context ctx) {
		memory.setSh2Access(ctx.sh2Access);
		ctx.VBR = 0;
		ctx.PC = memory.read32i(0);
		ctx.registers[15] = memory.read32i(4); //SP
		System.out.println(ctx.sh2Access + " SP: " + Integer.toHexString(ctx.registers[15]));
		ctx.cycles = burstCycles;
		System.out.println("reset");
	}

	private void acceptInterrupts(Sh2Context ctx) {
		int mask = getIMASK();
		int imask = S32XMMREG.interruptControl.getInterruptLevel(ctx.sh2Access);
		if (imask > mask) {
			processInterrupt(ctx, imask);
//			debugging = true;
		}
	}

	private void processInterrupt(Sh2Context ctx, int source_irq) {
		System.out.println(ctx.sh2Access + " Interrupt processed: " + source_irq);
		push(ctx.SR);
		push(ctx.PC);

		//SR 7-4
		ctx.SR &= 0xF0F;
		ctx.SR |= (source_irq << 4);

		int vectorNum = 64 + (source_irq >> 1);

		ctx.PC = memory.read32i(ctx.VBR + (vectorNum << 2));
		ctx.cycles -= 13;
	}

	//push to stack
	private void push(int data) {
		ctx.registers[15] -= 4;
		memory.write32i(ctx.registers[15], data);
		System.out.println(ctx.sh2Access + " PUSH SP: " + Integer.toHexString(ctx.registers[15])
				+ "," + Integer.toHexString(data));
	}

	//pop from stack
	private int pop() {
		int res = memory.read32i(ctx.registers[15]);
		ctx.registers[15] += 4;
		System.out.println(ctx.sh2Access + " POP SP: " + Integer.toHexString(ctx.registers[15])
				+ "," + Integer.toHexString(res));
		return res;
	}

	private void NOIMP(int code) {
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void UNKNOWN(int instruction) {
		Sh2Helper.printState(ctx, instruction);
		throw new RuntimeException("Unknown inst: " + Integer.toHexString(instruction));
	}

	private final void MOVI(int code) {
		int n = ((code >> 8) & 0x0f);
		//8 bit sign extend
		ctx.registers[n] = (byte) (code & 0xFF);
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		ctx.registers[n] = memory.read16i(ctx.PC + 4 + (d << 1));

		if ((ctx.registers[n] & 0x8000) == 0) ctx.registers[n] &= 0x0000FFFF;
		else ctx.registers[n] |= 0xFFFF0000;


		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		ctx.registers[n] = memory.read32i((ctx.PC & 0xfffffffc) + 4 + (d << 2));

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOV(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVBS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8i(ctx.registers[n], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16i(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVLS(int code) {
		int m = RM(code);
		int n = RN(code);

		//	System.out.println("MOVLS source " + Integer.toHexString(ctx.registers[n]) + "destination " + Integer.toHexString(ctx.registers[m]));

		memory.write32i(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVBL(int code) {
		int m = RM(code);
		int n = RN(code);
		byte c;

		c = (byte) (memory.read8i(ctx.registers[m]) & 0xFF);

		ctx.registers[n] = (int) c;

		//Logger.log(Logger.CPU, String.format("movb7: r[%d]=%x,%d r[%d]=%x,%d\r", m, ctx.registers[m], ctx.registers[m], n, ctx.registers[n], ctx.registers[n]));

		//System.out.println("MOVBL @" + Integer.toHexString(ctx.registers[m]) + " value read " + ctx.registers[n] + " ctx.PC " + Integer.toHexString(PC));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWL(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) (memory.read16i(ctx.registers[m]) & 0xFFFF);

		ctx.registers[n] = (int) w;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVLL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32i(ctx.registers[m]);


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVBM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 1;
		memory.write8i(ctx.registers[n], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 2;

		memory.write16i(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 4;

		memory.write32i(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBP(int code) {
		int m = RM(code);
		int n = RN(code);

		byte b = (byte) (memory.read8i(ctx.registers[m]) & 0xFF);
		ctx.registers[n] = (int) b;
		if (n != m) ctx.registers[m] += 1;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVWP(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) memory.read16i(ctx.registers[m]);
		ctx.registers[n] = (int) w;
		if (n != m) ctx.registers[m] += 2;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLP(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32i(ctx.registers[m]);
		if (n != m) ctx.registers[m] += 4;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write8i(ctx.registers[n] + (d << 0), (byte) ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVWS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write16i(ctx.registers[n] + (d << 1), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLS4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		memory.write32i(ctx.registers[n] + (d << 2), ctx.registers[m]);

		//System.out.println("MOVLS4 " + Integer.toHexString(ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		byte b = (byte) (memory.read8i(ctx.registers[m] + d) & 0xFF);
		ctx.registers[0] = (int) b;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		short w = (short) memory.read16i(ctx.registers[m] + (d << 1));
		ctx.registers[0] = (int) w;


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVLL4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32i(ctx.registers[m] + (d << 2));


		//System.out.println("MOVLL4 " + Integer.toHexString(ctx.registers[n]) + " @" + Integer.toHexString(ctx.registers[m] + (d *4)) );
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVBS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8i(ctx.registers[n] + ctx.registers[0], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVWS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16i(ctx.registers[n] + ctx.registers[0], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVLS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write32i(ctx.registers[n] + ctx.registers[0], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBL0(int code) {
		int m = RM(code);
		int n = RN(code);

		byte b = (byte) memory.read8i(ctx.registers[m] + ctx.registers[0]);
		ctx.registers[n] = (int) b;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVWL0(int code) {
		int m = RM(code);
		int n = RN(code);

		short w = (short) memory.read16i(ctx.registers[m] + ctx.registers[0]);
		ctx.registers[n] = (int) w;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLL0(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32i(ctx.registers[m] + ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBSG(int code) {
		int d = (code & 0xff);

		memory.write8i(ctx.GBR + d, (byte) ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVWSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write16i(ctx.GBR + (d << 1), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write32i(ctx.GBR + (d << 2), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVBLG(int code) {
		int d = ((code >> 0) & 0xff);

		byte b = (byte) memory.read8i(ctx.GBR + (d << 0));
		ctx.registers[0] = (int) b;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVWLG(int code) {
		int d = ((code >> 0) & 0xff);

		short w = (short) memory.read16i(ctx.GBR + (d << 1));
		ctx.registers[0] = (int) w;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void MOVLLG(int code) {
		int d = ((code >> 0) & 0xff);

		ctx.registers[0] = memory.read32i(ctx.GBR + (d << 2));


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVA(int code) {
		int d = (code & 0x000000ff);

		ctx.registers[0] = ((ctx.PC & 0xfffffffc) + 4 + (d << 2));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MOVT(int code) {
		int n = RN(code);

		ctx.registers[n] = (ctx.SR & flagT);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SWAPB(int code) {
		int m = RM(code);
		int n = RN(code);

		int temp0, temp1;
		temp0 = ctx.registers[m] & 0xFFFF0000;
		temp1 = (ctx.registers[m] & 0x000000FF) << 8;
		ctx.registers[n] = (ctx.registers[m] & 0x0000FF00) >> 8;
		ctx.registers[n] = ctx.registers[n] | temp1 | temp0;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void SWAPW(int code) {
		int m = RM(code);
		int n = RN(code);
		int temp = 0;
		temp = (ctx.registers[m] >>> 16) & 0x0000FFFF;
		ctx.registers[n] = ctx.registers[m] << 16;
		ctx.registers[n] |= temp;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void XTRCT(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ((ctx.registers[n] & 0xffff0000) >>> 16) |
				((ctx.registers[m] & 0x0000ffff) << 16);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void ADD(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] += ctx.registers[m]; 
		

		/*Logger.log(Logger.CPU,String.format("add39: r[%d]=%x,%d r[%d]=%x,%d\r\n",
	            n, ctx.registers[n], ctx.registers[n],
	            m, ctx.registers[m], ctx.registers[m]));*/
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ADDI(int code) {
		int n = RN(code);
		byte b = (byte) (code & 0xff);
		//System.out.println("ADDI before " + Integer.toHexString(ctx.registers[n]) + "value to be added " + Integer.toHexString(b));

		ctx.registers[n] += (int) b;

		/*Logger.log(Logger.CPU,String.format("add40: r[%d]=%x,%d\r\n",
	            n, ctx.registers[n], ctx.registers[n])); */

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ADDC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0 = ctx.registers[n];
		int tmp1 = ctx.registers[n] + ctx.registers[m];
		ctx.registers[n] = tmp1 + (ctx.SR & flagT);
		if (tmp0 > tmp1)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		if (tmp1 > ctx.registers[n]) ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;
		
		/*Logger.log(Logger.CPU,String.format("addc41: r[%d]=%x,%d r[%d]=%x,%d tmp0=%x,%d, tmp1=%x,%d\r\n",
	            m, ctx.registers[m], ctx.registers[m],
	            n, ctx.registers[n], ctx.registers[n],
	            tmp0, tmp0, tmp1, tmp1)); */
	}

	private final void ADDV(int code) {
		int ans;
		int m = RM(code);
		int n = RN(code);
		int dest = 0, src = 0;

		if (ctx.registers[n] >= 0) dest = 1;
		else dest = 0;
		if (ctx.registers[m] >= 0) src = 1;
		else src = 0;

		src += dest;
		ctx.registers[n] += ctx.registers[m];

		if (ctx.registers[n] >= 0)
			ans = 0;
		else ans = 1;

		ans += dest;

		if ((src == 0) || (src == 2)) {
			if (ans == 1)
				ctx.SR |= ans;
			else ctx.SR &= (~flagT);
		} else
			ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void CMPIM(int code) {
		int i = (byte) (code & 0xFF);
		if (ctx.registers[0] == i)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		

		/*Logger.log(Logger.CPU,String.format("cmp/eq: r[0]=%x,%d,'%c' == #%x,%d,'%c' ?\r\n",
				ctx.registers[0],ctx.registers[0], ctx.registers[0],
	        i, i,i));*/


		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void CMPEQ(int code) {
		int m = RM(code);
		int n = RN(code);

		if (ctx.registers[n] == ctx.registers[m])
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);


		/*Logger.log(Logger.CPU,String.format("cmp/eq: r[%d]=%x,%d,'%c' == r[%d]=%x,%d,'%c' \r",
	        n,  ctx.registers[n],  ctx.registers[n],  ctx.registers[n],
	        m,  ctx.registers[m],  ctx.registers[m],  ctx.registers[m]));
*/

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void CMPHS(int code) {
		int m = RM(code);
		int n = RN(code);

		if (((long) (ctx.registers[n] & 0xFFFFFFFFL)) >= ((long) (ctx.registers[m] & 0xFFFFFFFFL)))
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hs: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void CMPGE(int code) {
		int m = RM(code);
		int n = RN(code);

		if (ctx.registers[n] >= ctx.registers[m])
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/ge: r[%d]=%x >= r[%d]=%x ?\r\n", n, ctx.registers[n], m, ctx.registers[m]));

		//Logger.log(Logger.CPU,"CMPGE " + ctx.registers[n] + " >= " + ctx.registers[m]);
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void CMPHI(int code) {
		int m = RM(code);
		int n = RN(code);

		// AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH
		if (((long) (ctx.registers[n] & 0xFFFFFFFFL)) > ((long) (ctx.registers[m] & 0xFFFFFFFFL)))
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hi: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void CMPGT(int code) {
		int m = RM(code);
		int n = RN(code);

		if (ctx.registers[n] > ctx.registers[m])
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/gt: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void CMPPZ(int code) {
		int n = RN(code);

		if (ctx.registers[n] >= 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x >= 0 ?\r", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void CMPPL(int code) {
		int n = RN(code);

		if (ctx.registers[n] > 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x > 0 ?\r", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;

		//Logger.log(Logger.CPU,"CMPPL " + ctx.registers[n]);
	}

	private final void CMPSTR(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp = ctx.registers[n] ^ ctx.registers[m];

		int HH = (tmp >>> 24) & 0xff;
		int HL = (tmp >>> 16) & 0xff;
		int LH = (tmp >>> 8) & 0xff;
		int LL = tmp & 0xff;
		if ((HH & HL & LH & LL) != 0)
			ctx.SR &= ~flagT;
		else
			ctx.SR |= flagT;

		//	Logger.log(Logger.CPU,String.format("cmp/str: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void DIV1(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0, tmp2;
		int tmp1;
		int old_q;

		old_q = ctx.SR & flagQ;
		if ((0x80000000 & ctx.registers[n]) != 0)
			ctx.SR |= flagQ;
		else
			ctx.SR &= ~flagQ;

		tmp2 = ctx.registers[m];
		ctx.registers[n] <<= 1;

		ctx.registers[n] |= flagT;

		if (old_q == 0) {
			if ((ctx.SR & flagM) == 0) {
				tmp0 = ctx.registers[n];
				ctx.registers[n] -= tmp2;
				tmp1 = (ctx.registers[n] > tmp0 ? 1 : 0);
				if ((ctx.SR & flagQ) == 0)
					if (tmp1 == 1)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				else if (tmp1 == 0)
					ctx.SR |= flagQ;
				else
					ctx.SR &= ~flagQ;
			} else {
				tmp0 = ctx.registers[n];
				ctx.registers[n] += tmp2;
				tmp1 = (ctx.registers[n] < tmp0 ? 1 : 0);
				if ((ctx.SR & flagQ) == 0) {
					if (tmp1 == 0)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				} else {
					if (tmp1 == 1)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				}
			}
		} else {
			if ((ctx.SR & flagM) == 0) {
				tmp0 = ctx.registers[n];
				ctx.registers[n] += tmp2;
				tmp1 = (ctx.registers[n] < tmp0 ? 1 : 0);
				if ((ctx.SR & flagQ) == 0) {
					if (tmp1 == 1)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				} else {
					if (tmp1 == 0)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				}
			} else {
				tmp0 = ctx.registers[n];
				ctx.registers[n] -= tmp2;
				tmp1 = (ctx.registers[n] > tmp0 ? 1 : 0);
				if ((ctx.SR & flagQ) == 0) {
					if (tmp1 == 0)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				} else {
					if (tmp1 == 1)
						ctx.SR |= flagQ;
					else
						ctx.SR &= ~flagQ;
				}
			}
		}

		tmp0 = (ctx.SR & (flagQ | flagM));
		if (((tmp0) == 0) || (tmp0 == 0x300)) /* if Q == M set T else clear T */
			ctx.SR |= flagT;
		else
			ctx.SR &= ~flagT;

		//Logger.log(Logger.CPU,String.format("div1s: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void DIV0S(int code) {
		int m = RM(code);
		int n = RN(code);

		if ((ctx.registers[n] & 0x80000000) == 0)
			ctx.SR &= ~flagQ;
		else
			ctx.SR |= flagQ;
		if ((ctx.registers[m] & 0x80000000) == 0)
			ctx.SR &= ~flagM;
		else
			ctx.SR |= flagM;
		if (((ctx.registers[m] ^ ctx.registers[n]) & 0x80000000) != 0)
			ctx.SR |= flagT;
		else
			ctx.SR &= ~flagT;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void DIV0U(int code) {
		ctx.SR &= (~flagQ);
		ctx.SR &= (~flagM);
		ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void DMULS(int code) {
		int m = RM(code);
		int n = RN(code);

		long mult = (long) ctx.registers[n] * (long) ctx.registers[m];

//		System.out.println("DMULS " + mult);

		ctx.MACL = (int) (mult & 0xffffffff);
		ctx.MACH = (int) ((mult >>> 32) & 0xffffffff);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void DMULU(int code) {
		int m = RM(code);
		int n = RN(code);

		// this should be unsigned but oh well :/
		long mult = (long) (ctx.registers[n] & 0xffffffffL) * (long) (ctx.registers[m] & 0xffffffffL);

		System.out.println("DMULU" + Long.toHexString(mult));

		ctx.MACL = (int) (mult & 0xffffffff);
		ctx.MACH = (int) ((mult >>> 32) & 0xffffffff);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void DT(int code) {
		int n = RN(code);
		//Logger.log(Logger.CPU,"DT: R[" + n + "] = " + Integer.toHexString(ctx.registers[n]));
		ctx.registers[n]--;
		if (ctx.registers[n] == 0) {
			ctx.SR |= flagT;
		} else {
			ctx.SR &= (~flagT);
		}
		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void EXTSB(int code) {
		int m = RM(code);
		int n = RN(code);


		ctx.registers[n] = ctx.registers[m];
		if ((ctx.registers[m] & 0x00000080) == 0) ctx.registers[n] &= 0x000000FF;
		else ctx.registers[n] |= 0xFFFFFF00;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void EXTSW(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m];
		if ((ctx.registers[m] & 0x00008000) == 0) ctx.registers[n] &= 0x0000FFFF;
		else ctx.registers[n] |= 0xFFFF0000;


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void EXTUB(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (ctx.registers[m] & 0x000000FF);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void EXTUW(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m];
		ctx.registers[n] &= 0x0000FFFF;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void MACL(int code) {
		int RnL, RnH, RmL, RmH, Res0, Res1, Res2;
		int temp0, temp1, temp2, temp3;
		int tempm, tempn, fnLmL;

		int m = RM(code);
		int n = RN(code);

		tempn = memory.read32i(ctx.registers[n]);
		ctx.registers[n] += 4;
		tempm = memory.read32i(ctx.registers[m]);
		ctx.registers[m] += 4;

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

		if ((ctx.SR & flagS) == flagS) {
			Res0 = ctx.MACL + Res0;
			if (ctx.MACL > Res0)
				Res2++;

			if ((ctx.MACH & 0x00008000) != 0) ;
			else Res2 += ctx.MACH | 0xFFFF0000;

			Res2 += (ctx.MACL & 0x0000FFFF);

			if ((Res2 < 0) && (Res2 < 0xFFFF8000)) {
				Res2 = 0x00008000;
				Res0 = 0x00000000;
			}

			if ((Res2 > 0) && (Res2 > 0x00007FFF)) {
				Res2 = 0x00007FFF;
				Res0 = 0xFFFFFFFF;
			}
			;

			ctx.MACH = Res2;
			ctx.MACL = Res0;
		} else {
			Res0 = ctx.MACL + Res0;

			if (ctx.MACL > Res0)
				Res2++;

			Res2 += ctx.MACH;

			ctx.MACH = Res2;
			ctx.MACL = Res0;
		}
		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void MACW(int code) {
		int tempm, tempn, dest, src, ans;
		int templ;

		int m = RM(code);
		int n = RN(code);

		tempn = memory.read16i(ctx.registers[n]);
		ctx.registers[n] += 2;
		tempm = memory.read16i(ctx.registers[m]);
		ctx.registers[m] += 2;

		templ = ctx.MACL;
		tempm = ((int) (short) tempn * (int) (short) tempm);

		if (ctx.MACL >= 0)
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

		ctx.MACL += tempm;

		if (ctx.MACL >= 0)
			ans = 0;
		else
			ans = 1;

		ans += dest;

		if ((ctx.SR & flagS) == 0) {
			if (ans == 1) {
				if (src == 0) ctx.MACL = 0x7FFFFFFF;
				if (src == 2) ctx.MACL = 0x80000000;
			}
		} else {
			ctx.MACH += tempn;
			if (templ > ctx.MACL)
				ctx.MACH += 1;
		}
		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void MULL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.MACL = ctx.registers[n] * ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void MULSW(int code) {
		int m = RM(code);
		int n = RN(code);


		ctx.MACL = (int) (short) ctx.registers[n] * (int) (short) ctx.registers[m];

		//System.out.println("MULLSW " + Integer.toHexString(MACL) + "R[n]=" + ctx.registers[n] + " R[m]=" + ctx.registers[m] );

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void MULSU(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.MACL = (((int) ctx.registers[n] & 0xFFFF) * ((int) ctx.registers[m] & 0xFFFF));

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void NEG(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = 0 - ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void NEGC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp = 0 - ctx.registers[m];
		ctx.registers[n] = tmp - (ctx.SR & flagT);
		if (0 < tmp)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		if (tmp < ctx.registers[n])
			ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void SUB(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void SUBC(int code) {
		int m = RM(code);
		int n = RN(code);

		int tmp0 = ctx.registers[n];
		int tmp1 = ctx.registers[n] - ctx.registers[m];
		ctx.registers[n] = tmp1 - (ctx.SR & flagT);
		if (tmp0 < tmp1)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		if (tmp1 < ctx.registers[n])
			ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SUBV(int code) {
		int ans;
		int m = RM(code);
		int n = RN(code);

		int dest = (ctx.registers[n] >> 31) & 1;
		int src = (ctx.registers[m] >> 31) & 1;

		src += dest;
		ctx.registers[n] -= ctx.registers[m];

		ans = (ctx.registers[n] >> 31) & 1;
		ans += dest;

		if (src == 1)
			if (ans == 1)
				ctx.SR |= flagT;
			else ctx.SR &= (~flagT);
		else
			ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void AND(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] &= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ANDI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] &= i;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ANDM(int code) {
		int i = (byte) ((code >> 0) & 0xff);

		int value = (byte) memory.read8i(ctx.GBR + ctx.registers[0]);
		memory.write8i(ctx.GBR + ctx.registers[0], ((byte) (value & i)));

		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	private final void NOT(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ~ctx.registers[m];


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void OR(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] |= ctx.registers[m];


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ORI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] |= i;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void ORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(ctx.GBR + ctx.registers[0]);
		memory.write8i(ctx.GBR + ctx.registers[0], ((byte) (value | i)));

		ctx.cycles -= 4;
	}

	private final void TAS(int code) {
		int n = RN(code);

		byte value = (byte) memory.read8i(ctx.registers[n]);
		if (value == 0)
			ctx.SR |= 0x1;
		else ctx.SR &= ~0x1;
		memory.write8i(ctx.registers[n], ((byte) (value | 0x80)));

		ctx.cycles -= 5;

		ctx.PC += 2;
	}

	private final void TST(int code) {
		int m = RM(code);
		int n = RN(code);

		if ((ctx.registers[n] & ctx.registers[m]) == 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);


		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void TSTI(int code) {
		int i = ((code >> 0) & 0xff);

		if ((ctx.registers[0] & i) != 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void TSTM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(ctx.GBR + ctx.registers[0]);
		if ((value & i) == 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	private final void XOR(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] ^= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void XORI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] ^= i;

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void XORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8i(ctx.GBR + ctx.registers[0]);
		memory.write8i(ctx.GBR + ctx.registers[0], ((byte) (value ^ i)));

		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	private final void ROTR(int code) {
		int n = RN(code);

		if ((ctx.registers[n] & flagT) != 0) {
			ctx.SR |= flagT;
		} else ctx.SR &= ~flagT;

		ctx.registers[n] >>>= 1;

		if ((ctx.SR & flagT) != 0)
			ctx.registers[n] |= 0x80000000;
		else
			ctx.registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("rotr: despues %x\r", ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ROTCR(int code) {
		int n = RN(code);
		int temp = 0;

		if ((ctx.registers[n] & 0x00000001) == 0) temp = 0;
		else temp = 1;
		ctx.registers[n] >>= 1;
		if ((ctx.SR & flagT) != 0)
			ctx.registers[n] |= 0x80000000;
		else
			ctx.registers[n] &= 0x7FFFFFFF;
		if (temp == 1) {
			ctx.SR |= flagT;
		} else ctx.SR &= ~flagT;


		//Logger.log(Logger.CPU,String.format("rotcr89: r[%d]=%x\r", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ROTL(int code) {
		int n = RN(code);

		if ((ctx.registers[n] & 0x80000000) != 0)
			ctx.SR |= flagT;
		else
			ctx.SR &= ~flagT;

		ctx.registers[n] <<= 1;

		if ((ctx.SR & flagT) != 0) {
			ctx.registers[n] |= 0x1;
		} else {
			ctx.registers[n] &= 0xFFFFFFFE;
		}

		//	Logger.log(Logger.CPU,String.format("rotl: despues %x\r", ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void ROTCL(int code) {
		int n = RN(code);
		int temp = 0;

		if ((ctx.registers[n] & 0x80000000) == 0) temp = 0;
		else temp = 1;

		ctx.registers[n] <<= 1;
		if ((ctx.SR & flagT) != 0) ctx.registers[n] |= 0x00000001;
		else ctx.registers[n] &= 0xFFFFFFFE;

		if (temp == 1)
			ctx.SR |= flagT;
		else
			ctx.SR &= ~flagT;


		// Logger.log(Logger.CPU,String.format("rotcl: r[%d]=%x\r", n, ctx.registers[n]));
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void SHAR(int code) {
		int n = RN(code);
		int temp = 0;
		if ((ctx.registers[n] & 0x00000001) == 0) ctx.SR &= (~flagT);
		else ctx.SR |= flagT;
		if ((ctx.registers[n] & 0x80000000) == 0) temp = 0;
		else temp = 1;
		ctx.registers[n] >>= 1;
		if (temp == 1) ctx.registers[n] |= 0x80000000;
		else ctx.registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("shar: despues %x\r", ctx.registers[n]));
		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHAL(int code) {
		int n = RN(code);

		if ((ctx.registers[n] & 0x80000000) == 0)
			ctx.SR &= (~flagT);
		else ctx.SR |= flagT;
		ctx.registers[n] <<= 1;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLL(int code) {
		int n = RN(code);

		//	Logger.log(Logger.CPU,String.format("shll: antes %x\r", ctx.registers[n]));
		ctx.SR = (ctx.SR & ~flagT) | ((ctx.registers[n] >>> 31) & 1);
		ctx.registers[n] <<= 1;

		//Logger.log(Logger.CPU,String.format("shll: despues %x\r", ctx.registers[n]));
		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLR(int code) {
		int n = RN(code);

		ctx.SR = (ctx.SR & ~flagT) | (ctx.registers[n] & 1);
		ctx.registers[n] >>>= 1;
		ctx.registers[n] &= 0x7FFFFFFF;

		//Logger.log(Logger.CPU,String.format("shlr: r[%d]=%x\r\n", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLL2(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 2;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLR2(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 2;
		ctx.registers[n] &= 0x3FFFFFFF;

//		System.out.println(String.format("shlr2: r[%d]=%x\r\n", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLL8(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 8;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLR8(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 8;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLL16(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 16;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SHLR16(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 16;

		ctx.registers[n] &= 0x0000FFFF;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void BF(int code) {
		if ((ctx.SR & flagT) == 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			ctx.PC += d + 4;

			ctx.cycles--;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	private final void BFS(int code) {
		if ((ctx.SR & flagT) == 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			int pc = ctx.PC + d + 4;

			decode(memory.read16i(ctx.PC + 2));

			ctx.PC = pc;

			ctx.cycles--;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	private final void BT(int code) {
		if ((ctx.SR & flagT) != 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			ctx.PC = ctx.PC + d + 4;

			ctx.cycles--;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	private final void BTS(int code) {
		if ((ctx.SR & flagT) != 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			int pc = ctx.PC + d + 4;

			decode(memory.read16i(ctx.PC + 2));

			ctx.PC = pc;
			ctx.cycles--;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	private final void BRA(int code) {
		int disp;

		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);

		int pc = ctx.PC + 4 + (disp << 1);

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = pc;

		ctx.cycles -= 2;
	}

	private final void BSR(int code) {
		int disp = 0;
		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);


		ctx.PR = ctx.PC;

		int pc = ctx.PC + (disp << 1) + 4;

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = pc;

		ctx.cycles -= 2;
	}

	private final void BRAF(int code) {
		int n = RN(code);

		int pc = ctx.PC + ctx.registers[n] + 4;

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = pc;
		ctx.cycles -= 2;
	}

	private final void BSRF(int code) {
		int n = RN(code);

		ctx.PR = ctx.PC;

		int pc = ctx.PC + ctx.registers[n] + 4;

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = pc;

		ctx.cycles -= 2;
	}

	private final void JMP(int code) {
		int n = RN(code);

		int target = ctx.registers[n];

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = target;

		ctx.cycles -= 2;
	}

	private final void JSR(int code) {
		int n = RN(code);

		ctx.PR = ctx.PC;

		int target = ctx.registers[n];

		decode(memory.read16i(ctx.PC + 2));

		ctx.PC = target;

		ctx.cycles -= 2;
	}

	private final void RTS(int code) {
		int prevPC = ctx.PC;
		int newPc = ctx.PR + 4;
		//delayed branch inst, run before setting PC as it is changing PC
		decode(memory.read16i(prevPC + 2));
		ctx.PC = newPc;
		ctx.cycles -= 2;
	}

	private final void RTE(int code) {
		int prevPC = ctx.PC;
		//delayed branch inst, run before setting PC as it is changing PC
		decode(memory.read16i(prevPC + 2));
		//NOTE should be +4, but we don't do it, see processInt
		ctx.PC = pop();
		ctx.SR = pop() & SR_MASK;
		ctx.cycles -= 5;
	}

	private final void CLRMAC(int code) {
		ctx.MACL = ctx.MACH = 0;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void CLRT(int code) {
		ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void LDCSR(int code) {
		int m = RN(code);
		ctx.SR = ctx.registers[m] & SR_MASK;

		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	private final void LDCGBR(int code) {
		int m = RN(code);

		ctx.GBR = ctx.registers[m];

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	private final void LDCVBR(int code) {
		int m = RN(code);

		ctx.VBR = ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void LDCMSR(int code) {
		int m = RN(code);

		ctx.SR = memory.read32i(ctx.registers[m]) & SR_MASK;
		ctx.registers[m] += 4;

		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	private final void LDCMGBR(int code) {
		int m = RN(code);

		ctx.GBR = memory.read32i(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	private final void LDCMVBR(int code) {
		int m = RN(code);

		ctx.VBR = memory.read32i(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void LDSMACH(int code) {
		int m = RN(code);

		ctx.MACH = ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void LDSMACL(int code) {
		int m = RN(code);

		ctx.MACL = ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void LDSPR(int code) {
		int m = RN(code);

		ctx.PR = ctx.registers[m];

//		System.out.println("LDS " + Integer.toHexString(PR));

		ctx.PC += 2;

		ctx.cycles -= 2;

	}

	private final void LDSMMACH(int code) {
		int m = RN(code);

		ctx.MACH = memory.read32i(ctx.registers[m]);
		ctx.registers[m] += 4;


		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void LDSMMACL(int code) {
		int m = RN(code);

		ctx.MACL = memory.read32i(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles -= 2;
		ctx.PC += 2;

	}


	private final void LDSMPR(int code) {
		int m = RN(code);

		ctx.PR = memory.read32i(ctx.registers[m]);

		//	System.out.println("LSMctx.PR Register[ " + m + "] to ctx.MACL " + Integer.toHexString(PR));

		ctx.registers[m] += 4;

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void LDTLB(int code) {
		ctx.cycles--;
		ctx.PC += 2;
	}

	private final void NOP(int code) {
		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SETT(int code) {
		ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void SLEEP(int code) {
		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	private final void STCSR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.SR;

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void STCGBR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.GBR;
		ctx.PC += 2;
		ctx.cycles -= 2;

	}

	private final void STCVBR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.VBR;

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	private final void STCMSR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.SR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void STCMGBR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.GBR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void STCMVBR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.VBR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void STSMACH(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.MACH;

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void STSMACL(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.MACL;


		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void STSPR(int code) {
		int n = RN(code);


		ctx.registers[n] = ctx.PR;

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	private final void STSMMACH(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.MACH);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void STSMMACL(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.MACL);

		ctx.cycles--;
		ctx.PC += 2;

	}

	private final void STSMPR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32i(ctx.registers[n], ctx.PR);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	//TODO sh2
	private final void TRAPA(int code) {
		int imm;
		imm = (0x000000FF & code);

//		memory.regmapWritehandle32Inst(MMREG.TRA,imm<<2);
//		SPC=PC+2;
//		SGR=ctx.registers[15];
//		ctx.SR |= flagMD;
//		ctx.SR |= flagBL;
//		ctx.SR |= flagsRB;
//		memory.regmapWritehandle32Inst(MMREG.EXPEVT,0x00000160);
		ctx.PC = ctx.VBR + 0x00000100;
		ctx.cycles -= 7;
		ctx.PC += 2;
		throw new RuntimeException("TRAPA");
	}

	protected void printDebugMaybe(Sh2Context ctx, int instruction) {
		if (ctx.debug) {
			Sh2Helper.printInst(ctx, instruction);
		}
	}

	/*
	 * Because an instruction in a delay slot cannot alter the PC we can do this.
	 * Perf: better to keep run() close to decode()
	 */
	public void run(final Sh2Context ctx) {
		this.ctx = ctx;
		memory.setSh2Access(ctx.sh2Access);
		int opcode;
		for (; ctx.cycles >= 0; ) {
			opcode = memory.read16i(ctx.PC);
			printDebugMaybe(ctx, opcode);
			try {
				decode(opcode);
			} catch (Exception e) {
				Sh2Helper.printState(ctx, opcode);
				e.printStackTrace();
				System.exit(1);
			}
			acceptInterrupts(ctx);
		}
		ctx.cycles_ran = burstCycles - ctx.cycles;
		ctx.cycles = burstCycles;
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
		UNKNOWN(instruction);
	}
}
