package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;

/**
 * NeoForge installer for the {@link MineGitPermissions} seam (permission parity 2026-06-07). Declares
 * a BOOL {@link PermissionNode} for {@code minegit.use} and {@code minegit.admin}; each node's default
 * resolver returns the vanilla op fallback (op level 2) when no backend has set it. A permissions
 * backend (LuckPerms) overrides per player. The checker routes player sources through
 * {@code PermissionAPI}; console / RCON / command blocks fall back to op-level via the source.
 */
public final class MineGitNeoForgePermissions {

    private static final PermissionNode<Boolean> USE = boolNode("use");
    private static final PermissionNode<Boolean> ADMIN = boolNode("admin");

    private MineGitNeoForgePermissions() {
    }

    private static PermissionNode<Boolean> boolNode(String path) {
        return new PermissionNode<>(
                Identifier.fromNamespaceAndPath(MineGitInfo.MOD_ID, path),
                PermissionTypes.BOOLEAN,
                // Default when unset by a backend: op level 2 (the seam's locked-by-default fallback).
                (player, playerUUID, context) -> player != null
                        && Commands.LEVEL_GAMEMASTERS.check(player.permissions()));
    }

    /** Adds the two nodes to the gather event. Wired to {@code PermissionGatherEvent.Nodes}. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(USE, ADMIN);
    }

    /** Installs the PermissionAPI-backed checker over the common seam. Called from the mod constructor. */
    public static void install() {
        MineGitPermissions.setChecker(MineGitNeoForgePermissions::allowed);
    }

    private static boolean allowed(CommandSourceStack source, String node, int level) {
        PermissionNode<Boolean> permNode = permNodeFor(node);
        if (source.getEntity() instanceof ServerPlayer player) {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, permNode));
        }
        // Console / RCON / command block: no player to resolve a node for — fall back to op level.
        return Commands.LEVEL_GAMEMASTERS.check(source.permissions());
    }

    /**
     * The {@link PermissionNode} for a seam node string. MineGit only ever passes {@code minegit.use}
     * or {@code minegit.admin} (from {@code Subcommand.node()}); an unrecognized string is a bug, so we
     * throw loudly rather than silently under-restricting to the {@code use} node — matching the seam's
     * own throw-on-unsupported-level stance.
     */
    private static PermissionNode<Boolean> permNodeFor(String node) {
        if ("minegit.use".equals(node)) {
            return USE;
        }
        if ("minegit.admin".equals(node)) {
            return ADMIN;
        }
        throw new IllegalArgumentException("unknown MineGit permission node: " + node);
    }
}
