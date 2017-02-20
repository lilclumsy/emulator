package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import eu.rekawek.coffeegb.memory.Ram;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace {

    public enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace videoRam0;

    private final AddressSpace videoRam1;

    private final AddressSpace oamRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private final Dma dma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final HBlankPhase hBlankPhase;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

    private final VBlankPhase vBlankPhase;

    private boolean lcdEnabled = true;

    private int lcdEnabledDelay;

    private MemoryRegisters r;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    private boolean modeIrqRequested;

    private boolean lineIrqRequested;

    public Gpu(Display display, InterruptManager interruptManager, Dma dma, Ram oamRam, boolean gbc) {
        this.r = new MemoryRegisters(GpuRegister.values());
        this.lcdc = new Lcdc();
        this.interruptManager = interruptManager;
        this.gbc = gbc;
        this.videoRam0 = new Ram(0x8000, 0x2000);
        if (gbc) {
            this.videoRam1 = new Ram(0x8000, 0x2000);
        } else {
            this.videoRam1 = null;
        }
        this.oamRam = oamRam;
        this.dma = dma;

        this.bgPalette = new ColorPalette(0xff68);
        this.oamPalette = new ColorPalette(0xff6a);
        oamPalette.fillWithFF();

        this.oamSearchPhase = new OamSearch(oamRam, lcdc, r);
        this.pixelTransferPhase = new PixelTransfer(videoRam0, videoRam1, oamRam, display, lcdc, r, gbc, bgPalette, oamPalette);
        this.hBlankPhase = new HBlankPhase();
        this.vBlankPhase = new VBlankPhase();

        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();

        this.display = display;
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address) && mode != Mode.PixelTransfer) {
            if (gbc && (r.get(VBK) & 1) == 1) {
                return videoRam1;
            } else {
                return videoRam0;
            }
        } else if (oamRam.accepts(address) && !dma.isOamBlocked() && mode != Mode.OamSearch && mode != Mode.PixelTransfer) {
            return oamRam;
        } else if (lcdc.accepts(address)) {
            return lcdc;
        } else if (r.accepts(address)) {
            return r;
        } else if (gbc && bgPalette.accepts(address)) {
            return bgPalette;
        } else if (gbc && oamPalette.accepts(address)) {
            return oamPalette;
        } else {
            return null;
        }
    }

    @Override
    public boolean accepts(int address) {
        return getAddressSpace(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == STAT.getAddress()) {
            setStat(value);
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == lcdc) {
                setLcdc(value);
            } else if (space != null) {
                space.setByte(address, value);
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address == STAT.getAddress()) {
            return getStat();
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == null) {
                return 0xff;
            } else if (address == VBK.getAddress()) {
                return gbc ? 0xfe : 0xff;
            } else {
                return space.getByte(address);
            }
        }
    }

    public Mode tick() {
        if (irqUpdateRequested) {
            updateIrqState();
            irqUpdateRequested = false;
        }
        if (!lcdEnabled) {
            if (lcdEnabledDelay != -1) {
                if (--lcdEnabledDelay == 0) {
                    display.enableLcd();
                    lcdEnabled = true;
                }
            }
        }
        if (!lcdEnabled) {
            return null;
        }

        Mode oldMode = mode;
        ticksInLine++;
        if (phase.tick()) {
            // switch line 153 to 0
            if (ticksInLine == 4 && mode == Mode.VBlank && r.get(LY) == 153) {
                r.put(LY, 0);
                onLineChanged();
            }
        } else {
            switch (oldMode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = pixelTransferPhase.start(oamSearchPhase.getSprites());
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = hBlankPhase.start(ticksInLine);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 144) {
                        mode = Mode.VBlank;
                        phase = vBlankPhase.start();
                        interruptManager.requestInterrupt(InterruptType.VBlank);
                    } else {
                        mode = Mode.OamSearch;
                        phase = oamSearchPhase.start();
                    }
                    onLineChanged();
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 1) {
                        mode = Mode.OamSearch;
                        r.put(LY, 0);
                        phase = oamSearchPhase.start();
                        updateIrqState();
                        interruptManager.clearInterrupt(InterruptType.VBlank);
                    } else {
                        phase = vBlankPhase.start();
                    }
                    onLineChanged();
                    break;
            }
        }
        if (oldMode == mode) {
            return null;
        } else {
            onModeChanged();
            return mode;
        }
    }

    public int getTicksInLine() {
        return ticksInLine;
    }

    private boolean irqUpdateRequested;

    private void onModeChanged() {
        int stat = r.get(STAT);
        boolean i = false;
        if ((stat & (1 << 3)) != 0) {
            i = i || mode == Mode.HBlank;
        }
        if ((stat & (1 << 4)) != 0) {
            i = i || mode == Mode.VBlank;
        }
        if ((stat & (1 << 5)) != 0) {
            i = i || mode == Mode.OamSearch;
        }
        if ((stat & (1 << 5)) != 0) {
            i = i || (mode == Mode.VBlank && r.get(LY) == 144);
        }
        if (i && !modeIrqRequested) {
            modeIrqRequested = true;
            irqUpdateRequested = true;
        } else if (!i && modeIrqRequested) {
            modeIrqRequested = false;
            irqUpdateRequested = true;
        }
    }

    private void onLineChanged() {
        boolean coincidence = (r.get(STAT) & (1 << 6)) != 0 && r.get(LYC) == r.get(LY);
        if (coincidence && !lineIrqRequested) {
            lineIrqRequested = true;
            irqUpdateRequested = true;
        } else if (!coincidence && lineIrqRequested) {
            lineIrqRequested = false;
            irqUpdateRequested = true;
        }
    }

    private void updateIrqState() {
        if (modeIrqRequested || lineIrqRequested) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        } else {
            interruptManager.clearInterrupt(InterruptType.LCDC);
        }
    }

    private int getStat() {
        return r.get(STAT) | mode.ordinal() | (r.get(LYC) == r.get(LY) ? (1 << 2) : 0) | 0x80;
    }

    private void setStat(int value) {
        int oldState = r.get(STAT);
        r.put(STAT, value & 0b11111000); // last three bits are read-only
        if (((oldState ^ value) & (1 << 3)) != 0 && mode == Mode.HBlank) {
            onModeChanged();
        }
        if (((oldState ^ value) & (1 << 4)) != 0 && mode == Mode.VBlank) {
            onModeChanged();
        }
        if (((oldState ^ value) & (1 << 5)) != 0 && mode == Mode.OamSearch) {
            onModeChanged();
        }
        if (((oldState ^ value) & (1 << 6)) != 0 && r.get(LYC) == r.get(LY)) {
            onLineChanged();
        }
    }

    private void setLcdc(int value) {
        lcdc.set(value);
        if ((value & (1 << 7)) == 0) {
            disableLcd();
        } else {
            enableLcd();
        }
    }

    private void disableLcd() {
        r.put(LY, 0);
        this.ticksInLine = 0;
        this.phase = hBlankPhase.start(250);
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.lcdEnabledDelay = -1;
        display.disableLcd();
    }

    private void enableLcd() {
        lcdEnabledDelay = 244;
    }

    public boolean isLcdEnabled() {
        return lcdEnabled;
    }

    public Lcdc getLcdc() {
        return lcdc;
    }

    public AddressSpace getOamRam() {
        return oamRam;
    }

}
