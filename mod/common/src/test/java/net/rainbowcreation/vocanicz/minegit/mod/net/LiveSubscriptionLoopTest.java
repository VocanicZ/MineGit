package net.rainbowcreation.vocanicz.minegit.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import org.junit.jupiter.api.Test;

/**
 * The headless half of the server live-subscription loop (issue #93, Spec C batch 2 §2.3): the
 * per-player subscription registry plus the recompute→dedupe→push step, driven through a recording
 * {@link DiffOverlaySender.Sink} so the whole decision path is unit-testable without a live server.
 *
 * <p>The loop never dereferences the {@link ServerPlayer} itself — capability and identity are
 * supplied by the sink and the caller's UUID — so {@code null} stands in for the real player the
 * dedicated server would resolve, exactly as {@link DiffOverlaySenderTest} does.
 */
final class LiveSubscriptionLoopTest {

    /** Records every frame handed to {@code sendTo}, gated by a fixed {@code canSend} verdict. */
    private static final class RecordingSink implements DiffOverlaySender.Sink {
        private final boolean capable;
        final List<byte[]> sent = new ArrayList<byte[]>();

        RecordingSink(boolean capable) {
            this.capable = capable;
        }

        @Override
        public boolean canSend(ServerPlayer player) {
            return capable;
        }

        @Override
        public void sendTo(ServerPlayer player, byte[] frameBytes) {
            sent.add(frameBytes);
        }
    }

    /** A poller that returns a fixed snapshot for any id (or null = offline). */
    private static LiveSubscriptionLoop.Poller pollerOf(WorldDiff diff) {
        return id -> diff == null ? null : new LiveSubscriptionLoop.Snapshot(null, diff);
    }

    private static WorldDiff diffWith(int added) {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        for (int i = 0; i < Math.max(1, added); i++) {
            changes.add(BlockChange.add(i, 70, 0, new BlockState("minecraft:stone")));
        }
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims = new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        return new WorldDiff(dims, added, 0, 0);
    }

    /** An empty working-vs-HEAD diff (no uncommitted changes). */
    private static WorldDiff emptyDiff() {
        return new WorldDiff(new HashMap<DimensionId, List<ChunkDiff>>(), 0, 0, 0);
    }

    @Test
    void subscribeImmediatelyPushesCurrentDiff() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, diffWith(2));

        assertTrue(loop.isSubscribed(id), "subscribe must register the player");
        assertTrue(sink.sent.size() >= 1, "subscribe must immediately push the current diff");
    }

    @Test
    void subscribePushesEvenAnEmptyInitialDiff() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, emptyDiff());

        assertTrue(sink.sent.size() >= 1, "the initial empty diff is still pushed once on subscribe");
    }

    @Test
    void unchangedDiffIsDedupedAndNotPushedAgain() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();
        WorldDiff same = diffWith(2);
        loop.subscribe(null, id, same);
        int afterSubscribe = sink.sent.size();

        loop.tick(pollerOf(same));

        assertEquals(afterSubscribe, sink.sent.size(),
                "an unchanged working-vs-HEAD must not push again (dedupe)");
    }

    @Test
    void changedDiffPushesOnNextTick() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.tick(pollerOf(diffWith(5)));

        assertTrue(sink.sent.size() > afterSubscribe,
                "a changed working-vs-HEAD must push on the next tick");
    }

    @Test
    void incapablePlayerNeverPushes() {
        RecordingSink sink = new RecordingSink(false);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, diffWith(2));
        loop.tick(pollerOf(diffWith(5)));

        assertTrue(sink.sent.isEmpty(), "a canSend=false player must never receive a frame");
    }

    @Test
    void unsubscribeStopsPushes() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.unsubscribe(id);
        assertFalse(loop.isSubscribed(id), "unsubscribe must clear the registry entry");
        loop.tick(pollerOf(diffWith(9)));

        assertEquals(afterSubscribe, sink.sent.size(),
                "no push may land after unsubscribe even when the diff changes");
    }

    @Test
    void disconnectStopsPushes() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.disconnect(id);
        assertFalse(loop.isSubscribed(id), "disconnect must clear the registry entry");
        loop.tick(pollerOf(diffWith(9)));

        assertEquals(afterSubscribe, sink.sent.size(), "no push may land after disconnect");
    }

    @Test
    void tickFiresOnlyEveryRefreshTicks() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(3, sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();
        LiveSubscriptionLoop.Poller changing = pollerOf(diffWith(5));

        loop.tick(changing); // tick 1 — below threshold
        loop.tick(changing); // tick 2 — below threshold
        assertEquals(afterSubscribe, sink.sent.size(),
                "no recompute/push before refreshTicks elapse");

        loop.tick(changing); // tick 3 — fires
        assertTrue(sink.sent.size() > afterSubscribe,
                "the loop recomputes and pushes once refreshTicks elapse");
    }

    @Test
    void tickSkipsOfflineSubscribers() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.tick(pollerOf(null)); // poller returns null => offline / unbound

        assertEquals(afterSubscribe, sink.sent.size(), "an offline subscriber pushes nothing");
        assertTrue(loop.isSubscribed(id), "an offline subscriber stays registered until disconnect");
    }

    @Test
    void noSubscribersIsANoOp() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(1, sink);

        loop.tick(pollerOf(diffWith(3)));

        assertTrue(sink.sent.isEmpty(), "with no subscribers the loop does nothing");
    }
}
