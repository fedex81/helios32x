package sh2.event;

import org.junit.jupiter.api.Test;

import static sh2.dict.S32xDict.S32xRegType;
import static sh2.event.SysEventManager.SysEvent;
import static sh2.event.SysEventManager.SysEvent.INT;
import static sh2.event.SysEventManager.SysEvent.START_POLLING;
import static sh2.sh2.drc.Ow2DrcOptimizer.PollType;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class SysEventManagerTest {

    @Test
    public void testSysEventVsPollTypeVsS32xRegType() {
        for (SysEvent e : SysEvent.values()) {
            if (e == START_POLLING || e == INT) {
                continue;
            }
            System.out.println(PollType.valueOf(e.name()));
        }

        for (S32xRegType s : S32xRegType.values()) {
            System.out.println(PollType.valueOf(s.name()));
            System.out.println(SysEvent.valueOf(s.name()));
        }
    }

}
