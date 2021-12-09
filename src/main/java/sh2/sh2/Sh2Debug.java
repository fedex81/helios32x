package sh2.sh2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sh2.IMemory;
import sh2.IntC;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public class Sh2Debug extends Sh2 {

    private static final Logger LOG = LogManager.getLogger(Sh2Debug.class.getSimpleName());

    enum DebugMode {INST_ONLY, NEW_INST_ONLY, STATE}

    private DebugMode debugMode = DebugMode.NEW_INST_ONLY;
    private int[][] pcVisited = new int[2][0x1000_0000];

    public Sh2Debug(IMemory memory, IntC intc) {
        super(memory, intc);
        LOG.warn("Sh2 cpu: creating debug instance");
    }

    protected void printDebugMaybe(Sh2Context ctx, int opcode) {
        if (ctx.debug) {
            switch (debugMode) {
                case STATE:
                    Sh2Helper.printState(ctx, opcode);
                    break;
                case INST_ONLY:
                    Sh2Helper.printInst(ctx, opcode);
                    break;
                case NEW_INST_ONLY:
                    printNewInst(ctx, opcode);
                    break;
                default:
                    break;
            }
        }
    }

    private void printNewInst(Sh2Context ctx, int opcode) {
        final int c = ctx.cpuAccess.ordinal();
        if (pcVisited[c][ctx.PC] == 0) {
            String s = Sh2Helper.getInstString(ctx, opcode);
            System.out.println(s + " [NEW]");
        }
        pcVisited[c][ctx.PC]++;
    }
}
