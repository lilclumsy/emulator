package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import eu.rekawek.coffeegb.memory.Ram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M0;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M1;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M1_1;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M1_2;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M2;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY00_M2_WND;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY9X_M1;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LY9X_M1_INC;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M0;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M0_2;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M0_INC;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M2;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M2_WND;
import static eu.rekawek.coffeegb.gpu.Gpu.State.GB_LCD_STATE_LYXX_M3;
import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Gpu.class);

    public static enum State {
        GB_LCD_STATE_LYXX_M3(3),
        GB_LCD_STATE_LYXX_M0(0),
        GB_LCD_STATE_LYXX_M0_2(0),
        GB_LCD_STATE_LYXX_M0_INC(0),
        GB_LCD_STATE_LY00_M2(2),
        GB_LCD_STATE_LY00_M2_WND(2),
        GB_LCD_STATE_LYXX_M2(2),
        GB_LCD_STATE_LYXX_M2_WND(2),
        GB_LCD_STATE_LY9X_M1(1),
        GB_LCD_STATE_LY9X_M1_INC(1),
        GB_LCD_STATE_LY00_M1(1),
        GB_LCD_STATE_LY00_M1_1(1),
        GB_LCD_STATE_LY00_M1_2(1),
        GB_LCD_STATE_LY00_M0(0);

        private final int mode;

        State(int mode) {
            this.mode = mode;
        }
    }

    private final Ram videoRam0;

    private final Ram videoRam1;

    private final Ram oamRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private final Dma dma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

    private boolean lcdEnabled = true;

    private MemoryRegisters r;

    private int ticksInLine;

    private int mode;

    private State state;

    private State nextState;

    private GpuPhase phase;

    private boolean phaseInProgress;

    private int stateTicks;

    private int mode3Ticks;

    private boolean vramLocked;

    private boolean oamRamLocked;

    private boolean firstRefresh;

    private int wy;

    private boolean lyLycIncident;

    private boolean lyLycInterrupt;

    private boolean[] modeInterrupt = new boolean[4];

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
        this.display = display;

        this.bgPalette = new ColorPalette(0xff68);
        this.oamPalette = new ColorPalette(0xff6a);
        oamPalette.fillWithFF();

        this.oamSearchPhase = new OamSearch(oamRam, lcdc, r);
        this.pixelTransferPhase = new PixelTransfer(videoRam0, videoRam1, oamRam, display, lcdc, r, gbc, bgPalette, oamPalette);

        this.nextState = GB_LCD_STATE_LY00_M2;
        this.stateTicks = 0;
        this.phaseInProgress = false;
        this.firstRefresh = true;
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address)/* && !vramLocked*/) {
            if (gbc && (r.get(VBK) & 1) == 1) {
                return videoRam1;
            } else {
                return videoRam0;
            }
        } else if (oamRam.accepts(address) && !dma.isOamBlocked() && !oamRamLocked) {
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

    private int scxAdjust, spriteCycles, windowCycles;

    public int tick() {
        boolean modeUpdated = false;

        ticksSinceInterrupt++;
        ticksInLine++;

        if (stateTicks-- > 0) {
            phaseInProgress = phaseInProgress && phase.tick();
            return state.mode;
        } else {
            if (state != null && state.mode == nextState.mode) {
                phaseInProgress = phaseInProgress && phase.tick();
            } else {
                while (phaseInProgress = phaseInProgress && phase.tick());
                modeUpdated = true;
            }
            state = nextState;
            mode = state.mode;
        }

        switch (state) {
            case GB_LCD_STATE_LYXX_M0:
                oamRamLocked = false;
                vramLocked = false;
                nextState = GB_LCD_STATE_LYXX_M0_2;
                stateTicks = 1;
                break;

            case GB_LCD_STATE_LYXX_M0_2:
                checkInterruptForMode(0);
                nextState = GB_LCD_STATE_LYXX_M0_INC;
                stateTicks = 200 - 1 + 3 - scxAdjust - spriteCycles - windowCycles;
                break;

            case GB_LCD_STATE_LYXX_M0_INC:
                r.inc(LY);

                if (r.get(LY) < 144) {
                    clearInterruptForMode(0);
                }
                checkInterruptForMode(2);
                checkLyLycInterrupt();
                if (r.get(LY) == 144) {
                    nextState = GB_LCD_STATE_LY9X_M1;
                    stateTicks = 4;
                } else {
                    nextState = GB_LCD_STATE_LYXX_M2;
                    oamRamLocked = true;
                    stateTicks = 4;
                }
                break;

            case GB_LCD_STATE_LY00_M2:
                oamRamLocked = true;
                clearInterruptForMode(1);
                checkInterruptForMode(2);
                nextState = GB_LCD_STATE_LY00_M2_WND;
                stateTicks = 8;
                break;

            case GB_LCD_STATE_LY00_M2_WND:
                stateTicks = 80 - 8;
                nextState = GB_LCD_STATE_LYXX_M3;
                break;

            case GB_LCD_STATE_LYXX_M2:
                // following are already disabled in GB_LCD_STATE_LYXX_M0_INC
                // clearInterruptForMode(0);
                // oamRamLocked = true;
                setLyLycIncidenceFlag();
                nextState = GB_LCD_STATE_LYXX_M2_WND;
                stateTicks = 8;
                break;

            case GB_LCD_STATE_LYXX_M2_WND:
                stateTicks = 80 - 8;
                nextState = GB_LCD_STATE_LYXX_M3;
                break;

            case GB_LCD_STATE_LYXX_M3:
                clearInterruptForMode(2);
                oamRamLocked = true;
                vramLocked = true;
                nextState = GB_LCD_STATE_LYXX_M0;
                stateTicks = 4 - 3 + 168 + spriteCycles;
                break;

            case GB_LCD_STATE_LY9X_M1:
                // already checked in GB_LCD_STATE_LYXX_M0_INC
                // checkLyLycInterrupt();
                clearInterruptForMode(2);
                clearInterruptForMode(0);
                if (r.get(LY) == 144) {
                    triggerVBlank();
                    checkInterruptForMode(1);
                }
                setLyLycIncidenceFlag();
                nextState = GB_LCD_STATE_LY9X_M1_INC;
                stateTicks = 452;
                break;

            case GB_LCD_STATE_LY9X_M1_INC:
                r.inc(LY);
                clearLyLycIncidenceFlag();
                if (r.get(LY) == 153) {
                    nextState = GB_LCD_STATE_LY00_M1;
                    stateTicks = 4;
                } else {
                    nextState = GB_LCD_STATE_LY9X_M1;
                    stateTicks = 4;
                }
                break;

            case GB_LCD_STATE_LY00_M1:
                setLyLycIncidenceFlag();
                checkLyLycInterrupt();
                r.put(LY, 0);
                nextState = GB_LCD_STATE_LY00_M1_1;
                stateTicks = 4;
                break;

            case GB_LCD_STATE_LY00_M1_1:
                clearLyLycIncidenceFlag();
                nextState = GB_LCD_STATE_LY00_M1_2;
                stateTicks = 4;
                break;

            case GB_LCD_STATE_LY00_M1_2:
                setLyLycIncidenceFlag();
                checkLyLycInterrupt();
                nextState = GB_LCD_STATE_LY00_M0;
                stateTicks = 444;
                break;

            case GB_LCD_STATE_LY00_M0:
                nextState = GB_LCD_STATE_LY00_M2;
                stateTicks = 4;
                break;
        }

        if (modeUpdated) {
            switch (state.mode) {
                case 0:
                    LOG.trace("Phase updated to HBlank");
                    phaseInProgress = false;
                    phase = null;
                    break;

                case 1:
                    LOG.trace("Phase updated to VBlank");
                    phaseInProgress = false;
                    phase = null;
                    break;

                case 2:
                    LOG.trace("Phase updated to OAM search");
                    phaseInProgress = true;
                    phase = oamSearchPhase.start();
                    break;

                case 3:
                    LOG.trace("Phase updated to Pixel transfer");
                    phaseInProgress = true;
                    phase = pixelTransferPhase.start(oamSearchPhase.getSprites());
                    break;
            }
            phaseInProgress = phaseInProgress && phase.tick();
        }
        return state.mode;
    }

    private void triggerVBlank() {
        interruptManager.requestInterrupt(InterruptManager.InterruptType.VBlank);
    }

    int ticksSinceInterrupt;

    private void checkInterruptForMode(int mode) {
        //System.out.println("ticks since last interrupt: " + ticksSinceInterrupt);
        //System.out.println("new interrupt for mode " + mode);
        ticksSinceInterrupt = 0;
        modeInterrupt[mode] = true;
        updateLcdInterrupt();
    }

    private void clearInterruptForMode(int mode) {
        modeInterrupt[mode] = false;
        updateLcdInterrupt();
    }

    private void setLyLycIncidenceFlag() {
        lyLycIncident = r.get(LY) == r.get(LYC);
    }

    private void clearLyLycIncidenceFlag() {
        lyLycIncident = false;
    }

    private void checkLyLycInterrupt() {
        lyLycInterrupt = r.get(LY) == r.get(LYC);
        updateLcdInterrupt();
    }

    private void updateLcdInterrupt() {
        int stat = r.get(STAT);
        boolean lcdIrq = false;
        if (0 != (stat & (1 << 3)) && modeInterrupt[0]) {
            lcdIrq = true;
        }
        if (0 != (stat & (1 << 4)) && modeInterrupt[1]) {
            lcdIrq = true;
        }
        if (0 != (stat & (1 << 5)) && modeInterrupt[2]) {
            lcdIrq = true;
        }
        if (0 != (stat & (1 << 6)) && lyLycInterrupt) {
            lcdIrq = true;
        }
        if (lcdIrq) {
            interruptManager.requestInterrupt(InterruptManager.InterruptType.LCDC);
        } else {
            interruptManager.clearInterrupt(InterruptManager.InterruptType.LCDC);
        }
    }

    public int getTicksInLine() {
        return ticksInLine;
    }

    private int getStat() {
        return r.get(STAT) | mode | (lyLycIncident ? (1 << 2) : 0) | 0x80;
    }

    private void setStat(int value) {
        r.put(STAT, value & 0b11111000); // last three bits are read-only
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
        display.disableLcd();
        lcdEnabled = false;
    }

    private void enableLcd() {
        display.enableLcd();
        lcdEnabled = true;

        r.put(LY, 0);
        this.ticksInLine = 0;

        this.nextState = GB_LCD_STATE_LY00_M2;
        this.stateTicks = 0;
        this.phaseInProgress = false;
        this.firstRefresh = true;
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
