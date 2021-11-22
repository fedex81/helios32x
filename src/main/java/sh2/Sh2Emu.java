package sh2;

import memory.IMemory;
import sh4.Intc;
//import sh4.MMREG;
import sh4.Sh4Disassembler;


public class Sh2Emu {

    public enum Sh2Access {MASTER, SLAVE, M68K}

    public int connectedDevices = 0;
    public Sh2Context sh2cpu;
    public Intc interruptController;
//    public MMREG memoryMappedRegs;
    public IMemory memory;
    public Sh2Access sh2Access;

    public Sh2Emu(Sh2Access sh2Access, IMemory memory) {
        this.memory = memory;
		this.sh2Access = sh2Access;
		sh2cpu = new Sh2Context(sh2Access, memory);
		sh2cpu.setDisassembler(new Sh4Disassembler());
		interruptController = new Intc(sh2cpu);
//		memoryMappedRegs = new MMREG(interruptController);
	}

	public void run() {
		sh2cpu.run();
//		syncHardware(sh2cpu.cycles_ran);
	}

	private void syncHardware(int cycles) {
//		memoryMappedRegs.TMU(cycles);
		interruptController.acceptInterrupts();
	}
}
