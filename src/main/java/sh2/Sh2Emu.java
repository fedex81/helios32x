package sh2;

//import sh4.Intc;


public class Sh2Emu {

	public enum Sh2Access {MASTER, SLAVE, M68K}

	public int connectedDevices = 0;
	public Sh2 sh2cpu;
	//	public Intc interruptController;
	//    public MMREG memoryMappedRegs;
	public IMemory memory;

	public Sh2Emu(IMemory memory) {
		this.memory = memory;
		sh2cpu = new Sh2(memory);
//		interruptController = new Intc(sh2cpu);
//		memoryMappedRegs = new MMREG(interruptController);
	}

	public void run(Sh2Context ctx) {
		sh2cpu.run(ctx);
//		syncHardware(sh2cpu.cycles_ran);
	}

	private void syncHardware(int cycles) {
//		memoryMappedRegs.TMU(cycles);
//		interruptController.acceptInterrupts();
	}
}
