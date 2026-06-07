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
 * Verifies the {@code minegit.use} permission gate on {@code SUBSCRIBE} in
 * {@link ServerCommandRuntime#onControl} (SP1 transport-parity, 2026-06-07).
 *
 * <p><strong>Test-seam approach — two injected hooks:</strong>
 * <ol>
 *   <li>A package-private {@code ServerCommandRuntime(Clock, Executor, LiveSubscriptionLoop)}
 *       constructor injects a real {@link LiveSubscriptionLoop} backed by a no-op
 *       {@link DiffOverlaySender.Sink} ({@code canSend = false}, so no frames are emitted and the
 *       {@code ServerPlayer} argument is never used for I/O). Subscription state is observable via
 *       {@link LiveSubscriptionLoop#isSubscribed(UUID)} and {@link LiveSubscriptionLoop#subscriberCount()}.
 *   <li>A package-private {@code onControlInner(UUID, ServerPlayer, DiffControl, boolean, WorldDiff)}
 *       overload accepts all decisions already resolved: the player UUID, the pre-computed
 *       {@code permitted} flag, and the current diff. The production
 *       {@link ServerCommandRuntime#onControl(net.minecraft.server.level.ServerPlayer, DiffControl)}
 *       resolves these from the live player; tests supply an arbitrary UUID, {@code true}/{@code false}
 *       for the permission decision, and {@code null} for the diff (no bound repo — registering without
 *       an initial push). The {@code ServerPlayer} argument is also {@code null} (the loop passes it
 *       only to the sink's {@code canSend}/{@code sendTo}, which are no-ops here). This keeps the
 *       test completely headless — no real {@code ServerPlayer} or {@code CommandSourceStack} needed.
 * </ol>
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
