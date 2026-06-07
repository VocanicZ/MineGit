package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.UUID;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffOverlaySender;
import net.rainbowcreation.vocanicz.minegit.mod.net.LiveSubscriptionLoop;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the {@code minegit.use} permission gate in
 * {@link ServerCommandRuntime#onControl} / {@link ServerCommandRuntime#onControlInner} — see those
 * methods' javadoc for the seam contract this test suite relies on (SP1, 2026-06-07).
 */
class ServerCommandRuntimeOnControlTest {

    /** No-op sink: canSend always returns false so frames are never emitted and player is unused. */
    private static final DiffOverlaySender.Sink NO_OP_SINK = new DiffOverlaySender.Sink() {
        @Override public boolean canSend(net.minecraft.server.level.ServerPlayer player) { return false; }
        @Override public void sendTo(net.minecraft.server.level.ServerPlayer player, byte[] frameBytes) {}
    };

    /** Builds a real {@link LiveSubscriptionLoop} backed by the no-op sink. */
    private static LiveSubscriptionLoop recordingLoop() {
        return new LiveSubscriptionLoop(1, NO_OP_SINK);
    }

    /** Builds a runtime whose live loop is the given loop (test-seam constructor). */
    private static ServerCommandRuntime runtimeWith(LiveSubscriptionLoop loop) {
        return new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);
    }

    // ---- tests -----------------------------------------------------------------------------------

    /**
     * Denied permission → {@code SUBSCRIBE} is silently ignored: no subscription registered.
     * The {@code permitted=false} flag causes the gate to return early before any loop call.
     * Player and diff are null because the code never reaches them.
     */
    @Test
    void deniedSubscribeIsIgnored() {
        LiveSubscriptionLoop loop = recordingLoop();
        ServerCommandRuntime runtime = runtimeWith(loop);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, false /* denied */, null);

        assertEquals(0, loop.subscriberCount(),
                "denied SUBSCRIBE must not register a subscriber");
        assertFalse(loop.isSubscribed(id),
                "denied SUBSCRIBE must leave the UUID unsubscribed");
    }

    /**
     * Allowed permission → {@code SUBSCRIBE} calls {@link LiveSubscriptionLoop#subscribe} and
     * registers the UUID. Diff is null (no bound repo) — the loop registers without an initial push.
     * Player is null — the no-op sink never dereferences it.
     */
    @Test
    void allowedSubscribeRegistersThePlayer() {
        LiveSubscriptionLoop loop = recordingLoop();
        ServerCommandRuntime runtime = runtimeWith(loop);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");

        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, true /* permitted */, null);

        assertEquals(1, loop.subscriberCount(),
                "allowed SUBSCRIBE must register exactly one subscriber");
        assertTrue(loop.isSubscribed(id),
                "allowed SUBSCRIBE must register this player UUID in the live loop");
    }

    /**
     * {@code UNSUBSCRIBE} is never gated: even when the {@code permitted} flag is false, the
     * unsubscribe fires. This ensures a client can always drop its subscription regardless of
     * any permission-state change after subscribe.
     */
    @Test
    void unsubscribeIsNeverGatedEvenWhenDenied() {
        LiveSubscriptionLoop loop = recordingLoop();
        ServerCommandRuntime runtime = runtimeWith(loop);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // Subscribe first (allowed) so there is a subscription to remove.
        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, true, null);
        assertTrue(loop.isSubscribed(id), "pre-condition: player must be subscribed");

        // Unsubscribe with the denied flag — must still unsubscribe.
        runtime.onControlInner(id, null, DiffControl.UNSUBSCRIBE, false /* denied */, null);

        assertEquals(0, loop.subscriberCount(),
                "UNSUBSCRIBE must remove the subscription regardless of permission flag");
        assertFalse(loop.isSubscribed(id),
                "UNSUBSCRIBE must clear the UUID even when the permission predicate would deny");
    }
}
