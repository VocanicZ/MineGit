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
 * The headless half of the server overlay subscription registry + snapshot push (Spec SP2 §2e): the
 * per-player registry plus the SUBSCRIBE/HEAD-move push, driven through a recording
 * {@link DiffOverlaySender.Sink} so the whole decision path is unit-testable without a live server.
 *
 * <p>The per-tick recompute/dedupe of the original live loop is retired — the client is now the
 * live-diff engine — so these assert the snapshot contract: push exactly once on subscribe (with a
 * non-null diff) and once per HEAD-move {@code pushTo} to a current subscriber.
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
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, diffWith(2));

        assertTrue(loop.isSubscribed(id), "subscribe must register the player");
        assertTrue(sink.sent.size() >= 1, "subscribe must immediately push the current diff");
    }

    @Test
    void subscribePushesEvenAnEmptyInitialDiff() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, emptyDiff());

        assertTrue(sink.sent.size() >= 1, "the initial empty diff is still pushed once on subscribe");
    }

    @Test
    void subscribeWithNullDiffDoesNotPush() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, null);

        assertTrue(loop.isSubscribed(id), "subscribe still registers the player without a diff");
        assertTrue(sink.sent.isEmpty(), "a null current diff (no bound repo) must not push on subscribe");
    }

    @Test
    void incapablePlayerNeverPushes() {
        RecordingSink sink = new RecordingSink(false);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();

        loop.subscribe(null, id, diffWith(2));
        loop.pushTo(null, id, diffWith(5));

        assertTrue(sink.sent.isEmpty(), "a canSend=false player must never receive a frame");
    }

    @Test
    void pushToSubscriberSendsOnce() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        int frames = loop.pushTo(null, id, diffWith(5));

        assertTrue(frames >= 1, "a HEAD-move push to a subscriber must send frames");
        assertTrue(sink.sent.size() > afterSubscribe, "the HEAD-move snapshot must reach the sink");
    }

    @Test
    void pushToWithNullDiffIsANoOp() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        assertEquals(0, loop.pushTo(null, id, null), "a null HEAD-move diff pushes nothing");
        assertEquals(afterSubscribe, sink.sent.size(), "no frame may be sent for a null diff");
    }

    @Test
    void unsubscribeThenPushToIsANoOp() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.unsubscribe(id);
        assertFalse(loop.isSubscribed(id), "unsubscribe must clear the registry entry");
        assertEquals(0, loop.pushTo(null, id, diffWith(9)), "pushTo a non-subscriber pushes nothing");

        assertEquals(afterSubscribe, sink.sent.size(),
                "no push may land after unsubscribe even on a HEAD-move");
    }

    @Test
    void disconnectThenPushToIsANoOp() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
        UUID id = UUID.randomUUID();
        loop.subscribe(null, id, diffWith(1));
        int afterSubscribe = sink.sent.size();

        loop.disconnect(id);
        assertFalse(loop.isSubscribed(id), "disconnect must clear the registry entry");
        assertEquals(0, loop.pushTo(null, id, diffWith(9)), "pushTo a disconnected player pushes nothing");

        assertEquals(afterSubscribe, sink.sent.size(), "no push may land after disconnect");
    }

    @Test
    void pushToUnknownIdIsANoOp() {
        RecordingSink sink = new RecordingSink(true);
        LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);

        assertEquals(0, loop.pushTo(null, UUID.randomUUID(), diffWith(3)),
                "pushTo an id that never subscribed pushes nothing");
        assertTrue(sink.sent.isEmpty(), "with no subscribers a HEAD-move push does nothing");
    }
}
