package net.rainbowcreation.vocanicz.minegit.mod.net;

import dev.architectury.injectables.annotations.ExpectPlatform;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code minegit:diffsub} client→server control-channel seam (Spec C batch 2 §2.1, issue #91), the
 * mirror of batch 1's server→client {@link DiffChannel}. The overlay keybind drives a live
 * subscription: it sends a single {@link DiffControl} byte ({@code SUBSCRIBE}/{@code UNSUBSCRIBE}) as
 * an <strong>opaque {@code byte[]}</strong> custom payload — {@link DiffControlPayload} — and the
 * server pushes working-vs-HEAD over {@link DiffChannel} while subscribed.
 *
 * <p>This issue wires the channel only: a loader-agnostic server-side sink the per-loader receiver
 * funnels bytes into ({@link #setServerHandler}/{@link #deliverToServer}), plus an {@code @ExpectPlatform}
 * client→server send seam ({@link #sendToServer}) the keybind (a later issue, §2.2) consumes. The
 * live-subscription loop (#E) installs its own {@link Handler} over {@link #setServerHandler}; until
 * then the default handler just logs, proving the wire is open.
 *
 * <p>The loader-specific packet send lives in {@code DiffControlChannelImpl} under each of
 * {@code mod:fabric} / {@code mod:neoforge}; the payload-type and <em>server</em> receiver
 * registration is wired from each loader's entrypoint, where the loader's networking lifecycle
 * (Fabric's {@code PayloadTypeRegistry} + {@code ServerPlayNetworking} / NeoForge's payload event) is
 * reachable.
 */
public final class DiffControlChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("MineGit/diffsub");

    /** The server-side sink for a decoded control message, keyed to the sending player. */
    public interface Handler {
        /** Called on the server thread for each well-formed control a subscribed client sends. */
        void onControl(ServerPlayer player, DiffControl control);
    }

    /** The fallback handler before the live-subscription loop installs its own: logs, never throws. */
    private static final Handler NO_OP = (player, control) ->
            LOGGER.debug("[minegit:diffsub] received {} (no subscription handler installed yet)", control);

    /** The current server-side handler. Replace-on-install; the receiver always funnels here. */
    private static volatile Handler serverHandler = NO_OP;

    private DiffControlChannel() {
    }

    /**
     * Installs the server-side handler for received control messages. The live-subscription loop (a
     * later issue) calls this from server init; a newly installed handler replaces the previous.
     */
    public static void setServerHandler(Handler handler) {
        serverHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Restores the default logging no-op handler. Used by tests and on server teardown. */
    public static void resetServerHandler() {
        serverHandler = NO_OP;
    }

    /**
     * Decodes one received control payload and dispatches it to the installed handler. The
     * {@code @ExpectPlatform} server receiver calls this for every {@code minegit:diffsub} packet;
     * keeping it loader-agnostic makes the receive path unit-testable without a live server.
     *
     * <p>A malformed payload (unknown byte, wrong length) from a hostile or out-of-date client is
     * logged and dropped — never propagated and never dispatched — so one bad packet cannot crash the
     * server or confuse the subscription registry.
     */
    public static void deliverToServer(ServerPlayer player, byte[] payloadBytes) {
        DiffControl control;
        try {
            control = DiffControl.decode(payloadBytes);
        } catch (RuntimeException badPacket) {
            LOGGER.warn("[minegit:diffsub] dropping malformed control packet: {}", badPacket.getMessage());
            return;
        }
        serverHandler.onControl(player, control);
    }

    // ---- loader-specific seams (architectury @ExpectPlatform) ---------------------------------

    /**
     * Sends one control message's opaque bytes (a {@link DiffControl#encode()} result) to the server
     * from the client as a raw {@code minegit:diffsub} payload. The overlay keybind (a later issue,
     * §2.2) calls this on toggle; only reachable on the physical client, so the dedicated server never
     * classloads the client-only networking it touches.
     *
     * <p>Takes {@code byte[]} (not {@link DiffControl}) so the loader impls depend only on the common
     * payload type, never the protocol module — mirroring {@link DiffChannel#sendTo}.
     */
    @ExpectPlatform
    public static void sendToServer(byte[] controlBytes) {
        throw new AssertionError("@ExpectPlatform stub — replaced by DiffControlChannelImpl at build time");
    }
}
