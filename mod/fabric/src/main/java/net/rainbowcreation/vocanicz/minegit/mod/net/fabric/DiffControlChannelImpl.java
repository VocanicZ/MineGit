package net.rainbowcreation.vocanicz.minegit.mod.net.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlPayload;

/**
 * Fabric implementation of the {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel}
 * {@code @ExpectPlatform} client→server send seam. The payload-type registration and <em>server</em>
 * receiver are wired from {@code MineGitFabric#onInitialize}.
 */
public final class DiffControlChannelImpl {

    private DiffControlChannelImpl() {
    }

    /** Sends one control message's bytes to the server as a raw {@code minegit:diffsub} payload (client only). */
    public static void sendToServer(byte[] controlBytes) {
        ClientPlayNetworking.send(new DiffControlPayload(controlBytes));
    }
}
