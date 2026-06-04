package net.rainbowcreation.vocanicz.minegit.mod;

import net.fabricmc.api.ModInitializer;

/** Fabric loader entrypoint — declared in {@code fabric.mod.json}. Delegates to the shared init. */
public final class MineGitFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        MineGitMod.init();
    }
}
