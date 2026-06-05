package net.rainbowcreation.vocanicz.minegit.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/**
 * Fabric client-distribution entrypoint — declared under the {@code client} entrypoint in
 * {@code fabric.mod.json} (issue #77). Loaded only on the client, so the dedicated server never
 * classloads the client-only networking it touches.
 *
 * <p>Registers the {@code minegit:diff} client receiver: each packet's opaque bytes are funnelled to
 * the loader-agnostic {@link DiffChannel#deliverToClient}. No diff/render logic yet — the default sink
 * just logs, proving the wire is open; later batches install the overlay sink.
 */
@Environment(EnvType.CLIENT)
public final class MineGitFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                DiffRawPayload.TYPE, (payload, context) -> DiffChannel.deliverToClient(payload.bytes()));
    }
}
