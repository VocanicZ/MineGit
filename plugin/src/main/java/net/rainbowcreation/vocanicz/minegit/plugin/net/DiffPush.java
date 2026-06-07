package net.rainbowcreation.vocanicz.minegit.plugin.net;

import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Framing;
import net.rainbowcreation.vocanicz.minegit.protocol.Protocol;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and sends diff-overlay wire frames over {@link Protocol#DIFF_CHANNEL}.
 *
 * <p>{@link #frames} is pure and headless-testable: it encodes a {@link WorldDiff} into a
 * {@link DiffPayload} blob, splits it via {@link Framing} and returns the serialized frame bytes
 * ready to send. {@link #push} is the thin Bukkit send layer — covered by the manual integration
 * matrix, not unit tests.
 *
 * <p>Ref tags match those used by the mod's {@code LiveSubscriptionLoop}: {@code "HEAD"} and
 * {@code "WORKING"}, so the client's diff renderer produces identical output regardless of whether
 * the push originates from the mod or from this plugin.
 */
public final class DiffPush {

    /**
     * The {@code fromRef} tag applied to every encoded diff — matches the mod's
     * {@code LiveSubscriptionLoop} ({@code DiffOverlaySender.send(player, diff, "HEAD", "WORKING", sink)}).
     */
    public static final String FROM_REF = "HEAD";

    /**
     * The {@code toRef} tag applied to every encoded diff — matches the mod's
     * {@code LiveSubscriptionLoop}.
     */
    public static final String TO_REF = "WORKING";

    private DiffPush() {}

    /**
     * Encodes {@code diff} (tagged {@link #FROM_REF}/{@link #TO_REF}) and splits the resulting
     * {@link DiffPayload} blob into wire frame byte arrays ready for
     * {@link Player#sendPluginMessage}. Pure — no Bukkit runtime required.
     *
     * @param diff the world diff to encode; must not be null
     * @return ordered list of serialized {@link Frame} byte arrays (at least one)
     */
    public static List<byte[]> frames(WorldDiff diff) {
        byte[] payload = DiffPayload.encode(diff, FROM_REF, TO_REF);
        List<Frame> framed = Framing.frame(payload, Framing.DEFAULT_MAX_FRAME_BYTES);
        List<byte[]> out = new ArrayList<byte[]>(framed.size());
        for (Frame f : framed) {
            out.add(f.toBytes());
        }
        return out;
    }

    /**
     * Encodes {@code diff} and sends every wire frame to {@code player} over
     * {@link Protocol#DIFF_CHANNEL}. Thin Bukkit send — covered by the manual integration matrix,
     * not unit tests.
     *
     * @param plugin the owning plugin (required by the Bukkit plugin-message API)
     * @param player the recipient
     * @param diff   the world diff to push
     */
    public static void push(Plugin plugin, Player player, WorldDiff diff) {
        for (byte[] frame : frames(diff)) {
            player.sendPluginMessage(plugin, Protocol.DIFF_CHANNEL, frame);
        }
    }
}
