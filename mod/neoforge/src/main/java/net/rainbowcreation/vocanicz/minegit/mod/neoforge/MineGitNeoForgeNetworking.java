package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlPayload;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/**
 * NeoForge channel registration for both MineGit overlay payloads. NeoForge registers custom payloads
 * (type + handlers) in one place — the {@link RegisterPayloadHandlersEvent} on the mod bus — so this
 * is wired from the {@code MineGitNeoForge} constructor where the bus is reachable, mirroring the
 * GameTest registration.
 *
 * <p>The channels are {@code optional()}: a vanilla or other client that did not negotiate them is not
 * disconnected, and {@code DiffChannel.canSend} reports {@code false} for it. The S2C {@code minegit:diff}
 * handler (issue #77) routes each packet's opaque bytes to the loader-agnostic
 * {@link DiffChannel#deliverToClient}; it only fires on the client, so client types are never touched
 * on a dedicated server. The C2S {@code minegit:diffsub} control handler (issue #91) routes the
 * sending player + control bytes to {@link DiffControlChannel#deliverToServer} on the server thread.
 */
public final class MineGitNeoForgeNetworking {

    private MineGitNeoForgeNetworking() {
    }

    /** Hooks the payload registration onto the mod event bus. */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, MineGitNeoForgeNetworking::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(
                DiffRawPayload.TYPE,
                DiffRawPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> DiffChannel.deliverToClient(payload.bytes())));
        registrar.playToServer(
                DiffControlPayload.TYPE,
                DiffControlPayload.STREAM_CODEC,
                (payload, context) -> {
                    Player player = context.player();
                    ServerPlayer sender = player instanceof ServerPlayer ? (ServerPlayer) player : null;
                    context.enqueueWork(() -> DiffControlChannel.deliverToServer(sender, payload.bytes()));
                });
    }
}
