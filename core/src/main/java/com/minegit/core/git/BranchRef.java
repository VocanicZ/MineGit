package com.minegit.core.git;

import java.util.Objects;

/**
 * Immutable reference to a branch surfaced by {@link MineGitRepo#branch(String)} and
 * {@link MineGitRepo#branches()}.
 *
 * <ul>
 *   <li>{@code name} — the short branch name. For a local branch this is the bare name (e.g.
 *       {@code "master"}); for a remote-tracking branch it is the remote-qualified name (e.g.
 *       {@code "origin/main"}), so the two kinds never collide.
 *   <li>{@code remote} — {@code true} for a remote-tracking ref ({@code refs/remotes/*}),
 *       {@code false} for a local branch ({@code refs/heads/*}).
 * </ul>
 *
 * <p>No Minecraft dependencies.
 */
public final class BranchRef {

    private final String name;
    private final boolean remote;

    public BranchRef(String name, boolean remote) {
        this.name = Objects.requireNonNull(name, "name");
        this.remote = remote;
    }

    /** A local branch ref. */
    public static BranchRef local(String name) {
        return new BranchRef(name, false);
    }

    /** A remote-tracking branch ref (remote-qualified name, e.g. {@code "origin/main"}). */
    public static BranchRef remote(String name) {
        return new BranchRef(name, true);
    }

    public String getName() {
        return name;
    }

    /** {@code true} if this is a remote-tracking branch ({@code refs/remotes/*}). */
    public boolean isRemote() {
        return remote;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BranchRef)) {
            return false;
        }
        BranchRef that = (BranchRef) o;
        return remote == that.remote && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, remote);
    }

    @Override
    public String toString() {
        return "BranchRef(" + name + (remote ? ", remote" : ", local") + ")";
    }
}
