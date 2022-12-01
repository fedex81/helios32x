package sh2.sh2.prefetch;

import omegadrive.cpu.CpuFastDebug;
import omegadrive.util.LogHelper;
import org.slf4j.Logger;
import sh2.S32xUtil;
import sh2.sh2.Sh2Helper;
import sh2.sh2.drc.Sh2Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static omegadrive.util.Util.th;
import static sh2.dict.S32xDict.SH2_PC_AREA_SHIFT;
import static sh2.sh2.Sh2Helper.SH2_NOT_VISITED;

/**
 * Federico Berti
 * <p>
 * Copyright 2022
 */
public class PrefetchUtil {

    private static final Logger LOG = LogHelper.getLogger(PrefetchUtil.class.getSimpleName());

    public static void logPcHits(S32xUtil.CpuDeviceAccess cpu) {
        Map<CpuFastDebug.PcInfoWrapper, Long> hitMap = new HashMap<>();
        long top10 = 10;
        Sh2Helper.Sh2PcInfoWrapper[][] pcInfoWrapper = Sh2Helper.getPcInfoWrapper();
        for (int i = 0; i < pcInfoWrapper.length; i++) {
            for (int j = 0; j < pcInfoWrapper[i].length; j++) {
                Sh2Helper.Sh2PcInfoWrapper piw = pcInfoWrapper[i][j | cpu.ordinal()];
                if (piw != SH2_NOT_VISITED) {
                    if (piw.block.hits < top10) {
                        continue;
                    }
                    hitMap.put(piw, Long.valueOf(piw.block.hits));
                    top10 = hitMap.values().stream().sorted().limit(10).findFirst().orElse(10L);
//                        LOG.info("{} PC: {} hits: {}, {}", cpu, th(pc), piw.hits, piw);
                }
            }
        }
        List<Map.Entry<CpuFastDebug.PcInfoWrapper, Long>> l = hitMap.entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).
                limit(10).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        l.forEach(e -> {
            CpuFastDebug.PcInfoWrapper piw = e.getKey();
            int pc = (piw.area << SH2_PC_AREA_SHIFT) | piw.pcMasked;
            //TODO fix
            Sh2Block res = Sh2Block.INVALID_BLOCK;
//            Sh2Block res = prefetchMap[cpu.ordinal()].getOrDefault(piw, Sh2Block.INVALID_BLOCK);
            //TODO fix
            assert res != Sh2Block.INVALID_BLOCK;
            sb.append(cpu + " " + th(pc) + "," + e.getValue() + ", block: " +
                    th(res.prefetchPc) + "," + res.pollType + "," + res.hits + "\n" + Sh2Helper.toListOfInst(res)).append("\n");

        });
//            String s = hitMap.entrySet().stream().sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).
//                    limit(10).map(Objects::toString).collect(Collectors.joining("\n"));
        LOG.info(sb.toString());
    }
}
