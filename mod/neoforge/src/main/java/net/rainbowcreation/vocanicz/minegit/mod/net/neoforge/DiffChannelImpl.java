package net.rainbowcreation.vocanicz.minegit.mod.net.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/**
 * NeoForge implementation of the {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel}
 * {@code @ExpectPlatform} send seam. The payload type + S2C client handler are registered from
 * {@link MineGitNeoForgeNetworking} on the {@code RegisterPayloadHandlersEvent}.
 */
public final class DiffChannelImpl {

    private DiffChannelImpl() {
    }

    /** True when {@code player}'s connection negotiated the optional {@code minegit:diff} channel. */
    public static boolean canSend(ServerPlayer player) {
        return player.connection.hasChannel(DiffRawPayload.TYPE);
    }

    /** Sends one frame's opaque bytes to {@code player} as a raw {@code minegit:diff} payload. */
    public static void sendTo(ServerPlayer player, byte[] frameBytes) {
        PacketDistributor.sendToPlayer(player, new DiffRawPayload(frameBytes));
    }
}
