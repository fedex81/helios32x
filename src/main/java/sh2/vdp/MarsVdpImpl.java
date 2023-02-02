package sh2.vdp;

import omegadrive.util.LogHelper;
import omegadrive.util.Size;
import omegadrive.util.VideoMode;
import omegadrive.vdp.util.UpdatableViewer;
import omegadrive.vdp.util.VdpDebugView;
import org.slf4j.Logger;
import sh2.Md32xRuntimeData;
import sh2.S32XMMREG;
import sh2.S32xUtil;
import sh2.dict.S32xDict;
import sh2.dict.S32xMemAccessDelay;
import sh2.sh2.prefetch.Sh2Prefetch;
import sh2.vdp.debug.MarsVdpDebugView;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Optional;

import static omegadrive.util.Util.th;
import static sh2.S32XMMREG.RegContext;
import static sh2.S32xUtil.*;
import static sh2.dict.S32xDict.*;
import static sh2.dict.S32xDict.RegSpecS32x.*;
import static sh2.sh2.device.IntControl.Sh2Interrupt.HINT_10;
import static sh2.sh2.device.IntControl.Sh2Interrupt.VINT_12;
import static sh2.vdp.MarsVdp.VdpPriority.MD;
import static sh2.vdp.MarsVdp.VdpPriority.S32X;

/**
 * Federico Berti
 * <p>
 * Copyright 2021
 * <p>
 * TODO H32 is broken, S32x should keep drawing as H40, while the md layer's H32 should be stretched to H40
 */
public class MarsVdpImpl implements MarsVdp {

    private static final Logger LOG = LogHelper.getLogger(MarsVdpImpl.class.getSimpleName());

    private final ByteBuffer colorPalette = ByteBuffer.allocateDirect(SIZE_32X_COLPAL);
    private final ByteBuffer[] dramBanks = new ByteBuffer[2];

    private final ShortBuffer[] frameBuffersWord = new ShortBuffer[NUM_FB];
    private final ShortBuffer colorPaletteWords = colorPalette.asShortBuffer();
    private final short[] fbDataWords = new short[DRAM_SIZE >> 1];
    private final int[] lineTableWords = new int[LINE_TABLE_WORDS];

    private ByteBuffer vdpRegs;
    private MarsVdpDebugView view;
    private MarsVdpContext vdpContext;
    private MarsVdpRenderContext renderContext;
    private S32XMMREG s32XMMREG;
    private RegContext regContext;

    private int[] buffer;

    //0 - pal, 1 - NTSC
    private int pal = 1;
    //0 = palette access disabled, 1 = enabled
    private int pen = 1;

    private boolean wasBlankScreen = false;
    private static final boolean verbose = false, verboseRead = false;

    static {
        MarsVdp.initBgrMapper();
    }

    //for testing
    public static MarsVdp createInstance(MarsVdpContext vdpContext,
                                         ShortBuffer frameBuffer0, ShortBuffer frameBuffer1, ShortBuffer colorPalette) {
        MarsVdpImpl v = (MarsVdpImpl) createInstance(vdpContext, new S32XMMREG());
        v.colorPaletteWords.put(colorPalette);
        v.frameBuffersWord[0].put(frameBuffer0);
        v.frameBuffersWord[1].put(frameBuffer1);
        return v;
    }

    public static MarsVdp createInstance(MarsVdpContext vdpContext, S32XMMREG s32XMMREG) {
        MarsVdpImpl v = new MarsVdpImpl();
        v.s32XMMREG = s32XMMREG;
        v.regContext = s32XMMREG.regContext;
        v.vdpRegs = v.regContext.vdpRegs;
        v.dramBanks[0] = ByteBuffer.allocateDirect(DRAM_SIZE);
        v.dramBanks[1] = ByteBuffer.allocateDirect(DRAM_SIZE);
        v.frameBuffersWord[0] = v.dramBanks[0].asShortBuffer();
        v.frameBuffersWord[1] = v.dramBanks[1].asShortBuffer();
        v.view = MarsVdpDebugView.createInstance();
        v.vdpContext = vdpContext;
        v.renderContext = new MarsVdpRenderContext();
        v.renderContext.screen = v.buffer;
        v.renderContext.vdpContext = vdpContext;
        v.updateVideoModeInternal(vdpContext.videoMode);
        v.init();
        return v;
    }

    @Override
    public void init() {
        writeBufferWord(VDP_BITMAP_MODE, pal * P32XV_PAL);
        writeBufferWord(FBCR, (vdpContext.vBlankOn ? 1 : 0) * P32XV_VBLK | (pen * P32XV_PEN));
    }

    @Override
    public void write(int address, int value, Size size) {
        if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            assert Md32xRuntimeData.getAccessTypeExt() != CpuDeviceAccess.Z80;
            switch (size) {
                case WORD, LONG -> writeBuffer(colorPalette, address & S32X_COLPAL_MASK, value, size);
                default ->
                        LOG.error(Md32xRuntimeData.getAccessTypeExt() + " write, unable to access colorPalette as " + size);
            }
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.PALETTE);
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            if (size == Size.BYTE && value == 0) { //value =0 on byte is ignored
                return;
            }
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, size);
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.FRAME_BUFFER);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            //see Space Harrier, brutal, doom resurrection
            writeFrameBufferOver(address, value, size);
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.FRAME_BUFFER);
        } else {
            LOG.error("{} unhandled write at {}, val: {} {}", Md32xRuntimeData.getAccessTypeExt(), th(address),
                    th(value), size);
        }
    }

    @Override
    public int read(int address, Size size) {
        int res = 0;
        if (address >= START_32X_COLPAL_CACHE && address < END_32X_COLPAL_CACHE) {
            assert Md32xRuntimeData.getAccessTypeExt() != CpuDeviceAccess.Z80;
            if (size == Size.WORD) {
                res = readBuffer(colorPalette, address & S32X_COLPAL_MASK, size);
            } else {
                LOG.error(Md32xRuntimeData.getAccessTypeExt() + " read, unable to access colorPalette as " + size);
            }
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.PALETTE);
        } else if (address >= START_DRAM_CACHE && address < END_DRAM_CACHE) {
            res = readBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, size);
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.FRAME_BUFFER);
        } else if (address >= START_OVER_IMAGE_CACHE && address < END_OVER_IMAGE_CACHE) {
            res = readBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, size);
            S32xMemAccessDelay.addWriteCpuDelay(S32xMemAccessDelay.FRAME_BUFFER);
        } else {
            LOG.error("{} unhandled read: {} {}", Md32xRuntimeData.getAccessTypeExt(), th(address), size);
        }
        return res;
    }

    @Override
    public boolean vdpRegWrite(S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        switch (size) {
            case WORD:
            case BYTE:
                return handleVdpRegWriteInternal(regSpec, reg, value, size);
            case LONG:
                S32xDict.RegSpecS32x regSpec2 = getRegSpec(regSpec.regCpuType, regSpec.fullAddress + 2);
                boolean res = handleVdpRegWriteInternal(regSpec, reg, value >> 16, Size.WORD);
                res |= handleVdpRegWriteInternal(regSpec2, reg + 2, value & 0xFFFF, Size.WORD);
                return res;
        }
        return false;
    }

    private boolean handleVdpRegWriteInternal(S32xDict.RegSpecS32x regSpec, int reg, int value, Size size) {
        assert size != Size.LONG : regSpec;
        if (size == Size.BYTE && (reg & 1) == 0) {
            LOG.warn("{} even byte write: {} {}", regSpec, th(value), size);
            return false;
        }
        boolean regChanged = false;
        switch (regSpec) {
            case VDP_BITMAP_MODE:
                regChanged = handleBitmapModeWrite(reg, value, size);
                break;
            case FBCR:
                regChanged = handleFBCRWrite(reg, value, size);
                break;
            case AFDR:
                assert size == Size.WORD;
                runAutoFill(value);
                regChanged = true;
                break;
            case SSCR:
                value &= 1;
                //fall-through
            case AFLR:
                value &= 0xFF;
                //fall-through
            default:
                int res = readBufferReg(regContext, regSpec, reg, size);
                if (res != value) {
                    writeBufferReg(regContext, regSpec, reg, value, size);
                    regChanged = true;
                }
                break;
        }
        return regChanged;
    }

    private boolean handleBitmapModeWrite(int reg, int value, Size size) {
        //NOTE: golf, writes on even byte
        int val = readWordFromBuffer(VDP_BITMAP_MODE);
        int prevPrio = (val >> 7) & 1;
        writeBufferReg(regContext, VDP_BITMAP_MODE, reg, value, size);
        int newVal = readWordFromBuffer(VDP_BITMAP_MODE) & ~(P32XV_PAL | P32XV_240);
        int v240 = pal == 0 && vdpContext.videoMode.isV30() ? 1 : 0;
        newVal = (newVal & 0xC3) | (pal * P32XV_PAL) | (v240 * P32XV_240);
        writeBufferWord(VDP_BITMAP_MODE, newVal);
        vdpContext.bitmapMode = BitmapMode.vals[newVal & 3];
        int prio = (newVal >> 7) & 1;
        if (prevPrio != prio) {
            vdpContext.priority = prio == 0 ? MD : S32X;
            if (verbose) LOG.info("Vdp priority: {} -> {}", prevPrio == 0 ? "MD" : "32x", vdpContext.priority);
            if (!vdpContext.vBlankOn) { //vf does this but I think it is harmless
                LOG.warn("Illegal Vdp priority change outside VBlank: {} -> {}", prevPrio == 0 ? "MD" : "32x",
                        vdpContext.priority);
            }
        }
        return val != newVal;
    }

    private boolean handleFBCRWrite(int reg, int value, Size size) {
        int val = readWordFromBuffer(FBCR);
        writeBufferReg(regContext, FBCR, reg, value, size);
        //vblank, hblank, pen -> readonly
        int val1 = (val & 0xE000) | (readWordFromBuffer(FBCR) & 3);
        int regVal = 0;
        if (vdpContext.vBlankOn || vdpContext.bitmapMode == BitmapMode.BLANK) {
            regVal = (val & 0xFFFC) | (val1 & 3);
            updateFrameBuffer(regVal);
        } else {
            //during display the register always shows the current frameBuffer being displayed
            regVal = (val & 0xFFFD) | (val1 & 2);
        }
        writeBufferWord(FBCR, regVal);
        vdpContext.fsLatch = val1 & 1;
        assert (regVal & 0x1FFC) == 0;
//            System.out.println("###### FBCR write: D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
        return val != regVal;
    }

    private void updateFrameBuffer(int val) {
        vdpContext.frameBufferDisplay = val & 1;
        vdpContext.frameBufferWritable = (vdpContext.frameBufferDisplay + 1) & 1;
    }

    private void runAutoFill(int data) {
        writeBufferWord(AFDR, data);
        int startAddr = readWordFromBuffer(AFSAR);
        int len = readWordFromBuffer(AFLR) & 0xFF;
        runAutoFillInternal(dramBanks[vdpContext.frameBufferWritable], startAddr, data, len);
    }

    //for testing
    public void runAutoFillInternal(ByteBuffer buffer, int startAddrWord, int data, int len) {
        int wordAddrFixed = startAddrWord & 0xFF00;
        int wordAddrVariable = startAddrWord & 0xFF;
        if (verbose) LOG.info("AutoFill startWord {}, len(word) {}, data {}", th(startAddrWord), th(len), th(data));
        final int dataWord = data & 0xFFFF;
        int afsarEnd = wordAddrFixed + (len & 0xFF);
//        assert ((startAddrWord + len) & 0xFF) >= wordAddrVariable;
        do {
            //TODO this should trigger an invalidate on framebuf mem?
            //TODO anyone executing code from the framebuffer?
            writeBuffer(buffer, (wordAddrFixed + wordAddrVariable) << 1, dataWord, Size.WORD);
            if (verbose) LOG.info("AutoFill addr(word): {}, addr(byte): {}, len(word) {}, data(word) {}",
                    th(wordAddrFixed + wordAddrVariable), th((wordAddrFixed + wordAddrVariable) << 1), th(len), th(dataWord));
            wordAddrVariable = (wordAddrVariable + 1) & 0xFF;
            len--;
        } while (len >= 0);
        assert len == -1;
        writeBufferWord(AFSAR, wordAddrFixed + wordAddrVariable); //star wars arcade
        if (verbose)
            LOG.info("AutoFill done, startWord {}, AFSAR {}, data: {}", th(startAddrWord), th(afsarEnd), th(dataWord));
        vdpRegChange(AFSAR);
    }

    private void setPen(int pen) {
        this.pen = pen;
        int val = (pen << 5) | (readBufferByte(vdpRegs, FBCR.addr) & 0xDF);
        writeBuffer(vdpRegs, FBCR.addr, val, Size.BYTE);
    }

    public void setVBlank(boolean vBlankOn) {
        vdpContext.vBlankOn = vBlankOn;
        setBitFromWord(FBCR, FBCR_VBLK_BIT_POS, vBlankOn ? 1 : 0);
        if (vBlankOn) {
            vdpContext.screenShift = readWordFromBuffer(SSCR) & 1;
            draw(vdpContext);
            int currentFb = readWordFromBuffer(FBCR) & 1;
            if (currentFb != vdpContext.fsLatch) {
                setBitFromWord(FBCR, FBCR_FRAMESEL_BIT_POS, vdpContext.fsLatch);
                updateFrameBuffer(vdpContext.fsLatch);
//                System.out.println("##### VBLANK, D" + frameBufferDisplay + "W" + frameBufferWritable + ", fsLatch: " + fsLatch + ", VB: " + vBlankOn);
            }
        }
        setPen(vdpContext.hBlankOn || vBlankOn ? 1 : 0);
        vdpRegChange(FBCR);
        s32XMMREG.interruptControls[0].setIntPending(VINT_12, vBlankOn);
        s32XMMREG.interruptControls[1].setIntPending(VINT_12, vBlankOn);
//        System.out.println("VBlank: " + vBlankOn);
    }

    public void setHBlank(boolean hBlankOn, int hen) {
        vdpContext.hBlankOn = hBlankOn;
        setBitFromWord(FBCR, FBCR_HBLK_BIT_POS, hBlankOn ? 1 : 0);
        //TODO hack, FEN =0 after 40 cycles @ 23Mhz
        setBitFromWord(FBCR, FBCR_nFEN_BIT_POS, hBlankOn ? 1 : 0);
        boolean hintOn = false;
        if (hBlankOn) {
            if (hen > 0 || !vdpContext.vBlankOn) {
                if (--vdpContext.hCount < 0) {
                    vdpContext.hCount = readWordFromBuffer(SH2_HCOUNT_REG) & 0xFF;
                    hintOn = true;
                }
            } else {
                vdpContext.hCount = readWordFromBuffer(SH2_HCOUNT_REG) & 0xFF;
            }
        }
        s32XMMREG.interruptControls[0].setIntPending(HINT_10, hintOn);
        s32XMMREG.interruptControls[1].setIntPending(HINT_10, hintOn);
        setPen(hBlankOn || vdpContext.vBlankOn ? 1 : 0);
        //TODO check if any poller is testing the HBlank byte
        vdpRegChange(FBCR);
//        System.out.println("HBlank: " + hBlankOn);
    }

    private void vdpRegChange(RegSpecS32x regSpec) {
        assert regSpec.size == Size.WORD;
        int val = readWordFromBuffer(regSpec); //TODO avoid the read?
        int addr = SH2_CACHE_THROUGH_OFFSET | START_32X_SYSREG_CACHE | regSpec.fullAddress;
        assert getRegSpec(S32xRegCpuType.REG_MD, addr) == getRegSpec(S32xRegCpuType.REG_SH2, addr);
        Sh2Prefetch.checkPollersVdp(regSpec.deviceType, addr, val, Size.WORD);
    }

    private void writeBufferWord(RegSpecS32x reg, int value) {
        writeBufferReg(regContext, reg, reg.addr, value, Size.WORD);
    }

    private int readWordFromBuffer(RegSpecS32x reg) {
        return S32xUtil.readWordFromBuffer(regContext, reg);
    }

    private void setBitFromWord(RegSpecS32x reg, int pos, int value) {
        S32xUtil.setBit(vdpRegs, reg.addr & S32X_VDP_REG_MASK, pos, value, Size.WORD);
    }

    private void writeFrameBufferOver(int address, int value, Size size) {
        if (value == 0) {
            return;
        }
        switch (size) {
            case WORD -> {
                writeFrameBufferByte(address, (value >> 8) & 0xFF);
                writeFrameBufferByte(address + 1, value & 0xFF);
            }
            case BYTE ->
                //guaranteed not be zero
                    writeFrameBufferByte(address, value);
            case LONG -> {
//                LOG.error("Unexpected writeFrameBufferOver: {}", size);
                writeFrameBufferByte(address, (value >> 24) & 0xFF);
                writeFrameBufferByte(address + 1, (value >> 16) & 0xFF);
                writeFrameBufferByte(address + 2, (value >> 8) & 0xFF);
                writeFrameBufferByte(address + 3, (value >> 0) & 0xFF);
            }
        }
    }

    private void writeFrameBufferByte(int address, int value) {
        if (value != 0) {
            writeBuffer(dramBanks[vdpContext.frameBufferWritable], address & DRAM_MASK, value, Size.BYTE);
        }
    }

    @Override
    public void draw(MarsVdpContext context) {
        switch (context.bitmapMode) {
            case BLANK -> drawBlank();
            case PACKED_PX -> drawPackedPixel(context);
            case RUN_LEN -> drawRunLen(context);
            case DIRECT_COL -> drawDirectColor(context);
        }
        view.update(context, buffer);
    }

    private void drawBlank() {
        if (wasBlankScreen) {
            return;
        }
        Arrays.fill(buffer, 0, buffer.length, 0);
        wasBlankScreen = true;
    }

    //Mars Sample Program - Pharaoh
    //space harrier intro screen
    private void drawDirectColor(MarsVdpContext context) {
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);
        b.position(0);
        b.get(fbDataWords);
        final short[] fb = fbDataWords;
//        final int centerDcHalfShift = w * ((256 - context.videoMode.getDimension().height) >> 1);

        for (int row = 0; row < DIRECT_COLOR_LINES; row++) {
            final int linePos = lineTableWords[row] + context.screenShift;
            final int fbBasePos = row * w;
            for (int col = 0; col < w; col++) {
                imgData[fbBasePos + col] = getDirectColorWithPriority(fb[linePos + col] & 0xFFFF);
            }
        }
        wasBlankScreen = false;
    }

    //space harrier sega intro
    private void drawRunLen(MarsVdpContext context) {
        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);
        b.position(0);
        b.get(fbDataWords);

        for (int row = 0; row < h; row++) {
            int col = 0;
            final int basePos = row * w;
            final int linePos = lineTableWords[row];
            int nextWord = linePos;
            if (basePos >= fbDataWords.length) {
                break;
            }
            do {
                int rl = fbDataWords[nextWord++];
                int dotColorIdx = rl & 0xFF;
                int dotLen = ((rl & 0xFF00) >> 8) + 1;
                int nextLimit = col + dotLen;
                int color = getColorWithPriority(dotColorIdx);
                for (; col < nextLimit; col++) {
                    imgData[basePos + col] = color;
                }
            } while (col < w);
        }
        wasBlankScreen = false;
    }

    //32X Sample Program - Celtic - PWM Test
    void drawPackedPixel(MarsVdpContext context) {
        final ShortBuffer b = frameBuffersWord[context.frameBufferDisplay];
        final int[] imgData = buffer;
        populateLineTable(b);

        b.position(0);
        b.get(fbDataWords);

        final int h = context.videoMode.getDimension().height;
        final int w = context.videoMode.getDimension().width;

        for (int row = 0; row < h; row++) {
            final int linePos = lineTableWords[row] + context.screenShift;
            final int basePos = row * w;
            for (int col = 0, wordOffset = 0; col < w; col += 2, wordOffset++) {
                final int palWordIdx1 = (fbDataWords[linePos + wordOffset] >> 8) & 0xFF;
                final int palWordIdx2 = fbDataWords[linePos + wordOffset] & 0xFF;
                imgData[basePos + col] = getColorWithPriority(palWordIdx1);
                imgData[basePos + col + 1] = getColorWithPriority(palWordIdx2);
            }
        }
        wasBlankScreen = false;
    }

    @Override
    public int[] doCompositeRendering(int[] mdData, MarsVdpRenderContext ctx) {
        int[] out = doCompositeRenderingExt(mdData, ctx);
        view.updateFinalImage(out);
        return out;
    }

    public static int[] doCompositeRenderingExt(int[] mdData, MarsVdpRenderContext ctx) {
        int mdDataLen = mdData.length;
        final int[] marsData = Optional.ofNullable(ctx.screen).orElse(EMPTY_INT_ARRAY);
        int[] out = mdData;
        if (mdDataLen == marsData.length) {
            final boolean prio32x = ctx.vdpContext.priority == S32X;
            final boolean s32xRegBlank = ctx.vdpContext.bitmapMode == BitmapMode.BLANK;
            final boolean s32xBgBlank = !prio32x && s32xRegBlank;
            final boolean s32xFgBlank = prio32x && s32xRegBlank;
            final int[] fg = prio32x ? marsData : mdData;
            final int[] bg = prio32x ? mdData : marsData;
            for (int i = 0; i < fg.length; i++) {
                boolean throughBit = (marsData[i] & 1) > 0;
                boolean mdBlanking = (mdData[i] & 1) > 0;
                boolean bgBlanking = (prio32x && mdBlanking) || s32xBgBlank;
                boolean fgBlanking = (!prio32x && mdBlanking) || s32xFgBlank;
                fg[i] = (fgBlanking && !bgBlanking) || (throughBit && !bgBlanking) ? bg[i] : fg[i];
            }
            out = fg;
        }
        return out;
    }

    private void populateLineTable(final ShortBuffer b) {
        b.position(0);
        for (int i = 0; i < lineTableWords.length; i++) {
            lineTableWords[i] = b.get() & 0xFFFF;
        }
    }

    //NOTE: encodes priority as the LSB (bit) of the word
    private int getColorWithPriority(int palWordIdx) {
        int palValue = colorPaletteWords.get(palWordIdx) & 0xFFFF;
        return getDirectColorWithPriority(palValue);
    }

    private int getDirectColorWithPriority(int palValue) {
        int prio = (palValue >> 15) & 1;
        int color = bgr5toRgb8Mapper[palValue];
        return (color & ~1) | prio;
    }

    public void updateVdpBitmapMode(VideoMode video) {
        pal = video.isPal() ? 0 : 1;
        int v240 = video.isPal() && video.isV30() ? 1 : 0;
        int val = readWordFromBuffer(VDP_BITMAP_MODE) & ~(P32XV_PAL | P32XV_240);
        writeBufferWord(VDP_BITMAP_MODE, val | (pal * P32XV_PAL) | (v240 * P32XV_240));
    }

    @Override
    public void updateVideoMode(VideoMode videoMode) {
        if (videoMode.equals(vdpContext.videoMode)) {
            return;
        }
        updateVdpBitmapMode(videoMode);
        updateVideoModeInternal(videoMode);
        vdpContext.videoMode = videoMode;
    }

    private void updateVideoModeInternal(VideoMode videoMode) {
        this.buffer = new int[videoMode.getDimension().width * videoMode.getDimension().height];
        renderContext.screen = buffer;
        LOG.info("Updating videoMode, {} -> {}", vdpContext.videoMode, videoMode);
    }

    @Override
    public MarsVdpRenderContext getMarsVdpRenderContext() {
        return renderContext;
    }

    @Override
    public void updateDebugView(UpdatableViewer debugView) {
        if (debugView instanceof VdpDebugView) {
            ((VdpDebugView) debugView).setAdditionalPanel(view.getPanel());
        }
    }

    @Override
    public void dumpMarsData() {
        DebugMarsVdpRenderContext d = new DebugMarsVdpRenderContext();
        d.renderContext = getMarsVdpRenderContext();
        frameBuffersWord[0].position(0);
        frameBuffersWord[1].position(0);
        d.frameBuffer0 = new short[frameBuffersWord[0].capacity()];
        d.frameBuffer1 = new short[frameBuffersWord[1].capacity()];
        frameBuffersWord[0].get(d.frameBuffer0);
        frameBuffersWord[1].get(d.frameBuffer1);

        colorPaletteWords.position(0);
        d.palette = new short[colorPaletteWords.capacity()];
        colorPaletteWords.get(d.palette);
        //NOTE needs to redraw as the buffer and the context might be out of sync
        draw(d.renderContext.vdpContext);
        d.renderContext.screen = buffer;
        MarsVdp.storeMarsData(d);
    }

    @Override
    public void reset() {
        view.reset();
    }
}
