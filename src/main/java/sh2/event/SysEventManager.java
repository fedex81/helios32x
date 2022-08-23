package sh2.event;

import sh2.S32xUtil.CpuDeviceAccess;
import sh2.sh2.drc.Ow2DrcOptimizer;

import java.util.HashMap;
import java.util.Map;

import static sh2.S32xUtil.CpuDeviceAccess.MASTER;
import static sh2.S32xUtil.CpuDeviceAccess.SLAVE;
import static sh2.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface SysEventManager {

    SysEventManager instance = new SysEventManagerImpl();

    Ow2DrcOptimizer.PollerCtx[] currentPollers = {NO_POLLER, NO_POLLER};

    enum SysEvent {
        NONE,
        INT,
        SYS,
        SDRAM,
        COMM,
        DMA,
        PWM,
        VDP,
        START_POLLING;
    }

    void fireSysEvent(CpuDeviceAccess cpu, SysEvent event);

    boolean addSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l);

    boolean removeSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l);

    default boolean addSysEventListener(String name, SysEventListener l) {
        addSysEventListener(MASTER, name, l);
        addSysEventListener(SLAVE, name, l);
        return true;
    }

    default int removeSysEventListener(String name, SysEventListener l) {
        int num = 0;
        boolean s1 = removeSysEventListener(MASTER, name, l);
        boolean s2 = removeSysEventListener(SLAVE, name, l);
        num += (s1 ? 1 : 0) + (s2 ? 1 : 0);
        return num;
    }

    interface SysEventListener {
        void onSysEvent(CpuDeviceAccess cpu, SysEvent event);
    }

    class SysEventManagerImpl implements SysEventManager {

        private Map<String, SysEventListener> listenerMapMaster = new HashMap<>();
        private Map<String, SysEventListener> listenerMapSlave = new HashMap<>();

        @Override
        public boolean addSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.put(name, l);
            assert s == null;
            return true;
        }

        @Override
        public boolean removeSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.remove(name);
            return s != null;
        }

        @Override
        public void fireSysEvent(CpuDeviceAccess cpu, SysEvent event) {
            var map = cpu == MASTER ? listenerMapMaster : listenerMapSlave;
            for (var e : map.entrySet()) {
                e.getValue().onSysEvent(cpu, event);
            }
        }
    }
}
