package sh2.util;

import com.google.common.collect.ObjectArrays;
import omegadrive.bus.model.GenesisBusProvider;
import omegadrive.memory.ReadableByteMemory;
import omegadrive.vdp.model.VdpMemoryInterface;
import omegadrive.vdp.util.MemView;
import omegadrive.vdp.util.UpdatableViewer;
import omegadrive.vdp.util.VdpDebugView;
import sh2.Md32xRuntimeData;

import java.util.function.BiFunction;

import static omegadrive.vdp.util.MemView.MemViewOwner.M68K;
import static omegadrive.vdp.util.MemView.MemViewOwner.SH2;
import static sh2.dict.S32xDict.*;

/**
 * Federico Berti
 * <p>
 * Copyright 2023
 */
public class S32xMemView extends MemView {

    public static final MemView.MemViewData[] s32xMemViewData =
            ObjectArrays.concat(mdMemViewData, S32xMemViewType.values(), MemViewData.class);

    public static UpdatableViewer createInstance(GenesisBusProvider m, ReadableByteMemory s32x,
                                                 VdpMemoryInterface vdpMem) {
        return VdpDebugView.DEBUG_VIEWER_ENABLED ? new S32xMemView(m, s32x, vdpMem) : NO_MEMVIEW;
    }

    protected S32xMemView(GenesisBusProvider m, ReadableByteMemory s32x, VdpMemoryInterface vdpMem) {
        super(s32xMemViewData, m, s32x, vdpMem);
    }

    enum S32xMemViewType implements MemView.MemViewData {
        S32X_SDRAM(SH2, SH2_START_SDRAM, SH2_END_SDRAM),
        S32X_DRAM(SH2, START_DRAM, END_DRAM),
        S32X_PALETTE(SH2, START_32X_COLPAL, END_32X_COLPAL),
        M68K_ROM(M68K, M68K_START_ROM_MIRROR, M68K_END_ROM_MIRROR), //aden=1, in general
        M68K_VECTOR_ROM(M68K, 0, M68K_END_VECTOR_ROM),
        ;

        public int start, end;
        public MemView.MemViewOwner owner;

        S32xMemViewType(MemViewOwner c, int s, int e) {
            start = s;
            end = e;
            owner = c;
        }

        @Override
        public int getStart() {
            return start;
        }

        @Override
        public int getEnd() {
            return end;
        }

        @Override
        public MemViewOwner getOwner() {
            return owner;
        }
    }

    @Override
    protected void doMemoryRead(MemViewData current, int len, BiFunction<MemViewData, Integer, Integer> readerFn) {
        int v = Md32xRuntimeData.getCpuDelayExt();
        super.doMemoryRead(current, len, readerFn);
        Md32xRuntimeData.resetCpuDelayExt(v);
    }
}
