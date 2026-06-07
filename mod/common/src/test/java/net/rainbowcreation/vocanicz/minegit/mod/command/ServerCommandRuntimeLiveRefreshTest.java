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
 * completion) only.
 *
 * <p><strong>Scope of this headless suite — read before trusting it as the retired-loop guard.</strong>
 * These tests can only drive {@code tick(null)}: a null server is the only headless seam, and
 * {@code tick}'s push path ({@link ServerCommandRuntime#pushHeadMove}) early-returns on a null server
 * before it could walk any player list. So this suite asserts the narrow fact that <em>the null-server
 * tick path pushes nothing beyond {@code pump()}</em> — it does <em>not</em> exercise a real player-list
 * walk and therefore would also pass against the old per-tick-recompute code. The authoritative proof
 * that ticking pushes nothing <em>with a real, non-null server and a live subscriber</em> is the GameTest
 * {@code LiveOverlayRetiredLoopGameTest.ticksPushNothingOnRealServer} (it needs a real
 * {@code MinecraftServer}/{@code ServerLevel}, which only the GameTest harness provides). Keep both: this
 * suite guards the headless seam, the GameTest guards the real-server contract.
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
    void nullServerTickPushesNothing() {
        RecordingSink sink = new RecordingSink();
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);

        // Register a live subscriber (no initial diff, so the registry holds it with no push yet).
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, true /* permitted */, null);
        assertTrue(loop.isSubscribed(id), "pre-condition: the subscriber is registered");
        sink.sent.clear();

        // Many ticks on the null-server seam. NOTE: this is the only server a headless test can pass, and
        // tick's push path early-returns on a null server before any player-list walk — so this asserts
        // only that the null-server tick path stays a no-op beyond pump(). The real guard that ticking
        // pushes nothing with a non-null server + live subscriber is the GameTest
        // LiveOverlayRetiredLoopGameTest.ticksPushNothingOnRealServer (see this class's javadoc).
        for (int i = 0; i < 50; i++) {
            runtime.tick(null);
        }

        assertTrue(sink.sent.isEmpty(),
                "the null-server tick path must push nothing beyond pump() (real-server guard is the GameTest)");
    }

    @Test
    void subscribePushesButNullServerTickDoesNot() {
        RecordingSink sink = new RecordingSink();
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);

        // Subscribe directly on the loop with a non-null diff so the one immediate push is observable.
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        WorldDiff diff = new WorldDiff(new java.util.HashMap<>(), 0, 0, 0);
        loop.subscribe(null, id, diff);
        assertTrue(sink.sent.size() >= 1, "subscribe pushes the snapshot once immediately");

        // Null-server ticks add no further push. As above, this only covers the headless null-server
        // seam; the real-server no-push guard is the GameTest (see this class's javadoc).
        int afterSubscribe = sink.sent.size();
        for (int i = 0; i < 10; i++) {
            runtime.tick(null);
        }

        assertTrue(sink.sent.size() == afterSubscribe,
                "no additional push may land from the null-server tick path between HEAD-moves");
    }
}
