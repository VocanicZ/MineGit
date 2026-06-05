package net.rainbowcreation.vocanicz.minegit.mod.net;

import dev.architectury.injectables.annotations.ExpectPlatform;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code minegit:diff} channel seam (Spec C §2.2/§2.3, issue #77). The diff overlay travels as an
 * <strong>opaque {@code byte[]}</strong> custom payload — {@link DiffRawPayload} — never a typed mod
 * struct, so the exact bytes a future Spigot plugin emits decode identically on the client.
 *
 * <p>This batch wires the channel only: a server→client send seam ({@link #canSend}/{@link #sendTo})
 * plus the loader-agnostic sink the per-loader client receiver funnels bytes into
 * ({@link #setClientHandler}/{@link #deliverToClient}). There is no diff logic yet — the default sink
 * just logs, proving the wire is open. Decode → reassemble → render lands in later batches, installing
 * its own sink over {@link #setClientHandler}.
 *
 * <p>The loader-specific packet send lives in {@code DiffChannelImpl} under each of
 * {@code mod:fabric} / {@code mod:neoforge}, stitched in by architectury-plugin at build time; the
 * payload-type and client-receiver registration is wired from each loader's entrypoint, where the
 * loader's networking lifecycle (Fabric's {@code PayloadTypeRegistry} / NeoForge's payload event) is
 * reachable.
 */
public final class DiffChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("MineGit/diff");

    /** The fallback sink before the client overlay installs its own: logs, never throws. */
    private static final Consumer<byte[]> NO_OP = bytes ->
            LOGGER.debug("[minegit:diff] received {} bytes (no overlay sink installed yet)", bytes.length);

    /** The current client-side sink. Replace-on-install; the receiver always funnels here. */
    private static volatile Consumer<byte[]> clientHandler = NO_OP;

    private DiffChannel() {
    }

    /**
     * Installs the client-side sink for received {@code minegit:diff} payloads. The client overlay
     * (a later batch) calls this from its client init; a newly installed sink replaces the previous.
     */
    public static void setClientHandler(Consumer<byte[]> handler) {
        clientHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Restores the default logging no-op sink. Used by tests and on client teardown. */
    public static void resetClientHandler() {
        clientHandler = NO_OP;
    }

    /**
     * Hands one received frame's raw bytes to the installed sink. The {@code @ExpectPlatform} client
     * receiver calls this for every {@code minegit:diff} packet; keeping it loader-agnostic makes the
     * receive path unit-testable without a live client.
     */
    public static void deliverToClient(byte[] frameBytes) {
        clientHandler.accept(frameBytes);
    }

    // ---- loader-specific seams (architectury @ExpectPlatform) ---------------------------------

    /**
     * Whether {@code player} has the {@code minegit:diff} channel open (i.e. runs this mod). A vanilla
     * or other client returns {@code false} and is silently skipped by the server send — no error.
     */
    @ExpectPlatform
    public static boolean canSend(ServerPlayer player) {
        throw new AssertionError("@ExpectPlatform stub — replaced by DiffChannelImpl at build time");
    }

    /**
     * Sends one frame's opaque bytes to {@code player} over {@code minegit:diff} as a raw byte
     * payload. Callers gate on {@link #canSend} first; sending to a player without the channel is a
     * no-op or quietly dropped depending on the loader.
     */
    @ExpectPlatform
    public static void sendTo(ServerPlayer player, byte[] frameBytes) {
        throw new AssertionError("@ExpectPlatform stub — replaced by DiffChannelImpl at build time");
    }
}
