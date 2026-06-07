package net.rainbowcreation.vocanicz.minegit.mod.command.permission.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric installer for the {@link MineGitPermissions} seam: routes each gate through
 * fabric-permissions-api, whose 3-arg {@code check} returns "granted node OR op level >= fallback".
 * A permissions backend (LuckPerms) supplies grants; with none installed it degrades to op-level,
 * matching the seam's default. Called once from {@code MineGitFabric#onInitialize}.
 *
 * <p><b>Compatibility guard.</b> fabric-permissions-api builds are compiled against a specific
 * Minecraft intermediary; a build mismatched with the running Minecraft throws a {@link LinkageError}
 * (e.g. {@code NoClassDefFoundError}) from inside {@code Permissions.check}. Because the check runs
 * while the server builds each player's command tree on join, an unguarded throw crashes the join.
 * The checker therefore catches the first {@code LinkageError}, logs once, and permanently falls back
 * to vanilla op-level — so the mod stays usable (op can run everything) even on a Minecraft version
 * the installed permissions API predates. LuckPerms node grants are unavailable in that degraded
 * state until a matching fabric-permissions-api is supplied.
 */
public final class MineGitPermissionsImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger("MineGit");

    /** Set once if fabric-permissions-api is incompatible with this Minecraft build (see class doc). */
    private static volatile boolean permissionsApiBroken = false;

    private MineGitPermissionsImpl() {
    }

    public static void install() {
        MineGitPermissions.setChecker(MineGitPermissionsImpl::check);
    }

    private static boolean check(CommandSourceStack source, String node, int level) {
        if (!permissionsApiBroken) {
            try {
                return Permissions.check(source, node, level);
            } catch (LinkageError incompatible) {
                permissionsApiBroken = true;
                LOGGER.error(
                        "fabric-permissions-api is incompatible with this Minecraft build; MineGit "
                                + "permission checks now degrade to vanilla op-level (LuckPerms grants "
                                + "are ignored until a matching fabric-permissions-api is installed).",
                        incompatible);
            }
        }
        // Vanilla op-level fallback, mirroring the seam's default OP_LEVEL_ONLY checker. Every MineGit
        // gate uses op fallback level 2 (LEVEL_GAMEMASTERS), the only level the seam maps.
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }
}
