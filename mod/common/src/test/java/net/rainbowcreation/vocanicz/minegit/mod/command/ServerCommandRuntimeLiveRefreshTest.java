package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffOverlaySender;
import net.rainbowcreation.vocanicz.minegit.mod.net.LiveSubscriptionLoop;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import org.junit.jupiter.api.Test;

/**
 * The overlay is now a pure snapshot pusher (Spec SP2 §2e, closes #93/#94/#100): the per-tick
 * recompute is retired, so {@link ServerCommandRuntime#tick(net.minecraft.server.MinecraftServer)}
 * must never push the overlay — snapshots go out on SUBSCRIBE and on HEAD-move (commit/checkout
 * completion) only. This drives a runtime whose live loop is a recording-sink {@link LiveSubscriptionLoop}
 * (via the package-private test ctor) and proves {@code tick} pushes nothing even with a live subscriber
 * and even across many ticks. The HEAD-move push path itself is covered by the GameTest
 * {@code liveSubscriptionPushesOnChangeThenStopsOnUnsubscribe} (it needs a real {@code ServerLevel}).
 */
class ServerCommandRuntimeLiveRefreshTest {

    /** Records every frame handed to {@code sendTo}; always capable so a push would be observed. */
    private static final class RecordingSink implements DiffOverlaySender.Sink {
        final List<byte[]> sent = new ArrayList<byte[]>();

        @Override
        public boolean canSend(ServerPlayer player) {
            return true;
        }

        @Override
        public void sendTo(ServerPlayer player, byte[] frameBytes) {
            sent.add(frameBytes);
        }
    }

    @Test
    void tickNeverPushesTheOverlay() {
        RecordingSink sink = new RecordingSink();
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);

        // Register a live subscriber (no initial diff, so the registry holds it with no push yet).
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, true /* permitted */, null);
        assertTrue(loop.isSubscribed(id), "pre-condition: the subscriber is registered");
        sink.sent.clear();

        // Many ticks between HEAD-moves: the retired per-tick recompute must push nothing. A null server
        // is the headless seam (no player list to walk); tick must also stay a no-op there beyond pump().
        for (int i = 0; i < 50; i++) {
            runtime.tick(null);
        }

        assertTrue(sink.sent.isEmpty(),
                "tick no longer recomputes/pushes the overlay — pushes happen on subscribe + HEAD-move only");
    }

    @Test
    void subscribePushesButTickDoesNot() {
        RecordingSink sink = new RecordingSink();
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);

        // Subscribe directly on the loop with a non-null diff so the one immediate push is observable.
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        WorldDiff diff = new WorldDiff(new java.util.HashMap<>(), 0, 0, 0);
        loop.subscribe(null, id, diff);
        assertTrue(sink.sent.size() >= 1, "subscribe pushes the snapshot once immediately");

        int afterSubscribe = sink.sent.size();
        for (int i = 0; i < 10; i++) {
            runtime.tick(null);
        }

        assertTrue(sink.sent.size() == afterSubscribe,
                "no additional push may land from per-tick work between HEAD-moves");
    }
}
