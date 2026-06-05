package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The render→HUD bridge for the cap's dropped count (issue #80). The world-render hook computes
 * {@code visibleBoxes(...).getDropped()} per frame against the live camera; the HUD, which renders in
 * a separate event, reads it here to print {@code (+J more)}. A single {@code volatile int} — no
 * logic, so it stays out of the unit-tested {@link OverlayClientState}.
 *
 * <p>Client-only: only the render/HUD glue touch it. Reset to {@code 0} whenever nothing is drawn.
 */
@Environment(EnvType.CLIENT)
public final class OverlayRenderStats {

    private static volatile int lastDropped;

    private OverlayRenderStats() {
    }

    /** Records how many in-range boxes the cap dropped on the most recent render. */
    public static void setLastDropped(int dropped) {
        lastDropped = Math.max(0, dropped);
    }

    /** The cap-dropped count from the most recent render — the HUD's {@code (+J more)}. */
    public static int lastDropped() {
        return lastDropped;
    }
}
