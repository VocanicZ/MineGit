package net.rainbowcreation.vocanicz.minegit.mod.net.neoforge;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlPayload;

/**
 * NeoForge implementation of the {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel}
 * {@code @ExpectPlatform} client→server send seam. The payload type + C2S server handler are registered
 * from {@link net.rainbowcreation.vocanicz.minegit.mod.neoforge.MineGitNeoForgeNetworking} on the
 * {@code RegisterPayloadHandlersEvent}.
 */
public final class DiffControlChannelImpl {

    private DiffControlChannelImpl() {
    }

    /** Sends one control message's bytes to the server as a raw {@code minegit:diffsub} payload (client only). */
    public static void sendToServer(byte[] controlBytes) {
        ClientPacketDistributor.sendToServer(new DiffControlPayload(controlBytes));
    }
}
