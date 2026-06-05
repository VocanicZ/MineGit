package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;

/**
 * The translucent box color an overlay draws for each kind of {@link BlockChange}.
 *
 * <p>The mapping is fixed by Spec C's legend: ADD = green, REMOVE = red, CHANGE = yellow. This is
 * pure data — the {@code rgb} value is a render hint the GPU-agnostic core carries so the thin
 * renderer never has to re-derive the legend.
 *
 * <p>No Minecraft render imports: this is part of the headless overlay core.
 */
public enum OverlayColor {

    /** {@link BlockChange.Kind#ADD} — air/absent became solid. */
    GREEN(0x4CAF50),
    /** {@link BlockChange.Kind#REMOVE} — solid became air/absent. */
    RED(0xF44336),
    /** {@link BlockChange.Kind#CHANGE} — one non-air state became a different non-air state. */
    YELLOW(0xFFEB3B);

    private final int rgb;

    OverlayColor(int rgb) {
        this.rgb = rgb;
    }

    /** Packed 0xRRGGBB color, alpha applied by the renderer. */
    public int rgb() {
        return rgb;
    }

    /** The legend color for {@code kind}. */
    public static OverlayColor forKind(BlockChange.Kind kind) {
        switch (kind) {
            case ADD:
                return GREEN;
            case REMOVE:
                return RED;
            case CHANGE:
                return YELLOW;
            default:
                throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }
}
