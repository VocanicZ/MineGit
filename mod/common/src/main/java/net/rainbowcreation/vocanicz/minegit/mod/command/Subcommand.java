package net.rainbowcreation.vocanicz.minegit.mod.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /minegit} subcommand registry (Spec D §4, issue #60; permission parity 2026-06-07): the
 * loader-agnostic catalogue of subcommand literals, the grantable permission {@link #node()} each
 * requires, and the vanilla op level each falls back to. Driving Brigadier registration
 * ({@link MineGitCommands}), tab-completion and the gating seam off one ordered enum keeps them in
 * lock-step and makes the gating decision unit-testable without a live server.
 *
 * <p>Locked-by-default: every subcommand requires op <em>or</em> a granted node. {@code minegit.use}
 * covers {@code init}, {@code status}, {@code commit}, {@code log}, {@code diff} and {@code rescan};
 * the world-mutating {@code checkout} (issue #63) requires {@code minegit.admin}. Both fall back to
 * vanilla op (level 2). Mirrors the plugin's node model exactly.
 */
public enum Subcommand {
    INIT("init", "minegit.use", 2),      // 2 == OP_PERMISSION_LEVEL; inlined — enum consts can't forward-ref a field
    STATUS("status", "minegit.use", 2),
    COMMIT("commit", "minegit.use", 2),
    LOG("log", "minegit.use", 2),
    DIFF("diff", "minegit.use", 2),
    CHECKOUT("checkout", "minegit.admin", 2),
    RESCAN("rescan", "minegit.use", 2);

    /**
     * Vanilla op permission level (2). Every subcommand falls back here when no permission backend
     * grants its node — the locked-by-default model: a non-op with no granted node can run nothing.
     */
    public static final int OP_PERMISSION_LEVEL = 2;

    private final String literal;
    private final String node;
    private final int permissionLevel;

    Subcommand(String literal, String node, int permissionLevel) {
        this.literal = literal;
        this.node = node;
        this.permissionLevel = permissionLevel;
    }

    /** The subcommand literal as typed after {@code /mg} (e.g. {@code "status"}). */
    public String literal() {
        return literal;
    }

    /**
     * The grantable permission node this subcommand checks ({@code minegit.use} for read/setup/commit
     * /rescan, {@code minegit.admin} for the world-mutating {@code checkout}). A permission backend
     * (LuckPerms etc.) grants this; absent a grant, {@link #permissionLevel()} is the op fallback.
     */
    public String node() {
        return node;
    }

    /** The vanilla op level required when no permission backend grants {@link #node()} (always op, 2). */
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
