package net.rainbowcreation.vocanicz.minegit.core.git;

import java.util.Objects;

/**
 * Immutable summary of a single MineGit commit, as surfaced by {@link MineGitRepo#log()}.
 *
 * <ul>
 *   <li>{@code id} — the full 40-hex git commit SHA-1.
 *   <li>{@code author} — the author's display name (the player who triggered the snapshot).
 *   <li>{@code message} — the commit message.
 *   <li>{@code epochSeconds} — the commit time in seconds since the Unix epoch (UTC).
 * </ul>
 *
 * <p>No Minecraft dependencies.
 */
public final class CommitInfo {

    private final String id;
    private final String author;
    private final String message;
    private final long epochSeconds;

    public CommitInfo(String id, String author, String message, long epochSeconds) {
        this.id = Objects.requireNonNull(id, "id");
        this.author = Objects.requireNonNull(author, "author");
        this.message = Objects.requireNonNull(message, "message");
        this.epochSeconds = epochSeconds;
    }

    public String getId() {
        return id;
    }

    public String getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    public long getEpochSeconds() {
        return epochSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitInfo)) {
            return false;
        }
        CommitInfo that = (CommitInfo) o;
        return epochSeconds == that.epochSeconds
            && id.equals(that.id)
            && author.equals(that.author)
            && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, author, message, epochSeconds);
    }

    @Override
    public String toString() {
        return "CommitInfo(" + id + ", author=" + author + ", message=" + message
            + ", epochSeconds=" + epochSeconds + ")";
    }
}
