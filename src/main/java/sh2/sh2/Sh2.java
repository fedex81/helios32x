package sh2.sh2;

import omegadrive.Device;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface Sh2 extends Device {

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

    int STACK_LIMIT_SIZE = 0x1000;

    void reset(Sh2Context context);

    void run(Sh2Context masterCtx);

    void setCtx(Sh2Context ctx);
}
