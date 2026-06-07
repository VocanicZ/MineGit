package net.rainbowcreation.vocanicz.minegit.mod.net.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
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

    /**
     * Whether the {@code minegit:diffsub} channel was negotiated on the active connection — the same
     * registration NeoForge's {@code NetworkRegistry.checkPacket} consults, so {@code true} guarantees
     * {@link #sendToServer} will not throw. False on a non-MineGit server or when not in a play session.
     */
    public static boolean canSendToServer() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(DiffControlPayload.TYPE);
    }
}
