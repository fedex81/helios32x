package sh2;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 */
public interface IMemory {

    int read32i(int i);

    void write16i(int register, int register1);

    void write32i(int register, int register1);

    int read8i(int register);

    int read16i(int register);

    void write8i(int register, byte register1);

    void resetSh2();

    default void setSh2Access(Sh2Util.Sh2Access sh2Access) {
        //DO NOTHING
    }
}
