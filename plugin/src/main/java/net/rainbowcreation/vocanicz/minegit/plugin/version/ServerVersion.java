package net.rainbowcreation.vocanicz.minegit.plugin.version;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed Minecraft server version, detected once at plugin enable.
 *
 * <p>The plugin compiles against the Spigot 1.8.8 API but must run on everything from 1.8 to the
 * latest release. The 1.13 "flattening" changed block representation, so the plugin needs to know,
 * at runtime, which side of that boundary it is on to pick the right {@code BlockBridge}
 * (Spec B §3). This type is intentionally Bukkit-free so it stays unit-testable on a plain JVM —
 * the live plugin feeds it {@code Bukkit.getBukkitVersion()}.
 */
public final class ServerVersion {

    /** First Minecraft minor release of the flattening; 1.13+ is "modern", earlier is "legacy". */
    private static final int FLATTENING_MINOR = 13;

    // Matches the leading "MAJOR.MINOR[.PATCH]" of strings like "1.8.8-R0.1-SNAPSHOT" or "1.21-R0.1-SNAPSHOT".
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private final int major;
    private final int minor;
    private final int patch;

    private ServerVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * Parse the string returned by {@code Bukkit.getBukkitVersion()}, e.g. {@code "1.8.8-R0.1-SNAPSHOT"}.
     *
     * @throws IllegalArgumentException if the string does not start with a {@code MAJOR.MINOR} version
     */
    public static ServerVersion parseBukkitVersion(String bukkitVersion) {
        if (bukkitVersion == null) {
            throw new IllegalArgumentException("bukkitVersion is null");
        }
        Matcher m = VERSION.matcher(bukkitVersion);
        if (!m.find()) {
            throw new IllegalArgumentException("unrecognized server version: '" + bukkitVersion + "'");
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new ServerVersion(major, minor, patch);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** True for pre-1.13 servers (id+meta block model); selects the Legacy block bridge. */
    public boolean isLegacy() {
        if (major != 1) {
            // Defensive: any future "2.x" is unambiguously modern.
            return major < 1;
        }
        return minor < FLATTENING_MINOR;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
