package net.rainbowcreation.vocanicz.minegit.plugin.net;

import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;

import java.util.function.BooleanSupplier;

/**
 * Pure decode + permission decision for an incoming {@code minegit:diffsub} control payload.
 *
 * <p>Mirrors the mod's {@code DiffControlChannel.deliverToServer} malformed-drop contract: a bad
 * packet returns {@link Decision#DROP} and never throws. Bukkit-free and side-effect-free so it
 * can be unit-tested without a running server.
 */
public final class DiffSubDecision {

    /** The result of decoding a {@code minegit:diffsub} payload against a permission check. */
    public enum Decision {
        /** The client is permitted — add to the live overlay registry and start pushing diffs. */
        SUBSCRIBE_PUSH,
        /** The client sent SUBSCRIBE but lacks permission — silently ignore, do not push. */
        IGNORE,
        /** The client sent UNSUBSCRIBE — remove from the registry (no permission gate needed). */
        UNSUBSCRIBE,
        /** The payload was malformed (wrong length or unknown byte) — discard without throwing. */
        DROP
    }

    private DiffSubDecision() {
    }

    /**
     * Decodes {@code bytes} as a {@link DiffControl} wire payload and applies the permission check.
     *
     * <ul>
     *   <li>{@code SUBSCRIBE} + permitted  → {@link Decision#SUBSCRIBE_PUSH}
     *   <li>{@code SUBSCRIBE} + !permitted → {@link Decision#IGNORE}
     *   <li>{@code UNSUBSCRIBE}            → {@link Decision#UNSUBSCRIBE} (no permission gate)
     *   <li>malformed / unknown            → {@link Decision#DROP} (never throws)
     * </ul>
     *
     * @param bytes     the raw payload bytes received on the channel
     * @param permitted supplies {@code true} when the sending player holds the required permission
     * @return the decision for this payload; never {@code null}
     */
    public static Decision decide(byte[] bytes, BooleanSupplier permitted) {
        final DiffControl ctl;
        try {
            ctl = DiffControl.decode(bytes);
        } catch (RuntimeException malformed) {
            // Matches deliverToServer's catch(RuntimeException) — covers both
            // IllegalArgumentException (wrong length / unknown byte) and NullPointerException.
            return Decision.DROP;
        }
        if (ctl == DiffControl.UNSUBSCRIBE) {
            return Decision.UNSUBSCRIBE;
        }
        if (ctl == DiffControl.SUBSCRIBE) {
            return permitted.getAsBoolean() ? Decision.SUBSCRIBE_PUSH : Decision.IGNORE;
        }
        // Defensive: decode() only returns known constants, but guard future enum additions.
        return Decision.DROP;
    }
}
