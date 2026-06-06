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

    /**
     * Client→server control channel for the live diff overlay subscription (Spec C batch 2 §2.1).
     * Carries a single {@link DiffControl} byte ({@code 0 = UNSUBSCRIBE}, {@code 1 = SUBSCRIBE}); the
     * keybind drives SUBSCRIBE/UNSUBSCRIBE and the server pushes live working-vs-HEAD over
     * {@link #DIFF_CHANNEL} in response.
     */
    public static final String DIFF_CONTROL_CHANNEL = "minegit:diffsub";

    private Protocol() {
    }
}
