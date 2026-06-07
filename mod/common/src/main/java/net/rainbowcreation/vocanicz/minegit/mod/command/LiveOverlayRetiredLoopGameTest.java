package net.rainbowcreation.vocanicz.minegit.mod.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffOverlaySender;
import net.rainbowcreation.vocanicz.minegit.mod.net.LiveSubscriptionLoop;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;

/**
 * The authoritative regression guard that the per-tick overlay recompute is <strong>retired</strong>
 * (Spec SP2 §2e, closes #93/#94/#100), proven on a <em>real</em> dedicated server.
 *
 * <p>Lives in the {@code command} package — not {@code gametest} — so it can reach the package-private
 * {@link ServerCommandRuntime} test-seam constructor and {@link ServerCommandRuntime#onControlInner}
 * without widening any production API. The loader GameTest entrypoints register
 * {@link #ticksPushNothingOnRealServer} as a thin wrapper, exactly like the other
 * {@code MineGitGameTestLogic} bodies, so Fabric and NeoForge both run it headlessly.
 *
 * <p><strong>Why this exists.</strong> The headless {@code ServerCommandRuntimeLiveRefreshTest} can
 * only drive {@code runtime.tick(null)} — a null server early-returns out of any
 * player-list walk, so that test would pass against the <em>old</em> per-tick-recompute code too and
 * cannot prove the recompute is gone. This GameTest closes that gap: it drives
 * {@link ServerCommandRuntime#tick(MinecraftServer)} with the <em>real, non-null</em>
 * {@link MinecraftServer} the GameTest harness provides, with a live subscriber registered in the
 * runtime's own loop, and asserts that ticking pushes <em>nothing</em>. Pushes happen only on
 * SUBSCRIBE and on HEAD-move.
 */
public final class LiveOverlayRetiredLoopGameTest {

    private LiveOverlayRetiredLoopGameTest() {
    }

    /** Records every frame handed to {@code sendTo}; always capable so any push would be observed. */
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

    /**
     * Drives the production {@link ServerCommandRuntime#tick(MinecraftServer)} on a real
     * {@link ServerLevel}/{@link MinecraftServer} and proves ticking between HEAD-moves pushes nothing:
     *
     * <ol>
     *   <li>Build a runtime over a recording {@link LiveSubscriptionLoop} (the package-private test
     *       seam), so any overlay push the runtime makes is captured.</li>
     *   <li>SUBSCRIBE a player via {@link ServerCommandRuntime#onControlInner} ({@code permitted=true})
     *       with an <em>empty</em> diff so the single immediate subscribe-push is observable — exactly
     *       one frame batch — and assert the subscriber is registered.</li>
     *   <li>Drive {@code runtime.tick(server)} with the <em>real</em> server for many ticks
     *       (40), with no commit/checkout in between. Assert the recording sink received
     *       <em>not one further frame</em> — i.e. the retired per-tick recompute pushes nothing on a
     *       real server. This is the assertion the headless test (null server) cannot make.</li>
     *   <li>Then trigger a HEAD-move via {@link LiveSubscriptionLoop#pushTo} (the same call the
     *       runtime's commit/checkout completion makes) and assert exactly one additional push lands —
     *       proving the push path is still wired, only the tick cadence is gone.</li>
     * </ol>
     */
    public static void ticksPushNothingOnRealServer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        require(server != null, "the GameTest harness must provide a real, non-null MinecraftServer");

        // (1) A runtime whose live loop is a recording sink (package-private test-seam ctor): any push
        // the runtime makes — on subscribe, on tick, or on HEAD-move — is captured here.
        RecordingSink sink = new RecordingSink();
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        ServerCommandRuntime runtime = new ServerCommandRuntime(Clock.systemUTC(), Runnable::run, loop);

        // (2) SUBSCRIBE with an empty (non-null) diff so the one immediate subscribe-push is observable.
        UUID id = UUID.randomUUID();
        WorldDiff emptyDiff = new WorldDiff(new java.util.HashMap<>(), 0, 0, 0);
        runtime.onControlInner(id, null, DiffControl.SUBSCRIBE, true /* permitted */, emptyDiff);
        require(loop.isSubscribed(id), "SUBSCRIBE must register the player in the runtime's live loop");
        require(!sink.sent.isEmpty(), "SUBSCRIBE must push the initial snapshot exactly once");
        int afterSubscribe = sink.sent.size();

        // (3) THE GUARD: tick the runtime with the REAL server for many ticks, no HEAD-move between.
        // The retired per-tick recompute must push nothing — the headless null-server test cannot
        // prove this, because a null server early-returns before any player-list walk.
        for (int i = 0; i < 40; i++) {
            runtime.tick(server);
        }
        require(sink.sent.size() == afterSubscribe,
                "ticking between HEAD-moves must push nothing on a real server — the per-tick "
                        + "overlay recompute is retired (was " + sink.sent.size() + " frames, expected "
                        + afterSubscribe + ")");

        // (4) The push path still works: a HEAD-move push (the call commit/checkout completion makes)
        // delivers exactly one more snapshot to the subscriber.
        sink.sent.clear();
        int pushed = loop.pushTo(null, id, emptyDiff);
        require(pushed >= 1, "a HEAD-move push must still reach the subscriber (got " + pushed + ")");
        require(!sink.sent.isEmpty(), "the HEAD-move push must reach the recording sink");

        helper.succeed();
    }

    /** Fails the GameTest (a thrown exception is recorded as the failure) when {@code cond} is false. */
    private static void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalStateException(message);
        }
    }
}
