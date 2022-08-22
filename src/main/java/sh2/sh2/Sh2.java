package sh2.sh2;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.sh2.drc.Sh2Block;

import java.util.StringJoiner;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2 extends Device {

    Logger LOG = LogHelper.getLogger(Sh2.class.getSimpleName());

    public static final int posT = 0;
    public static final int posS = 1;
    public static final int posQ = 8;
    public static final int posM = 9;

    public static final int flagT = 1 << posT;
    public static final int flagS = 1 << posS;
    public static final int flagIMASK = 0x000000f0;
    public static final int flagQ = 1 << posQ;
    public static final int flagM = 1 << posM;

    public static final int SR_MASK = 0x3F3;

    public static final int ILLEGAL_INST_VN = 4; //vector number

    void reset(Sh2Context context);

    void run(Sh2Context masterCtx);

    void setCtx(Sh2Context ctx);

    default void printDebugMaybe(int opcode) {
        //NO-OP
    }

    class FetchResult {
        public int pc;
        public int opcode;
        public Sh2Block block;
    }

    class Sh2Config {
        public final boolean prefetchEn, cacheEn, drcEn, pollDetectEn;

        public Sh2Config(boolean prefetchEn, boolean cacheEn, boolean drcEn, boolean pollDetectEn) {
            this.prefetchEn = prefetchEn;
            this.cacheEn = cacheEn;
            this.drcEn = drcEn;
            this.pollDetectEn = pollDetectEn;
            LOG.info(toString());
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", Sh2Config.class.getSimpleName() + "[", "]")
                    .add("prefetchEn=" + prefetchEn)
                    .add("cacheEn=" + cacheEn)
                    .add("drcEn=" + drcEn)
                    .add("pollDetectEn=" + pollDetectEn)
                    .toString();
        }
    }
}
