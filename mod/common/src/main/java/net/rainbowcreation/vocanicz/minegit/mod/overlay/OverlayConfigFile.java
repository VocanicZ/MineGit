package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Reads the client overlay config (Spec C §3.1, issue #81) from a plain {@code key=value} properties
 * file under the loader's config dir. Pure {@code java.nio} + {@link OverlayConfig} — <b>no Minecraft
 * imports</b> — so the file ↔ config glue is exercised headless in JUnit; the loader entrypoints only
 * supply the {@link Path}.
 *
 * <p>Behavior is deliberately forgiving: a missing file writes a commented default template (so a
 * player has something to edit) and returns {@link OverlayConfig#defaults()}; an unreadable or
 * malformed file logs nothing and falls back to defaults. Per-key parse/clamp rules live in
 * {@link OverlayConfig#fromProperties}; this class never validates values itself.
 */
public final class OverlayConfigFile {

    /** The config file name the loader entrypoints resolve under the config dir. */
    public static final String FILE_NAME = "minegit-overlay.properties";

    private OverlayConfigFile() {
    }

    /**
     * Loads the overlay config from {@code path}. If the file is absent, writes a default template
     * and returns {@link OverlayConfig#defaults()}; if it cannot be read/parsed, returns defaults
     * without throwing.
     *
     * @param path the config file path (typically {@code <configDir>/}{@value #FILE_NAME})
     * @return the parsed config, or the defaults on any miss/error
     */
    public static OverlayConfig load(Path path) {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            writeTemplate(path);
            return OverlayConfig.defaults();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            return OverlayConfig.fromProperties(toMap(props));
        } catch (IOException | RuntimeException e) {
            return OverlayConfig.defaults();
        }
    }

    private static Map<String, String> toMap(Properties props) {
        Map<String, String> map = new HashMap<String, String>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        return map;
    }

    /** Best-effort write of the commented default template; a failure here is silently ignored. */
    private static void writeTemplate(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(template());
            }
        } catch (IOException | RuntimeException e) {
            // The config dir may be read-only or racing another writer — defaults still apply.
        }
    }

    /** The default template: every key set to its spec default, each documented with a comment. */
    static String template() {
        OverlayConfig d = OverlayConfig.defaults();
        StringBuilder sb = new StringBuilder();
        sb.append("# MineGit client diff-overlay config (Spec C 3.1).\n");
        sb.append("# Missing or unparseable keys fall back to these defaults.\n");
        sb.append("\n");
        sb.append("# Toggle key (GLFW key name); also rebindable via vanilla Controls.\n");
        sb.append("keybind=").append(d.getKeybind()).append("\n");
        sb.append("\n");
        sb.append("# Cull radius in blocks; boxes farther than this are not drawn.\n");
        sb.append("maxRenderDistance=").append(asInt(d.getMaxRenderDistance())).append("\n");
        sb.append("\n");
        sb.append("# Max boxes drawn per frame (nearest first); the rest count as \"(+J more)\".\n");
        sb.append("renderCap=").append(d.getRenderCap()).append("\n");
        sb.append("\n");
        sb.append("# Overlay auto-clear timeout in seconds; 0 disables the timer.\n");
        sb.append("autoExpireSeconds=").append(d.getAutoExpireSeconds()).append("\n");
        sb.append("\n");
        sb.append("# HUD anchor: top-left | top-right | bottom-left | bottom-right.\n");
        sb.append("hudCorner=").append(d.getHudCorner().name().toLowerCase().replace('_', '-')).append("\n");
        return sb.toString();
    }

    /** Renders a whole-valued double without a trailing {@code .0} so the template reads cleanly. */
    private static String asInt(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
