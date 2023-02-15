package s32x.event;

import omegadrive.Device;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import s32x.bus.Sh2Bus;
import s32x.sh2.drc.Ow2DrcOptimizer;
import s32x.sh2.drc.Ow2DrcOptimizer.PollerCtx;
import s32x.util.Md32xRuntimeData;
import s32x.util.S32xUtil;
import s32x.util.S32xUtil.CpuDeviceAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static omegadrive.util.Util.th;
import static s32x.sh2.drc.Ow2DrcOptimizer.NO_POLLER;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public interface PollSysEventManager extends Device {

    Logger LOG = LogHelper.getLogger(PollSysEventManager.class.getSimpleName());

    PollSysEventManager instance = new SysEventManagerImpl();

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

    void fireSysEvent(CpuDeviceAccess cpu, SysEvent event);

    boolean addSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l);

    boolean removeSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l);

    default boolean addSysEventListener(String name, SysEventListener l) {
        addSysEventListener(CpuDeviceAccess.MASTER, name, l);
        addSysEventListener(CpuDeviceAccess.SLAVE, name, l);
        return true;
    }

    default int removeSysEventListener(String name, SysEventListener l) {
        int num = 0;
        boolean s1 = removeSysEventListener(CpuDeviceAccess.MASTER, name, l);
        boolean s2 = removeSysEventListener(CpuDeviceAccess.SLAVE, name, l);
        num += (s1 ? 1 : 0) + (s2 ? 1 : 0);
        return num;
    }

    default void resetPoller(CpuDeviceAccess cpu) {
        PollerCtx pctx = PollSysEventManager.currentPollers[cpu.ordinal()];
        if (pctx != NO_POLLER) {
            pctx.stopPolling();
            PollSysEventManager.currentPollers[cpu.ordinal()] = NO_POLLER;
            pollerActiveMask.set(pollerActiveMask.get() & ~(cpu.ordinal() + 1));
        }
    }

    default void setPoller(CpuDeviceAccess cpu, PollerCtx ctx) {
        assert PollSysEventManager.currentPollers[cpu.ordinal()] == NO_POLLER;
        PollSysEventManager.currentPollers[cpu.ordinal()] = ctx;
        pollerActiveMask.set(pollerActiveMask.get() | (cpu.ordinal() + 1));
    }

    default PollerCtx getPoller(CpuDeviceAccess cpu) {
        return PollSysEventManager.currentPollers[cpu.ordinal()];
    }

    default int anyPollerActive() {
        return pollerActiveMask.get();
    }

    static int readPollValue(PollerCtx blockPoller) {
        if (blockPoller.isPollingBusyLoop()) {
            return 0;
        }
        //VF (Japan, USA) (Beta) (1995-06-15)
        //A block gets invalidated while it is polling, when polling ends we access the invalid_block
        if (!blockPoller.piw.block.isValid()) {
            LOG.warn("Unexpected state, block is invalid?\n{}", blockPoller);
            return 0;
        }
        Sh2Bus memory = blockPoller.piw.block.drcContext.memory;
        //TODO this should not disturb the cache...
        int delay = Md32xRuntimeData.getCpuDelayExt();
        int val = memory.read(blockPoller.blockPollData.memLoadTarget, blockPoller.blockPollData.memLoadTargetSize);
        Md32xRuntimeData.resetCpuDelayExt(delay);
        return val;
    }

    static boolean pollValueCheck(S32xUtil.CpuDeviceAccess cpu, PollSysEventManager.SysEvent event, Ow2DrcOptimizer.PollerCtx pctx) {
        if (event == PollSysEventManager.SysEvent.INT) {
            return true;
        }
        assert Md32xRuntimeData.getCpuDelayExt(cpu) == 0;
        int value = readPollValue(pctx);
        if (value == pctx.pollValue) {
            System.out.println("?? Poll stop but value unchanged: " + th(pctx.pollValue) + "," + th(value));
        }
        assert value != pctx.pollValue;
        return true;
    }

    interface SysEventListener {
        void onSysEvent(CpuDeviceAccess cpu, SysEvent event);
    }

    class SysEventManagerImpl implements PollSysEventManager {

        private final Map<String, SysEventListener> listenerMapMaster = new HashMap<>();
        private final Map<String, SysEventListener> listenerMapSlave = new HashMap<>();

        @Override
        public boolean addSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.put(name, l);
            assert s == null;
            return true;
        }

        @Override
        public boolean removeSysEventListener(CpuDeviceAccess cpu, String name, SysEventListener l) {
            var map = cpu == CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
            SysEventListener s = map.remove(name);
            return s != null;
        }

        @Override
        public void fireSysEvent(CpuDeviceAccess cpu, SysEvent event) {
            var map = cpu == CpuDeviceAccess.MASTER ? listenerMapMaster : listenerMapSlave;
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
