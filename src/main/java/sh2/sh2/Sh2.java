package sh2.sh2;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.sh2.Sh2Helper.Sh2PcInfoWrapper;
import sh2.sh2.drc.Sh2Block;

import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

import static sh2.sh2.Sh2Helper.SH2_NOT_VISITED;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2 extends Device {

    Logger LOG = LogHelper.getLogger(Sh2.class.getSimpleName());

    int posT = 0;
    int posS = 1;
    int posQ = 8;
    int posM = 9;

    int flagT = 1 << posT;
    int flagS = 1 << posS;
    int flagIMASK = 0x000000f0;
    int flagQ = 1 << posQ;
    int flagM = 1 << posM;

    int SR_MASK = 0x3F3;

    int ILLEGAL_INST_VN = 4; //vector number

    //NOTE: this only works when R[15] is not being rewritten ie. only push/pop modify it
    int STACK_LIMIT_SIZE = 0x1000;

    void reset(Sh2Context context);

    void run(Sh2Context masterCtx);

    void setCtx(Sh2Context ctx);

    default void printDebugMaybe(int opcode) {
        //NO-OP
    }

    class FetchResult {
        public int pc, opcode;
        public Sh2Block block;
        //TODO
        public Sh2PcInfoWrapper piw = SH2_NOT_VISITED;
    }

    class Sh2Config {
        public final static Sh2Config DEFAULT_CONFIG = new Sh2Config();
        private static final AtomicReference<Sh2Config> instance = new AtomicReference<>(DEFAULT_CONFIG);
        public final boolean prefetchEn, cacheEn, drcEn, pollDetectEn, ignoreDelays, tasQuirk;

        private Sh2Config() {
            cacheEn = tasQuirk = true;
            prefetchEn = drcEn = pollDetectEn = ignoreDelays = false;
            LOG.info("Default config: {}", toString());
        }

        public Sh2Config(boolean prefetchEn, boolean cacheEn, boolean drcEn, boolean pollDetectEn, boolean ignoreDelays) {
            this(prefetchEn, cacheEn, drcEn, pollDetectEn, ignoreDelays, true);
        }


        public Sh2Config(boolean prefetchEn, boolean cacheEn, boolean drcEn, boolean pollDetectEn, boolean ignoreDelays, boolean tasQuirk) {
            this.prefetchEn = prefetchEn;
            this.cacheEn = cacheEn;
            this.drcEn = drcEn;
            this.pollDetectEn = pollDetectEn;
            this.ignoreDelays = ignoreDelays;
            this.tasQuirk = tasQuirk;
            if (instance.compareAndSet(DEFAULT_CONFIG, this)) {
                LOG.info("Using config: {}", this);
            } else {
                LOG.error("Ignoring config: {}, current: {}", this, instance);
            }
        }

        public static Sh2Config get() {
            return instance.get();
        }

        //force config, test only
        public static void reset(Sh2Config config) {
            instance.set(config);
            LOG.warn("Overriding config: {}", config);
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", Sh2Config.class.getSimpleName() + "[", "]")
                    .add("prefetchEn=" + prefetchEn)
                    .add("cacheEn=" + cacheEn)
                    .add("drcEn=" + drcEn)
                    .add("pollDetectEn=" + pollDetectEn)
                    .add("ignoreDelays=" + ignoreDelays)
                    .toString();
        }
    }
}
