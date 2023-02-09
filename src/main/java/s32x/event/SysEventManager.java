package s32x.event;

import omegadrive.Device;
import s32x.sh2.drc.Ow2DrcOptimizer.PollerCtx;
import s32x.util.S32xUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static s32x.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface SysEventManager extends Device {

    SysEventManager instance = new SysEventManagerImpl();

    PollerCtx[] currentPollers = {NO_POLLER, NO_POLLER};
    AtomicInteger pollerActiveMask = new AtomicInteger();

    enum SysEvent {
        NONE,
        INT,
        SYS,
        SDRAM,
        FRAMEBUFFER,
        COMM,
        DMA,
        PWM,
        VDP,
        START_POLLING,
        SH2_RESET_ON,
        SH2_RESET_OFF;
    }

    void fireSysEvent(S32xUtil.CpuDeviceAccess cpu, SysEvent event);

    boolean addSysEventListener(S32xUtil.CpuDeviceAccess cpu, String name, SysEventListener l);

    boolean removeSysEventListener(S32xUtil.CpuDeviceAccess cpu, String name, SysEventListener l);

    default boolean addSysEventListener(String name, SysEventListener l) {
        addSysEventListener(S32xUtil.CpuDeviceAccess.MASTER, name, l);
        addSysEventListener(S32xUtil.CpuDeviceAccess.SLAVE, name, l);
        return true;
    }

    default int removeSysEventListener(String name, SysEventListener l) {
        int num = 0;
        boolean s1 = removeSysEventListener(S32xUtil.CpuDeviceAccess.MASTER, name, l);
        boolean s2 = removeSysEventListener(S32xUtil.CpuDeviceAccess.SLAVE, name, l);
        num += (s1 ? 1 : 0) + (s2 ? 1 : 0);
        return num;
    }

    default void resetPoller(S32xUtil.CpuDeviceAccess cpu) {
        PollerCtx pctx = SysEventManager.currentPollers[cpu.ordinal()];
        if (pctx != NO_POLLER) {
            pctx.stopPolling();
            SysEventManager.currentPollers[cpu.ordinal()] = NO_POLLER;
            pollerActiveMask.set(pollerActiveMask.get() & ~(cpu.ordinal() + 1));
        }
    }

    default void setPoller(S32xUtil.CpuDeviceAccess cpu, PollerCtx ctx) {
        assert SysEventManager.currentPollers[cpu.ordinal()] == NO_POLLER;
        SysEventManager.currentPollers[cpu.ordinal()] = ctx;
        pollerActiveMask.set(pollerActiveMask.get() | (cpu.ordinal() + 1));
    }

    default PollerCtx getPoller(S32xUtil.CpuDeviceAccess cpu) {
        return SysEventManager.currentPollers[cpu.ordinal()];
    }

    default int anyPollerActive() {
        return pollerActiveMask.get();
    }

    interface SysEventListener {
        void onSysEvent(S32xUtil.CpuDeviceAccess cpu, SysEvent event);
    }

    class SysEventManagerImpl implements SysEventManager {

        private final Map<String, SysEventListener> listenerMapMaster = new HashMap<>();
        private final Map<String, SysEventListener> listenerMapSlave = new HashMap<>();

        @Override
        public boolean addSysEventListener(S32xUtil.CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == S32xUtil.CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.put(name, l);
            assert s == null;
            return true;
        }

        @Override
        public boolean removeSysEventListener(S32xUtil.CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == S32xUtil.CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.remove(name);
            return s != null;
        }

        @Override
        public void fireSysEvent(S32xUtil.CpuDeviceAccess cpu, SysEvent event) {
            var map = cpu == S32xUtil.CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
            for (var e : map.entrySet()) {
                e.getValue().onSysEvent(cpu, event);
            }
        }

        @Override
        public void reset() {
            listenerMapMaster.clear();
            listenerMapSlave.clear();
            currentPollers[0] = currentPollers[1] = NO_POLLER;
            pollerActiveMask.set(0);
        }
    }
}
