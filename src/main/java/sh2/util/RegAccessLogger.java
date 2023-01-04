package sh2.util;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import org.slf4j.Logger;
import sh2.Md32xRuntimeData;

import java.util.HashMap;
import java.util.Map;

import static omegadrive.util.Util.th;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class RegAccessLogger {
    private static final Logger LOG = LogHelper.getLogger(RegAccessLogger.class.getSimpleName());

    private static final boolean ENABLE = false;

    private static final Map<String, Integer> log = new HashMap<>();

    public static void regAccess(String regSpec, int address, int val, Size size, boolean read) {
        if (!ENABLE) {
            return;
        }
        if (regSpec.startsWith("FRT_") || regSpec.startsWith("FBCR")) {
            return;
        }
        String s = Md32xRuntimeData.getAccessTypeExt() + "," + (read ? "R," : "W,") + regSpec + "," + th(address) + "," + size;
        Integer v = log.get(s);
        if (v == null || v != val) {
            LOG.info(s + "," + th(val));
            log.put(s, val);
        }
    }
}
