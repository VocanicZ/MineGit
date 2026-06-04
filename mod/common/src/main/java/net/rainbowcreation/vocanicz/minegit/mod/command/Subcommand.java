package net.rainbowcreation.vocanicz.minegit.mod.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /minegit} subcommand registry (Spec D §4, issue #60): the loader-agnostic catalogue of
 * subcommand literals and the permission level each requires. Driving both Brigadier registration
 * ({@link MineGitCommands}) and tab-completion off one ordered enum keeps the two in lock-step and
 * makes the gating decision unit-testable without a live server.
 *
 * <p>This catalogue ships {@code init}, {@code status}, {@code commit}, {@code log} and {@code diff}
 * at permission level 0, since Spec D §4 makes read <em>and commit</em> available to any player. The
 * world-mutating {@code checkout} (issue #63) gates at {@link #OP_PERMISSION_LEVEL} (vanilla op,
 * level 2) so only operators can revert a build live.
 */
public enum Subcommand {
    INIT("init", 0),
    STATUS("status", 0),
    COMMIT("commit", 0),
    LOG("log", 0),
    DIFF("diff", 0),
    CHECKOUT("checkout", 2); // == OP_PERMISSION_LEVEL; inlined — enum consts can't forward-ref a field

    /**
     * Vanilla op permission level. The world-mutating {@code checkout} subcommand gates here; the
     * read/setup/commit set stays at level 0.
     */
    public static final int OP_PERMISSION_LEVEL = 2;

    private final String literal;
    private final int permissionLevel;

    Subcommand(String literal, int permissionLevel) {
        this.literal = literal;
        this.permissionLevel = permissionLevel;
    }

    /** The subcommand literal as typed after {@code /mg} (e.g. {@code "status"}). */
    public String literal() {
        return literal;
    }

    /** The {@code CommandSourceStack.hasPermission} level required to run this subcommand. */
    public int permissionLevel() {
        return permissionLevel;
    }

    /** Every subcommand literal in registration/display order. */
    public static List<String> literals() {
        List<String> out = new ArrayList<String>();
        for (Subcommand sub : values()) {
            out.add(sub.literal);
        }
        return Collections.unmodifiableList(out);
    }

    /** The subcommand with literal {@code name} (case-insensitive), or {@code null} if unknown. */
    public static Subcommand byLiteral(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Subcommand sub : values()) {
            if (sub.literal.equals(lower)) {
                return sub;
            }
        }
        return null;
    }
}
