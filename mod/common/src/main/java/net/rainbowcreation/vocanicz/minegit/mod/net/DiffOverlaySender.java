package net.rainbowcreation.vocanicz.minegit.mod.net;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Framing;

/**
 * The server side of the {@code /mg diff} overlay push (Spec C §2.2, issue #78): after the command
 * has printed its chat diff, it <em>also</em> streams the same {@link WorldDiff} to the requesting
 * player as the overlay payload —
 * {@link DiffPayload#encode(WorldDiff, String, String) encode} →
 * {@link Framing#frame(byte[], int) frame} (at {@link Framing#DEFAULT_MAX_FRAME_BYTES}) →
 * per-{@link Frame} {@link Frame#toBytes() toBytes()} → {@link Sink#sendTo}.
 *
 * <p>The whole push is <strong>gated on {@link Sink#canSend}</strong>: a player without the client
 * mod (no negotiated {@code minegit:diff} channel) is silently skipped and gets <em>no</em> packet,
 * leaving the chat diff untouched. Sending is never required for the command to succeed.
 *
 * <p>Transmission is funneled through the {@link Sink} seam rather than calling
 * {@link DiffChannel} directly so the pure encode/frame/gate half is unit-testable headless with a
 * recording sink; production routes through {@link #CHANNEL_SINK}, which delegates to the
 * {@code @ExpectPlatform} {@link DiffChannel#canSend}/{@link DiffChannel#sendTo} proven per loader.
 */
public final class DiffOverlaySender {

    /**
     * The capability-gated transmit seam: whether {@code player} can receive the overlay, and how to
     * hand one frame's opaque bytes to it. Production binds this to {@link DiffChannel}; tests bind a
     * recording sink so the framing path is exercised without a live network.
     */
    public interface Sink {
        /** Whether {@code player} has the {@code minegit:diff} channel open (runs the client mod). */
        boolean canSend(ServerPlayer player);

        /** Sends one frame's opaque bytes to {@code player} over {@code minegit:diff}. */
        void sendTo(ServerPlayer player, byte[] frameBytes);
    }

    /** Production sink: delegates straight to the loader-bound {@link DiffChannel} send seam. */
    private static final Sink CHANNEL_SINK = new Sink() {
        @Override
        public boolean canSend(ServerPlayer player) {
            return DiffChannel.canSend(player);
        }

        @Override
        public void sendTo(ServerPlayer player, byte[] frameBytes) {
            DiffChannel.sendTo(player, frameBytes);
        }
    };

    private DiffOverlaySender() {
    }

    /**
     * Pushes {@code diff} (tagged {@code fromRef}/{@code toRef}) to {@code player} over the real
     * {@link DiffChannel}, gated on {@link DiffChannel#canSend}. Returns the number of frames sent —
     * {@code 0} when the player is incapable (silently skipped).
     */
    public static int send(ServerPlayer player, WorldDiff diff, String fromRef, String toRef) {
        return send(player, diff, fromRef, toRef, CHANNEL_SINK);
    }

    /**
     * Sink-injecting variant: same encode→frame→gate→send, but transmits through {@code sink}. The
     * unit tests drive this with a recording sink; production calls the {@link DiffChannel}-backed
     * overload above.
     *
     * @return the number of frames handed to {@code sink} ({@code 0} if {@code canSend} is false)
     */
    public static int send(ServerPlayer player, WorldDiff diff, String fromRef, String toRef, Sink sink) {
        if (!sink.canSend(player)) {
            return 0;
        }
        byte[] payload = DiffPayload.encode(diff, fromRef, toRef);
        List<Frame> frames = Framing.frame(payload, Framing.DEFAULT_MAX_FRAME_BYTES);
        for (Frame frame : frames) {
            sink.sendTo(player, frame.toBytes());
        }
        return frames.size();
    }
}
