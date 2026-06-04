package com.minegit.mod;

import net.neoforged.fml.common.Mod;

/** NeoForge loader entrypoint — declared in {@code neoforge.mods.toml}. Delegates to shared init. */
@Mod(MineGitInfo.MOD_ID)
public final class MineGitNeoForge {

    public MineGitNeoForge() {
        MineGitMod.init();
    }
}
