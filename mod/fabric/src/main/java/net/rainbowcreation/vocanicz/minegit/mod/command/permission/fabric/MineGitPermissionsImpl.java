package net.rainbowcreation.vocanicz.minegit.mod.command.permission.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;

/**
 * Fabric installer for the {@link MineGitPermissions} seam: routes each gate through
 * fabric-permissions-api, whose 3-arg {@code check} returns "granted node OR op level >= fallback".
 * A permissions backend (LuckPerms) supplies grants; with none installed it degrades to op-level,
 * matching the seam's default. Called once from {@code MineGitFabric#onInitialize}.
 */
public final class MineGitPermissionsImpl {

    private MineGitPermissionsImpl() {
    }

    public static void install() {
        MineGitPermissions.setChecker(
                (source, node, level) -> Permissions.check(source, node, level));
    }
}
