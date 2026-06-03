package com.minegit.core.git;

import java.util.Objects;

/**
 * Identity recorded as the <em>author</em> of a MineGit commit — the player whose action triggered
 * the snapshot. The <em>committer</em> is always the fixed {@code MineGit <minegit@local>} identity
 * (see {@link MineGitRepo}); only the author varies per commit.
 *
 * <p>No Minecraft dependencies.
 */
public final class Author {

    private final String name;
    private final String email;

    public Author(String name, String email) {
        this.name = Objects.requireNonNull(name, "name");
        this.email = Objects.requireNonNull(email, "email");
    }

    /**
     * Convenience for a player whose only known identity is a display name; synthesizes a stable
     * placeholder email {@code <name>@players.minegit.local}.
     */
    public static Author of(String name) {
        Objects.requireNonNull(name, "name");
        return new Author(name, name + "@players.minegit.local");
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Author)) {
            return false;
        }
        Author that = (Author) o;
        return name.equals(that.name) && email.equals(that.email);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + email.hashCode();
    }

    @Override
    public String toString() {
        return name + " <" + email + ">";
    }
}
