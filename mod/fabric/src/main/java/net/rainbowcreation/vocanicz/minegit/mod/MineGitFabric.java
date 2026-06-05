package net.rainbowcreation.vocanicz.minegit.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/** Fabric loader entrypoint — declared in {@code fabric.mod.json}. Delegates to the shared init. */
public final class MineGitFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        MineGitMod.init();
        // Register the minegit:diff S2C payload type on both dists so the server may send it and the
        // client may decode it (the client also registers a receiver — see MineGitFabricClient). The
        // payload is opaque bytes; no diff logic yet (issue #77).
        PayloadTypeRegistry.playS2C().register(DiffRawPayload.TYPE, DiffRawPayload.STREAM_CODEC);
    }
}
