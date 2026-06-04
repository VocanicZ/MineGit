package com.minegit.mod.neoforge;

import com.minegit.mod.MineGitInfo;
import com.minegit.mod.MineGitMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge loader entrypoint — declared in {@code neoforge.mods.toml}. Delegates to shared init.
 *
 * <p>Lives in the loader-specific {@code com.minegit.mod.neoforge} package (not {@code
 * com.minegit.mod}) so it never shares a package with the common module: NeoForge's strict JPMS
 * module layer rejects two modules exporting the same package, which a split would trigger in dev.
 */
@Mod(MineGitInfo.MOD_ID)
public final class MineGitNeoForge {

    public MineGitNeoForge(IEventBus modEventBus) {
        MineGitMod.init();
        // GameTest registration (issue #64); the event only fires when GameTest is enabled.
        MineGitNeoForgeGameTest.register(modEventBus);
    }
}
