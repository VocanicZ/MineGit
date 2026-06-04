package net.rainbowcreation.vocanicz.minegit.core.git;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link MineGitRepo#push(Credential)} — the <strong>per-ref</strong> status of every ref
 * the push attempted to update. MineGit collapses JGit's fine-grained
 * {@link org.eclipse.jgit.transport.RemoteRefUpdate.Status} set into three caller-facing outcomes
 * ({@link Status}): the push either advanced the remote ref ({@link Status#OK OK}), found it already
 * current ({@link Status#UP_TO_DATE UP_TO_DATE}), or was refused — typically a non-fast-forward —
 * ({@link Status#REJECTED REJECTED}).
 *
 * <p>Immutable. No Minecraft dependencies.
 */
public final class PushResult {

    /** Caller-facing outcome for a single ref update. */
    public enum Status {
        /** The remote ref was advanced to the pushed commit. */
        OK,
        /** The remote ref already pointed at the pushed commit; nothing changed. */
        UP_TO_DATE,
        /** The push was refused (e.g. a non-fast-forward) and the remote ref is unchanged. */
        REJECTED
    }

    /** The {@link Status} of one ref the push touched, with its full remote ref name. */
    public static final class RefUpdate {

        private final String remoteRef;
        private final Status status;
        private final String message;

        public RefUpdate(String remoteRef, Status status, String message) {
            this.remoteRef = Objects.requireNonNull(remoteRef, "remoteRef");
            this.status = Objects.requireNonNull(status, "status");
            this.message = message;
        }

        /** The full remote ref name, e.g. {@code "refs/heads/master"}. */
        public String getRemoteRef() {
            return remoteRef;
        }

        /** The collapsed outcome for this ref. */
        public Status getStatus() {
            return status;
        }

        /** The transport's human-readable detail for a rejection, or {@code null} if none. */
        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return remoteRef + " -> " + status + (message == null ? "" : " (" + message + ")");
        }
    }

    private final List<RefUpdate> updates;

    public PushResult(List<RefUpdate> updates) {
        this.updates =
                Collections.unmodifiableList(
                        new java.util.ArrayList<RefUpdate>(
                                Objects.requireNonNull(updates, "updates")));
    }

    /** Every ref the push attempted to update, in transport order. */
    public List<RefUpdate> getUpdates() {
        return updates;
    }

    /**
     * {@code true} if no ref was {@link Status#REJECTED rejected} — i.e. every update is
     * {@link Status#OK OK} or {@link Status#UP_TO_DATE UP_TO_DATE}.
     */
    public boolean isOk() {
        for (RefUpdate u : updates) {
            if (u.getStatus() == Status.REJECTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "PushResult" + updates;
    }
}
