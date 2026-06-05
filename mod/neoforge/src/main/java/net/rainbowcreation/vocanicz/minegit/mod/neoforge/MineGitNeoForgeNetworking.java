package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;

/**
 * NeoForge {@code minegit:diff} channel registration (issue #77). NeoForge registers custom payloads
 * (type + handlers) in one place — the {@link RegisterPayloadHandlersEvent} on the mod bus — so this
 * is wired from the {@code MineGitNeoForge} constructor where the bus is reachable, mirroring the
 * GameTest registration.
 *
 * <p>The channel is {@code optional()}: a vanilla or other client that did not negotiate it is not
 * disconnected, and {@code DiffChannel.canSend} reports {@code false} for it. The S2C handler routes
 * each packet's opaque bytes to the loader-agnostic {@link DiffChannel#deliverToClient}; it only fires
 * on the client, so client types are never touched on a dedicated server.
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
    }
}
