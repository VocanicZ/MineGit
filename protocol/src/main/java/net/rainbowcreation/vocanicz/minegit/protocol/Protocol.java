package net.rainbowcreation.vocanicz.minegit.protocol;

/**
 * Shared wire-protocol constants between the MineGit server frontends and the diff-visualizing
 * client mod.
 *
 * <p>Skeleton only for batch 1 — the {@code DiffPayload} encode/decode and chunked framing land in
 * batch 2 (see Spec A §11).
 */
public final class Protocol {

    /** Plugin-message / networking channel used to stream block diffs to the client. */
    public static final String DIFF_CHANNEL = "minegit:diff";

    private Protocol() {
    }
}
