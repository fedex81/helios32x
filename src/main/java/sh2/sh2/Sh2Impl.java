package sh2.sh2;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.Md32xRuntimeData;
import sh2.Sh2MMREG;
import sh2.sh2.device.IntControl;

import static omegadrive.util.Util.th;

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
public class Sh2Impl implements Sh2 {

	private final static Logger LOG = LogManager.getLogger(Sh2Impl.class.getSimpleName());

	protected Sh2Context ctx;
	protected IMemory memory;

	public Sh2Impl(IMemory memory) {
		this.memory = memory;
	}

	public static final int RN(int x) {
		return ((x >> 8) & 0xf);
	}

	public static final int RM(int x) {
		return ((x >> 4) & 0xf);
	}

	public void reset(Sh2Context ctx) {
		Md32xRuntimeData.setAccessTypeExt(ctx.cpuAccess);
		ctx.VBR = 0;
		ctx.PC = memory.read32(0);
		ctx.SR = flagIMASK;
		ctx.registers[15] = memory.read32(4); //SP
		ctx.cycles = Sh2Context.burstCycles;
		LOG.info("{} Reset, PC: {}, SP: {}", ctx.cpuAccess, th(ctx.PC), th(ctx.registers[15]));
	}

	//push to stack
	private void push(int data) {
		ctx.registers[15] -= 4;
		memory.write32(ctx.registers[15], data);
//		System.out.println(ctx.sh2Access + " PUSH SP: " + Integer.toHexString(ctx.registers[15])
//				+ "," + Integer.toHexString(data));
	}

	//pop from stack
	private int pop() {
		int res = memory.read32(ctx.registers[15]);
//		System.out.println(ctx.cpuAccess + " POP SP: " + Integer.toHexString(ctx.registers[15])
//				+ "," + Integer.toHexString(res));
		ctx.registers[15] += 4;
		return res;
	}

	private void ILLEGAL(int code) {
		push(ctx.SR);
		push(ctx.PC);
		LOG.error("{} illegal instruction: {}\n{}", ctx.cpuAccess, th(code),
				Sh2Helper.toDebuggingString(ctx));
		ctx.PC = memory.read32(ctx.VBR + (ILLEGAL_INST_VN << 2));
		ctx.cycles -= 5;
//		if(true) throw new RuntimeException();
	}

	protected final void MOVI(int code) {
		int n = ((code >> 8) & 0x0f);
		//8 bit sign extend
		ctx.registers[n] = (int) (byte) code;
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		ctx.registers[n] = (int) (short) memory.read16(ctx.PC + 4 + (d << 1));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLI(int code) {
		int d = (code & 0xff);
		int n = ((code >> 8) & 0x0f);

		ctx.registers[n] = memory.read32((ctx.PC & 0xfffffffc) + 4 + (d << 2));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOV(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8(ctx.registers[n], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLS(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write32(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (byte) memory.read8(ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (short) memory.read16(ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32(ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 1;
		memory.write8(ctx.registers[n], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 2;
		memory.write16(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVLM(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVBP(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (byte) memory.read8(ctx.registers[m]);
		if (n != m) ctx.registers[m] += 1;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVWP(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (short) memory.read16(ctx.registers[m]);
		if (n != m) ctx.registers[m] += 2;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLP(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32(ctx.registers[m]);
		if (n != m) ctx.registers[m] += 4;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write8(ctx.registers[n] + (d << 0), (byte) ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVWS4(int code) {
		int d = ((code >> 0) & 0x0f);
		int n = RM(code);

		memory.write16(ctx.registers[n] + (d << 1), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVLS4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		memory.write32(ctx.registers[n] + (d << 2), ctx.registers[m]);
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		ctx.registers[0] = (int) (byte) memory.read8(ctx.registers[m] + d);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWL4(int code) {
		int d = ((code >> 0) & 0x0f);
		int m = RM(code);

		ctx.registers[0] = (int) (short) memory.read16(ctx.registers[m] + (d << 1));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLL4(int code) {
		int d = (code & 0x0f);
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32(ctx.registers[m] + (d << 2));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write8(ctx.registers[n] + ctx.registers[0], (byte) ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write16(ctx.registers[n] + ctx.registers[0], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVLS0(int code) {
		int m = RM(code);
		int n = RN(code);

		memory.write32(ctx.registers[n] + ctx.registers[0], ctx.registers[m]);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVBL0(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (byte) memory.read8(ctx.registers[m] + ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVWL0(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (short) memory.read16(ctx.registers[m] + ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVLL0(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = memory.read32(ctx.registers[m] + ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVBSG(int code) {
		int d = (code & 0xff);

		memory.write8(ctx.GBR + d, (byte) ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVWSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write16(ctx.GBR + (d << 1), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVLSG(int code) {
		int d = ((code >> 0) & 0xff);

		memory.write32(ctx.GBR + (d << 2), ctx.registers[0]);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVBLG(int code) {
		int d = ((code >> 0) & 0xff);

		ctx.registers[0] = (int) (byte) memory.read8(ctx.GBR + (d << 0));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVWLG(int code) {
		int d = ((code >> 0) & 0xff);

		ctx.registers[0] = (int) (short) memory.read16(ctx.GBR + (d << 1));

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void MOVLLG(int code) {
		int d = ((code >> 0) & 0xff);

		ctx.registers[0] = memory.read32(ctx.GBR + (d << 2));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MOVA(int code) {
		int d = (code & 0x000000ff);

		ctx.registers[0] = ((ctx.PC + 4) & 0xfffffffc) + (d << 2);

		ctx.cycles--;
		ctx.PC += 2;
		//If this instruction is placed immediately after a delayed branch instruction, the PC must
		//point to an address specified by (the starting address of the branch destination) + 2.
		if (ctx.delaySlot) {
			ctx.registers[0] = ((ctx.delayPC + 2) & 0xfffffffc) + (d << 2);
		}
	}

	protected final void MOVT(int code) {
		int n = RN(code);

		ctx.registers[n] = (ctx.SR & flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SWAPB(int code) {
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

	protected final void SWAPW(int code) {
		int m = RM(code);
		int n = RN(code);
		int temp = 0;
		temp = (ctx.registers[m] >>> 16) & 0x0000FFFF;
		ctx.registers[n] = ctx.registers[m] << 16;
		ctx.registers[n] |= temp;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void XTRCT(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ((ctx.registers[n] & 0xffff0000) >>> 16) |
				((ctx.registers[m] & 0x0000ffff) << 16);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ADD(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] += ctx.registers[m];
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ADDI(int code) {
		int n = RN(code);
		byte b = (byte) (code & 0xff);
		//System.out.println("ADDI before " + Integer.toHexString(ctx.registers[n]) + "value to be added " + Integer.toHexString(b));

		ctx.registers[n] += (int) b;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ADDC(int code) {
		int m = RM(code);
		int n = RN(code);
		long tmp0 = ctx.registers[n] & 0xFFFF_FFFFL;
		long tmp1 = (tmp0 + ctx.registers[m]) & 0xFFFF_FFFFL;
		long regN = (int) (tmp1 + (ctx.SR & flagT)) & 0xFFFF_FFFFL;
		boolean tb = tmp0 > tmp1 || tmp1 > regN;
		ctx.SR &= (~flagT);
		ctx.SR |= tb ? flagT : 0;
		ctx.registers[n] = (int) regN;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ADDV(int code) {
		int m = RM(code);
		int n = RN(code);

		long d = (ctx.registers[n] < 0) ? 1 : 0;
		long s = ((ctx.registers[m] < 0) ? 1 : 0) + d;
		ctx.registers[n] += ctx.registers[m];
		long r = ((ctx.registers[n] < 0) ? 1 : 0) + d;
		ctx.SR &= (~flagT);
		ctx.SR = s != 1 ? (r == 1 ? 1 : 0) : 0;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void CMPIM(int code) {
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

	protected final void CMPEQ(int code) {
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

	protected final void CMPHS(int code) {
		int m = RM(code);
		int n = RN(code);

		if (((long) (ctx.registers[n] & 0xFFFFFFFFL)) >= ((long) (ctx.registers[m] & 0xFFFFFFFFL)))
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hs: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void CMPGE(int code) {
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

	protected final void CMPHI(int code) {
		int m = RM(code);
		int n = RN(code);
		if (((long) (ctx.registers[n] & 0xFFFFFFFFL)) > ((long) (ctx.registers[m] & 0xFFFFFFFFL)))
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/hi: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void CMPGT(int code) {
		int m = RM(code);
		int n = RN(code);

		if (ctx.registers[n] > ctx.registers[m])
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/gt: r[%d]=%x >= r[%d]=%x ?\r", n, ctx.registers[n], m, ctx.registers[m]));

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void CMPPZ(int code) {
		int n = RN(code);

		if (ctx.registers[n] >= 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x >= 0 ?\r", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void CMPPL(int code) {
		int n = RN(code);

		if (ctx.registers[n] > 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

		//Logger.log(Logger.CPU,String.format("cmp/pz: r[%d]=%x > 0 ?\r", n, ctx.registers[n]));

		ctx.cycles--;
		ctx.PC += 2;

		//Logger.log(Logger.CPU,"CMPPL " + ctx.registers[n]);
	}

	protected final void CMPSTR(int code) {
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

	protected final void DIV1(int code) {
		DIV1(ctx, RN(code), RM(code));    //dividend, divisor
	}

	/**
	 * From Ares
	 */
	public final static void DIV1(Sh2Context ctx, int dvd, int dvsr) {
		long udvd = ctx.registers[dvd] & 0xFFFF_FFFFL;
		long udvsr = ctx.registers[dvsr] & 0xFFFF_FFFFL;
		boolean old_q = (ctx.SR & flagQ) > 0;
		ctx.SR &= ~flagQ;
		ctx.SR |= ((udvd >> 31) & 1) << posQ;
		long r = (udvd << 1) & 0xFFFF_FFFFL;
		r |= (ctx.SR & flagT);
		if (old_q == ((ctx.SR & flagM) > 0)) {
			r -= udvsr;
		} else {
			r += udvsr;
		}
		ctx.registers[dvd] = (int) r;
		int qm = ((ctx.SR >> posQ) & 1) ^ ((ctx.SR >> posM) & 1);
		int q = qm ^ (int) ((r >> 32) & 1);
		qm = q ^ ((ctx.SR >> posM) & 1);
		int t = 1 - qm;
		ctx.SR &= ~(flagQ | flagT);
		ctx.SR |= (q << posQ) | t;

//		System.out.printf("####,div1: r[%d]=%x >= r[%d]=%x, %d, %d, %d\n", dvd,
//				ctx.registers[dvd], dvsr, ctx.registers[dvsr], ((ctx.SR & flagM) > 0) ? 1 : 0,
//				((ctx.SR & flagQ) > 0) ? 1 : 0,
//				((ctx.SR & flagT) > 0) ? 1 : 0);
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void DIV0S(int code) {
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
//		System.out.printf("####,div0s: r[%d]=%x >= r[%d]=%x, %d, %d, %d\n", n,
//				ctx.registers[n], m, ctx.registers[m], ((ctx.SR & flagM) > 0) ? 1 : 0,
//				((ctx.SR & flagQ) > 0) ? 1 : 0,
//				((ctx.SR & flagT) > 0) ? 1 : 0);
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void DIV0U(int code) {
		ctx.SR &= ~(flagQ | flagM | flagT);
		ctx.cycles--;
		ctx.PC += 2;
//		System.out.printf("####,div0u: %d, %d, %d\n", ((ctx.SR & flagM) > 0) ? 1: 0,
//				((ctx.SR & flagQ) > 0) ? 1: 0,
//				((ctx.SR & flagT) > 0) ? 1: 0);
	}

	protected final void DMULS(int code) {
		int m = RM(code);
		int n = RN(code);

		long mult = (long) ctx.registers[n] * (long) ctx.registers[m];
//		System.out.printf("####,DMULS,%8X,%8X,%16X\n", ctx.registers[n], ctx.registers[m], mult);

		ctx.MACL = (int) (mult & 0xffffffff);
		ctx.MACH = (int) ((mult >>> 32) & 0xffffffff);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void DMULU(int code) {
		int m = RM(code);
		int n = RN(code);

		// this should be unsigned but oh well :/
		long mult = (long) (ctx.registers[n] & 0xffffffffL) * (long) (ctx.registers[m] & 0xffffffffL);

//		System.out.printf("####,DMULU,%8X,%8X,%16X\n", ctx.registers[n], ctx.registers[m], mult);

		ctx.MACL = (int) (mult & 0xffffffff);
		ctx.MACH = (int) ((mult >>> 32) & 0xffffffff);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void DT(int code) {
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

	protected final void EXTSB(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (byte) ctx.registers[m];
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void EXTSW(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = (int) (short) ctx.registers[m];
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void EXTUB(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m] & 0x000000FF;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void EXTUW(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ctx.registers[m] & 0x0000FFFF;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void MACL(int code) {
		int m = RM(code);
		int n = RN(code);
		long regN = memory.read32(ctx.registers[n]);
		ctx.registers[n] += 4;
		long regM = memory.read32(ctx.registers[m]);
		ctx.registers[m] += 4;

		long res = regM * regN;
		res += ((ctx.MACH & 0xFFFF_FFFFL) << 32) + (ctx.MACL & 0xFFFF_FFFFL);
		if ((ctx.SR & flagS) > 0) {
			if (res > 0x7FFF_FFFF_FFFFL) {
				res = 0x7FFF_FFFF_FFFFL;
			} else if (res < 0xFFFF_8000_0000_0000L) {
				res = 0xFFFF_8000_0000_0000L;
			}
		}
		ctx.MACH = (int) (res >> 32);
		ctx.MACL = (int) (res & 0xFFFF_FFFF);
		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void MACW(int code) {
		final int m = RM(code);
		final int n = RN(code);
		final short rn = (short) memory.read16(ctx.registers[n]);
		ctx.registers[n] += 2;
		final short rm = (short) memory.read16(ctx.registers[m]);
		ctx.registers[m] += 2;
		MACW(ctx, rn, rm);
	}

	protected static final void MACW(Sh2Context ctx, short rn, short rm) {
//		String s = "#### " + th(rn) + "," + th(rm) + "," + th(ctx.MACH) + "," + th(ctx.MACL) + ",S=" +
//				((ctx.SR & flagS) > 0);
		int macl = ctx.MACL;
		int mach = ctx.MACH;
		if ((ctx.SR & flagS) > 0) { //16 x 16 + 32
			long res = rm * rn + (long) ctx.MACL;
			//saturation
			if (res > 0x7FFF_FFFFL) {
				res = 0x7FFF_FFFFL;
				ctx.MACH |= 1;
			} else if (res < 0xFFFF_FFFF_8000_0000L) {
				res = 0xFFFF_FFFF_8000_0000L;
				ctx.MACH |= 1;
			}
			ctx.MACL = (int) (res & 0xFFFF_FFFF);
		} else { //16 x 16 + 64
			long prod = rm * rn;
			long mac = ((ctx.MACH & 0xFFFF_FFFFL) << 32) + (ctx.MACL & 0xFFFF_FFFFL);
			long res = prod + mac;
			ctx.MACH = (int) (res >> 32);
			ctx.MACL = (int) (res & 0xFFFF_FFFF);
			//overflow
			if ((prod > 0 && mac > 0 && res < 0) || (mac < 0 && prod < 0 && res > 0)) {
				ctx.MACH |= 1;
			}
		}
		ctx.cycles -= 2;
		ctx.PC += 2;
//		System.out.println("1>>>>> " + th(ctx.MACH) + "," + th(ctx.MACL));
	}

	protected final void MULL(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.MACL = (ctx.registers[n] * ctx.registers[m]) & 0xFFFF_FFFF;
//		System.out.printf("####,MULL,%8X,%8X,%16X\n", ctx.registers[n], ctx.registers[m], ctx.MACL);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void MULSW(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.MACL = (int) (short) ctx.registers[n] * (int) (short) ctx.registers[m];
//		System.out.printf("####,MULSW,%8X,%8X,%16X\n", ctx.registers[n], ctx.registers[m], ctx.MACL);
		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void MULSU(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.MACL = (((int) ctx.registers[n] & 0xFFFF) * ((int) ctx.registers[m] & 0xFFFF));
//		System.out.printf("####,MULSU,%8X,%8X,%16X\n", ctx.registers[n], ctx.registers[m], ctx.MACL);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void NEG(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = 0 - ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void NEGC(int code) {
		int m = RM(code);
		int n = RN(code);

		long tmp = (0 - ctx.registers[m]) & 0xFFFF_FFFFL;
		long regN = (tmp - (ctx.SR & flagT)) & 0xFFFF_FFFFL;
		ctx.registers[n] = (int) regN;
		if (0 < tmp)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		if (tmp < regN)
			ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SUB(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] -= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SUBC(int code) {
		int m = RM(code);
		int n = RN(code);

		long tmp0 = ctx.registers[n] & 0xFFFF_FFFFL;
		long tmp1 = (ctx.registers[n] - ctx.registers[m]) & 0xFFFF_FFFFL;
		long regN = (tmp1 - (ctx.SR & flagT)) & 0xFFFF_FFFFL;
		ctx.registers[n] = (int) regN;
		ctx.SR &= (~flagT);
		ctx.SR |= tmp0 < tmp1 || tmp1 < regN ? flagT : 0;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SUBV(int code) {
		int m = RM(code);
		int n = RN(code);

		int dest = (ctx.registers[n] >> 31) & 1;
		int src = ((ctx.registers[m] >> 31) & 1) + dest;

		ctx.registers[n] -= ctx.registers[m];
		int r = ((ctx.registers[n] >> 31) & 1) + dest;
		ctx.SR &= (~flagT);
		ctx.SR |= src == 1 && r == 1 ? flagT : 0;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void AND(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] &= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ANDI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] &= i;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ANDM(int code) {
		int i = (byte) ((code >> 0) & 0xff);

		int value = (byte) memory.read8(ctx.GBR + ctx.registers[0]);
		memory.write8(ctx.GBR + ctx.registers[0], ((byte) (value & i)));

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void NOT(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] = ~ctx.registers[m];


		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void OR(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] |= ctx.registers[m];


		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ORI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] |= i;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void ORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8(ctx.GBR + ctx.registers[0]);
		memory.write8(ctx.GBR + ctx.registers[0], ((byte) (value | i)));

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void TAS(int code) {
		int n = RN(code);

		byte value = (byte) memory.read8(ctx.registers[n]);
		if (value == 0)
			ctx.SR |= 0x1;
		else ctx.SR &= ~0x1;
		memory.write8(ctx.registers[n], ((byte) (value | 0x80)));

		ctx.cycles -= 4;

		ctx.PC += 2;
		if (true) new RuntimeException();
	}

	protected final void TST(int code) {
		int m = RM(code);
		int n = RN(code);

		if ((ctx.registers[n] & ctx.registers[m]) == 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);


		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void TSTI(int code) {
		int i = ((code >> 0) & 0xff);
//		int prevT = ctx.SR & flagT;
		if ((ctx.registers[0] & i) == 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);

//		System.out.println("#### R0: " + Integer.toHexString(ctx.registers[0]) +
//				", imm: " + Integer.toHexString(i) + ", res: " + Integer.toHexString(ctx.registers[0] & i)
//				+ ", prevT: "+prevT+", T: " + (ctx.SR & flagT));

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void TSTM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8(ctx.GBR + ctx.registers[0]);
		if ((value & i) == 0)
			ctx.SR |= flagT;
		else ctx.SR &= (~flagT);
		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void XOR(int code) {
		int m = RM(code);
		int n = RN(code);

		ctx.registers[n] ^= ctx.registers[m];

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void XORI(int code) {
		int i = ((code >> 0) & 0xff);

		ctx.registers[0] ^= i;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void XORM(int code) {
		int i = ((code >> 0) & 0xff);

		int value = memory.read8(ctx.GBR + ctx.registers[0]);
		memory.write8(ctx.GBR + ctx.registers[0], ((byte) (value ^ i)));

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void ROTR(int code) {
		int n = RN(code);

		ctx.SR &= ~flagT;
		ctx.SR |= ctx.registers[n] & flagT;
		ctx.registers[n] = (ctx.registers[n] >>> 1) | (ctx.registers[n] << 31);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ROTCR(int code) {
		int n = RN(code);

		int temp = ctx.registers[n] & 1;
		ctx.registers[n] = (ctx.registers[n] >>> 1) | ((ctx.SR & flagT) << 31);
		ctx.SR &= ~flagT;
		ctx.SR |= temp;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ROTL(int code) {
		int n = RN(code);
		ctx.SR &= ~flagT;
		ctx.SR |= (ctx.registers[n] >>> 31) & flagT;
		ctx.registers[n] = (ctx.registers[n] << 1) | (ctx.registers[n] >>> 31);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void ROTCL(int code) {
		int n = RN(code);

		int temp = (ctx.registers[n] >>> 31) & 1;
		ctx.registers[n] = (ctx.registers[n] << 1) | (ctx.SR & flagT);
		ctx.SR &= ~flagT;
		ctx.SR |= temp;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHAR(int code) {
		int n = RN(code);

		ctx.SR &= (~flagT);
		ctx.SR |= ctx.registers[n] & 1;
		ctx.registers[n] = ctx.registers[n] >> 1;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHAL(int code) {
		int n = RN(code);

		ctx.SR &= (~flagT);
		ctx.SR |= (ctx.registers[n] >>> 31) & 1;
		ctx.registers[n] = ctx.registers[n] << 1;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHLL(int code) {
		int n = RN(code);

		ctx.SR = (ctx.SR & ~flagT) | ((ctx.registers[n] >>> 31) & 1);
		ctx.registers[n] <<= 1;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHLR(int code) {
		int n = RN(code);

		ctx.SR = (ctx.SR & ~flagT) | (ctx.registers[n] & 1);
		ctx.registers[n] >>>= 1;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHLL2(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 2;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHLR2(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 2;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void SHLL8(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 8;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void SHLR8(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 8;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void SHLL16(int code) {
		int n = RN(code);

		ctx.registers[n] <<= 16;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void SHLR16(int code) {
		int n = RN(code);

		ctx.registers[n] >>>= 16;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void BF(int code) {
		if ((ctx.SR & flagT) == 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			ctx.PC += d + 4;
			ctx.cycles -= 3;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	protected final void BFS(int code) {
		if ((ctx.SR & flagT) == 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			int prevPc = ctx.PC;
			ctx.PC = ctx.PC + d + 4;
			delaySlot(prevPc + 2);
		} else {
			ctx.PC += 2;
		}
		ctx.cycles--;
	}

	protected final void BT(int code) {
		if ((ctx.SR & flagT) != 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			ctx.PC = ctx.PC + d + 4;

			ctx.cycles -= 3;
		} else {
			ctx.cycles--;
			ctx.PC += 2;
		}
	}

	protected final void BTS(int code) {
		if ((ctx.SR & flagT) != 0) {
			//8 bit sign extend, then double
			int d = (byte) (code & 0xFF) << 1;
			int prevPc = ctx.PC;
			ctx.PC = ctx.PC + d + 4;
			delaySlot(prevPc + 2);
		} else {
			ctx.PC += 2;
		}
		ctx.cycles--;
	}

	protected final void BRA(int code) {
		int disp;

		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);

		int prevPc = ctx.PC;
		ctx.PC = ctx.PC + 4 + (disp << 1);
		delaySlot(prevPc + 2);
		ctx.cycles -= 2;
	}

	protected final void BSR(int code) {
		int disp = 0;
		if ((code & 0x800) == 0)
			disp = (0x00000FFF & code);
		else disp = (0xFFFFF000 | code);

		//PC is the start address of the second instruction after this instruction.
		ctx.PR = ctx.PC + 4;
		ctx.PC = ctx.PC + (disp << 1) + 4;
		delaySlot(ctx.PR - 2);
		ctx.cycles -= 2;
	}

	protected final void BRAF(int code) {
		int n = RN(code);

		int prevPc = ctx.PC;
		//PC is the start address of the second instruction after this instruction.
		ctx.PC += ctx.registers[n] + 4;

		delaySlot(prevPc + 2);
		ctx.cycles -= 2;
	}

	protected final void BSRF(int code) {
		int n = RN(code);

		//PC is the start address of the second instruction after this instruction.
		ctx.PR = ctx.PC + 4;
		ctx.PC = ctx.PC + ctx.registers[n] + 4;
		delaySlot(ctx.PR - 2);
		ctx.cycles -= 2;
	}

	protected final void JMP(int code) {
		int n = RN(code);
		int prevPc = ctx.PC;
		//NOTE: docs say this should be +4, are they wrong ??
		ctx.PC = ctx.registers[n];

		delaySlot(prevPc + 2);
		ctx.cycles -= 2;
	}

	protected final void JSR(int code) {
		int n = RN(code);

		ctx.PR = ctx.PC + 4;
		//NOTE: docs say this should be +4, are they wrong ??
		ctx.PC = ctx.registers[n];
		delaySlot(ctx.PR - 2);
		ctx.cycles -= 2;
	}

	protected final void RTS(int code) {
		int prevPc = ctx.PC;
		ctx.PC = ctx.PR;
		delaySlot(prevPc + 2);
		ctx.cycles -= 2;
	}

	protected final void RTE(int code) {
		int prevPc = ctx.PC;
		//NOTE should be +4, but we don't do it, see processInt
		ctx.PC = pop();
		ctx.SR = pop() & SR_MASK;
		delaySlot(prevPc + 2);
		ctx.cycles -= 4;
	}

	private void delaySlot(int pc) {
		ctx.delayPC = ctx.PC;
		ctx.PC = pc;
		ctx.delaySlot = true;
		decode(memory.fetchDelaySlot(pc, ctx.cpuAccess));
		ctx.delaySlot = false;
		ctx.PC = ctx.delayPC;
	}

	protected final void CLRMAC(int code) {
		ctx.MACL = ctx.MACH = 0;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void CLRT(int code) {
		ctx.SR &= (~flagT);

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void LDCSR(int code) {
		int m = RN(code);
		ctx.SR = ctx.registers[m] & SR_MASK;
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void LDCGBR(int code) {
		int m = RN(code);

		ctx.GBR = ctx.registers[m];
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void LDCVBR(int code) {
		int m = RN(code);

		ctx.VBR = ctx.registers[m];
		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void LDCMSR(int code) {
		int m = RN(code);

		ctx.SR = memory.read32(ctx.registers[m]) & SR_MASK;
		ctx.registers[m] += 4;

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void LDCMGBR(int code) {
		int m = RN(code);

		ctx.GBR = memory.read32(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles -= 3;
		ctx.PC += 2;
	}

	protected final void LDCMVBR(int code) {
		int m = RN(code);

		ctx.VBR = memory.read32(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles -= 3;
		ctx.PC += 2;

	}

	protected final void LDSMACH(int code) {
		int m = RN(code);

		ctx.MACH = ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void LDSMACL(int code) {
		int m = RN(code);

		ctx.MACL = ctx.registers[m];

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void LDSPR(int code) {
		int m = RN(code);

		ctx.PR = ctx.registers[m];
		ctx.PC += 2;
		ctx.cycles--;
	}

	protected final void LDSMMACH(int code) {
		int m = RN(code);

		ctx.MACH = memory.read32(ctx.registers[m]);
		ctx.registers[m] += 4;


		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void LDSMMACL(int code) {
		int m = RN(code);

		ctx.MACL = memory.read32(ctx.registers[m]);
		ctx.registers[m] += 4;

		ctx.cycles--;
		ctx.PC += 2;

	}


	protected final void LDSMPR(int code) {
		int m = RN(code);

		ctx.PR = memory.read32(ctx.registers[m]);

		//	System.out.println("LSMctx.PR Register[ " + m + "] to ctx.MACL " + Integer.toHexString(PR));

		ctx.registers[m] += 4;

		ctx.cycles--;
		ctx.PC += 2;
	}

	protected final void NOP(int code) {
		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void SETT(int code) {
		ctx.SR |= flagT;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void SLEEP(int code) {
		ctx.cycles -= 4;
		ctx.PC += 2;
	}

	protected final void STCSR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.SR;

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void STCGBR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.GBR;
		ctx.PC += 2;
		ctx.cycles -= 2;

	}

	protected final void STCVBR(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.VBR;

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void STCMSR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.SR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void STCMGBR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.GBR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void STCMVBR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.VBR);

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void STSMACH(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.MACH;

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void STSMACL(int code) {
		int n = RN(code);

		ctx.registers[n] = ctx.MACL;


		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void STSPR(int code) {
		int n = RN(code);


		ctx.registers[n] = ctx.PR;

		ctx.cycles -= 2;
		ctx.PC += 2;

	}

	protected final void STSMMACH(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.MACH);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void STSMMACL(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.MACL);

		ctx.cycles--;
		ctx.PC += 2;

	}

	protected final void STSMPR(int code) {
		int n = RN(code);

		ctx.registers[n] -= 4;
		memory.write32(ctx.registers[n], ctx.PR);

		ctx.cycles -= 2;
		ctx.PC += 2;
	}

	protected final void TRAPA(int code) {
		int imm = (0x000000FF & code);
		push(ctx.SR);
		push(ctx.PC + 2);

		//TODO check +4
		ctx.PC = memory.read32(ctx.VBR + (imm << 2)) + 4;
		if (true) new RuntimeException("TRAPA");
		ctx.cycles -= 8;
	}

	//NO-OP
	protected void printDebugMaybe(Sh2Context ctx) {
	}

	// get interrupt masks bits int the SR register
	private int getIMASK() {
		return (ctx.SR & flagIMASK) >>> 4;
	}

	private boolean acceptInterrupts(final int level) {
		if (level > getIMASK()) {
			processInterrupt(ctx, level);
			ctx.devices.intC.clearCurrentInterrupt();
			return true;
		}
		return false;
	}

	private void processInterrupt(final Sh2Context ctx, final int level) {
//		System.out.println(ctx.cpuAccess + " Interrupt processed: " + level);
		assert Md32xRuntimeData.getAccessTypeExt() == ctx.cpuAccess;
		push(ctx.SR);
		push(ctx.PC); //stores the next inst to be executed
		//SR 7-4
		ctx.SR &= 0xF0F;
		ctx.SR |= (level << 4);

		int vectorNum = ctx.devices.intC.getVectorNumber();
		ctx.PC = memory.read32(ctx.VBR + (vectorNum << 2));
		//5 + 3 mem accesses
		ctx.cycles -= 5;
	}

	/*
	 * Because an instruction in a delay slot cannot alter the PC we can do this.
	 * Perf: better to keep run() close to decode()
	 */
	public void run(final Sh2Context ctx) {
		this.ctx = ctx;
		final Sh2MMREG sh2MMREG = ctx.devices.sh2MMREG;
		final IntControl intControl = ctx.devices.intC;
		for (; ctx.cycles >= 0; ) {
			decode(memory.fetch(ctx.PC, ctx.cpuAccess));
			sh2MMREG.deviceStep();
			ctx.cycles -= Md32xRuntimeData.resetCpuDelayExt(); //TODO check perf
			if (acceptInterrupts(intControl.getInterruptLevel())) {
				ctx.cycles -= Md32xRuntimeData.resetCpuDelayExt();
				break;
			}
		}
		ctx.cycles_ran = Sh2Context.burstCycles - ctx.cycles;
		ctx.cycles = Sh2Context.burstCycles;
	}

	protected final void decode(int instruction) {
		ctx.opcode = instruction;
		printDebugMaybe(ctx);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
						ILLEGAL(instruction);
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
						ILLEGAL(instruction);
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
						ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
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
								ILLEGAL(instruction);
								return;
						}

					case 15:
						MACW(instruction);
						return;
					default:
						ILLEGAL(instruction);
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
						ILLEGAL(instruction);
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
		ILLEGAL(instruction);
	}

	public void setCtx(Sh2Context ctx) {
		this.ctx = ctx;
	}
}
