package net.rainbowcreation.vocanicz.minegit.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.fabric.MineGitPermissionsImpl;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlPayload;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/** Fabric loader entrypoint — declared in {@code fabric.mod.json}. Delegates to the shared init. */
public final class MineGitFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        MineGitPermissionsImpl.install();
        MineGitMod.init();
        // Register the minegit:diff S2C payload type on both dists so the server may send it and the
        // client may decode it (the client also registers a receiver — see MineGitFabricClient). The
        // payload is opaque bytes; no diff logic yet (issue #77).
        PayloadTypeRegistry.playS2C().register(DiffRawPayload.TYPE, DiffRawPayload.STREAM_CODEC);
        // Register the minegit:diffsub C2S control payload type + server receiver (issue #91): the
        // keybind sends a SUBSCRIBE/UNSUBSCRIBE byte and the receiver funnels the sending player +
        // bytes to the loader-agnostic DiffControlChannel.deliverToServer (on the server thread).
        PayloadTypeRegistry.playC2S().register(DiffControlPayload.TYPE, DiffControlPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                DiffControlPayload.TYPE,
                (payload, context) -> DiffControlChannel.deliverToServer(context.player(), payload.bytes()));
    }
}
