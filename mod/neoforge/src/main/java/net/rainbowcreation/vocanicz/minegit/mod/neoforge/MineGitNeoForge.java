package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.MineGitMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;

/**
 * NeoForge loader entrypoint — declared in {@code neoforge.mods.toml}. Delegates to shared init.
 *
 * <p>Lives in the loader-specific {@code net.rainbowcreation.vocanicz.minegit.mod.neoforge} package (not {@code
 * net.rainbowcreation.vocanicz.minegit.mod}) so it never shares a package with the common module: NeoForge's strict JPMS
 * module layer rejects two modules exporting the same package, which a split would trigger in dev.
 */
@Mod(MineGitInfo.MOD_ID)
public final class MineGitNeoForge {

    public MineGitNeoForge(IEventBus modEventBus, Dist dist) {
        // Install the permission checker + register the node-gather listener BEFORE shared init, so the
        // /minegit gates resolve through PermissionAPI from the first command registration (parity 2026-06-07).
        MineGitNeoForgePermissions.install();
        NeoForge.EVENT_BUS.addListener(
                PermissionGatherEvent.Nodes.class, MineGitNeoForgePermissions::onGatherNodes);
        MineGitMod.init();
        // Register the minegit:diff opaque-byte payload (type + S2C handler) on the mod bus (issue #77).
        MineGitNeoForgeNetworking.register(modEventBus);
        // Client-distribution init seam: only on the physical client, so the dedicated server never
        // classloads client-only overlay types (issue #77).
        if (dist.isClient()) {
            MineGitNeoForgeClient.init(modEventBus);
        }
        // GameTest registration (issue #64); the event only fires when GameTest is enabled.
        MineGitNeoForgeGameTest.register(modEventBus);
    }
}
