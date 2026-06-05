package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.Map;
import java.util.Objects;

/**
 * The client overlay's tunables (Spec C §3.1). Pure data with defaults and clamping — no Minecraft or
 * file-system imports — so the parse/clamp rules are unit-tested headless. The render/HUD/keybind glue
 * reads an instance; a missing key falls back to its default.
 *
 * <p>This batch (issue #80) needs the values to flow through the lifecycle (notably the
 * <b>configurable auto-expire timer</b>); the on-disk config file format + watcher is issue #6, which
 * will call {@link #fromProperties} with the parsed file. {@link #defaults()} is what the client uses
 * until then.
 */
public final class OverlayConfig {

    /** HUD anchor corner. */
    public enum HudCorner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        static HudCorner parse(String raw, HudCorner fallback) {
            if (raw == null) {
                return fallback;
            }
            String norm = raw.trim().toUpperCase().replace('-', '_');
            for (HudCorner c : values()) {
                if (c.name().equals(norm)) {
                    return c;
                }
            }
            return fallback;
        }
    }

    /** Default toggle key token (the GLFW name the loader maps; {@code J} per spec). */
    public static final String DEFAULT_KEYBIND = "J";
    public static final double DEFAULT_MAX_RENDER_DISTANCE = 64.0;
    public static final int DEFAULT_RENDER_CAP = 4096;
    public static final int DEFAULT_AUTO_EXPIRE_SECONDS = 60;
    public static final HudCorner DEFAULT_HUD_CORNER = HudCorner.TOP_LEFT;

    /** Ticks per second — the auto-expire timer is configured in seconds, applied in ticks. */
    public static final int TICKS_PER_SECOND = 20;

    private final String keybind;
    private final double maxRenderDistance;
    private final int renderCap;
    private final int autoExpireSeconds;
    private final HudCorner hudCorner;

    public OverlayConfig(
            String keybind,
            double maxRenderDistance,
            int renderCap,
            int autoExpireSeconds,
            HudCorner hudCorner) {
        this.keybind = Objects.requireNonNull(keybind, "keybind");
        // maxRenderDistance < 0 is meaningless (cull radius); clamp to 0 (draws nothing).
        this.maxRenderDistance = Math.max(0.0, maxRenderDistance);
        // renderCap < 0 would throw in visibleBoxes; clamp to 0.
        this.renderCap = Math.max(0, renderCap);
        // autoExpireSeconds < 0 collapses to 0 (= timer disabled), matching the spec's "0 disables".
        this.autoExpireSeconds = Math.max(0, autoExpireSeconds);
        this.hudCorner = Objects.requireNonNull(hudCorner, "hudCorner");
    }

    /** The spec defaults — what the client uses until issue #6 loads a config file. */
    public static OverlayConfig defaults() {
        return new OverlayConfig(
                DEFAULT_KEYBIND,
                DEFAULT_MAX_RENDER_DISTANCE,
                DEFAULT_RENDER_CAP,
                DEFAULT_AUTO_EXPIRE_SECONDS,
                DEFAULT_HUD_CORNER);
    }

    /**
     * Builds a config from raw string keys, falling back to the default for any missing or
     * unparseable value (Spec C §3.1: "falls back to defaults for any missing key"). Recognized keys:
     * {@code keybind}, {@code maxRenderDistance}, {@code renderCap}, {@code autoExpireSeconds},
     * {@code hudCorner}.
     */
    public static OverlayConfig fromProperties(Map<String, String> props) {
        Objects.requireNonNull(props, "props");
        return new OverlayConfig(
                props.getOrDefault("keybind", DEFAULT_KEYBIND),
                parseDouble(props.get("maxRenderDistance"), DEFAULT_MAX_RENDER_DISTANCE),
                parseInt(props.get("renderCap"), DEFAULT_RENDER_CAP),
                parseInt(props.get("autoExpireSeconds"), DEFAULT_AUTO_EXPIRE_SECONDS),
                HudCorner.parse(props.get("hudCorner"), DEFAULT_HUD_CORNER));
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getKeybind() {
        return keybind;
    }

    public double getMaxRenderDistance() {
        return maxRenderDistance;
    }

    public int getRenderCap() {
        return renderCap;
    }

    public int getAutoExpireSeconds() {
        return autoExpireSeconds;
    }

    public HudCorner getHudCorner() {
        return hudCorner;
    }

    /**
     * The auto-expire timer in ticks (the unit {@link OverlayState#isExpired} / {@code tickExpiry}
     * use). {@code 0} when the timer is disabled.
     */
    public long lifetimeTicks() {
        return (long) autoExpireSeconds * TICKS_PER_SECOND;
    }
}
