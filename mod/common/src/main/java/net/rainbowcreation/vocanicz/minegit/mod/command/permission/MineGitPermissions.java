package net.rainbowcreation.vocanicz.minegit.mod.command.permission;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;

/**
 * The loader-agnostic command-authorization seam (permission parity, 2026-06-07). A subcommand's
 * Brigadier {@code .requires(...)} gate is built from a grantable permission node plus a vanilla op
 * fallback level; the actual decision is delegated to an installed {@link Checker} so the gating shape
 * stays unit-testable in {@code mod:common} without a live server — mirroring the settable-handler
 * pattern of {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel}.
 *
 * <p>Until a loader installs its node-aware checker at init, the default is pure vanilla op-level:
 * granted iff the source holds the fallback op level. Each loader replaces it — Fabric via
 * fabric-permissions-api, NeoForge via the built-in {@code PermissionAPI} — so a non-op granted
 * {@code minegit.use}/{@code minegit.admin} by a permissions backend can run the matching commands.
 */
public final class MineGitPermissions {

    /** Decides whether {@code source} may run a command gated on {@code node} (op fallback {@code level}). */
    @FunctionalInterface
    public interface Checker {
        /** True iff a backend grants {@code node} to {@code source}, OR {@code source} holds op {@code level}. */
        boolean allowed(CommandSourceStack source, String node, int level);
    }

    /**
     * The default checker: no permission backend, so authorization is pure vanilla op-level. Reuses the
     * 1.21.11 {@link PermissionCheck} machinery the command tree already gates on.
     */
    private static final Checker OP_LEVEL_ONLY =
            (source, node, level) -> Commands.hasPermission(opLevelCheck(level)).test(source);

    /** The current checker. Replace-on-install from each loader's entrypoint; never null. */
    private static volatile Checker checker = OP_LEVEL_ONLY;

    private MineGitPermissions() {
    }

    /** Installs the loader's node-aware checker. Called once from each loader entrypoint at init. */
    public static void setChecker(Checker installed) {
        checker = Objects.requireNonNull(installed, "checker");
    }

    /** Restores the default op-level-only checker. Used by tests and on teardown. */
    public static void resetChecker() {
        checker = OP_LEVEL_ONLY;
    }

    /**
     * The Brigadier requirement for a subcommand gated on {@code node} with op fallback {@code level}.
     * Evaluated per source at parse/execute time, so a checker installed after registration still applies.
     */
    public static Predicate<CommandSourceStack> require(String node, int level) {
        return source -> checker.allowed(source, node, level);
    }

    /** Maps a MineGit op fallback level to the 1.21.11 {@link PermissionCheck}. Only op (2) is used. */
    private static PermissionCheck opLevelCheck(int level) {
        if (level == 2) {
            return Commands.LEVEL_GAMEMASTERS;
        }
        throw new IllegalArgumentException("unsupported MineGit op fallback level: " + level);
    }
}
