package net.rainbowcreation.vocanicz.minegit.mod.net.fabric;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/**
 * Fabric implementation of the {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel}
 * {@code @ExpectPlatform} send seam. The payload-type registration and client receiver are wired from
 * the Fabric entrypoints ({@code MineGitFabric} / {@code MineGitFabricClient}).
 */
public final class DiffChannelImpl {

    private DiffChannelImpl() {
    }

    /** True when {@code player} negotiated the {@code minegit:diff} S2C channel (i.e. runs this mod). */
    public static boolean canSend(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, DiffRawPayload.TYPE);
    }

    /** Sends one frame's opaque bytes to {@code player} as a raw {@code minegit:diff} payload. */
    public static void sendTo(ServerPlayer player, byte[] frameBytes) {
        ServerPlayNetworking.send(player, new DiffRawPayload(frameBytes));
    }
}
