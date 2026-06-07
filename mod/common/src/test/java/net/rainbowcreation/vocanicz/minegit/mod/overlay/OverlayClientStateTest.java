package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Framing;

/**
 * Headless tests for the loader-agnostic client overlay holder + receiver (Spec C §2.3, §3; issue
 * #80). Everything that can be wrong in the receive → reassemble → hold → toggle → expire → clear
 * lifecycle lives here, away from the untestable GPU draw call.
 */
class OverlayClientStateTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    private static ChunkDiff chunk(int cx, int cz, BlockChange... changes) {
        return new ChunkDiff(new ChunkPos(cx, cz), Arrays.asList(changes));
    }

    private static WorldDiff sampleDiff() {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        // Y in section 0 ([0,16)) so a FakeLevelAccess(OVERWORLD, 0, 1) seeds + diffs these positions.
        dims.put(DimensionId.OVERWORLD, Arrays.asList(
                chunk(0, 0,
                        BlockChange.add(1, 5, 1, STONE),
                        BlockChange.remove(2, 5, 2, DIRT),
                        BlockChange.change(3, 5, 3, DIRT, STONE))));
        return new WorldDiff(dims, 1, 1, 1);
    }

    private static byte[] payload() {
        return DiffPayload.encode(sampleDiff(), "main", "HEAD");
    }

    /** Feeds every frame of {@code payload} into {@code state}, returning the last accept result. */
    private static Optional<OverlayState> feedAll(
            OverlayClientState state, byte[] payload, int maxFrameBytes, long now) {
        Optional<OverlayState> last = Optional.empty();
        for (Frame f : Framing.frame(payload, maxFrameBytes)) {
            last = state.acceptFrame(f.toBytes(), now);
        }
        return last;
    }

    /**
     * A {@link FakeLevelAccess} for the OVERWORLD with chunk (0,0) loaded, whose live world matches
     * the working-tree (newState) side of {@link #sampleDiff()} so the engine computes real boxes
     * after a seed+tick. The sample's CHANGE entry is {@code (3,5,3) DIRT->STONE}: setting the live
     * block to STONE there means HEAD (frozen as DIRT via the dirty-overlay) differs from live (STONE),
     * yielding at least one CHANGE box; the REMOVE entry {@code (2,5,2)} (HEAD DIRT, live air) yields
     * another.
     */
    private static FakeLevelAccess sampleLevel() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        level.addLoadedChunk(0, 0);
        level.setBlock(3, 5, 3, STONE);
        return level;
    }

    /** A state wired to {@link #sampleLevel()} and the OVERWORLD active dimension. */
    private static OverlayClientState seededState(FakeLevelAccess level) {
        OverlayClientState state = new OverlayClientState();
        state.setLevelSupplier(() -> level);
        state.setActiveDimension(DimensionId.OVERWORLD);
        return state;
    }

    /** Records the controls the toggle sends, so the SUB/UNSUB wire is asserted headless. */
    private static final class RecordingSender implements OverlayClientState.ControlSender {
        final List<DiffControl> sent = new ArrayList<>();

        @Override
        public void send(DiffControl control) {
            sent.add(control);
        }
    }

    // ---- receive → reassemble → hold -----------------------------------------------------------

    @Test
    void partialFramesYieldNoOverlayYet() {
        OverlayClientState state = new OverlayClientState();
        List<Frame> frames = Framing.frame(payload(), 16);
        // Multi-frame payload: feeding only the first frame must not complete an overlay.
        assertTrue(frames.size() > 1, "test needs a multi-frame payload");

        Optional<OverlayState> result = state.acceptFrame(frames.get(0).toBytes(), 0L);

        assertFalse(result.isPresent(), "an incomplete payload holds no overlay");
        assertNull(state.current(), "no overlay is held until the payload completes");
    }

    @Test
    void completedPayloadSeedsEngineAndBecomesVisible() {
        // Migrated from the stored-state model: the completing frame no longer *holds* the pushed
        // overlay verbatim — it seeds the engine, which computes the overlay from the live world. The
        // accept result is the engine's freshly-computed overlay, and the state becomes visible.
        FakeLevelAccess level = sampleLevel();
        OverlayClientState state = seededState(level);

        Optional<OverlayState> result = feedAll(state, payload(), 16, 100L);

        assertTrue(result.isPresent(), "the completing frame yields the computed overlay");
        assertSame(result.get(), state.current(), "the returned overlay is the engine's current one");
        assertTrue(state.isVisible(), "a freshly received overlay is shown");
        state.tickEngine(64); // the seed marks sections dirty; the tick re-diffs them into boxes
        assertFalse(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "the engine computes boxes from the live world after the seed + tick");
    }

    @Test
    void newPushReseedsEngine() {
        // Migrated: a second push re-seeds (HEAD-move reset semantics in the engine) and the overlay
        // still reflects the live world; visibility stays on.
        FakeLevelAccess level = sampleLevel();
        OverlayClientState state = seededState(level);
        feedAll(state, payload(), 16, 10L);
        state.tickEngine(64);
        assertNotNull(state.current(), "sanity: overlay computed after first push");

        feedAll(state, payload(), 16, 500L);
        state.tickEngine(64);

        assertNotNull(state.current(), "the overlay survives a re-seed");
        assertFalse(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "and still reflects the live world after the re-seed");
        assertTrue(state.isVisible(), "the replacement is shown");
    }

    @Test
    void acceptFrameSeedsEngineAndComputesOverlayFromLiveWorld() {
        net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess level =
            new net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess(
                net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD, 0, 1);
        level.addLoadedChunk(0, 0);
        // Set the live world so it matches the working-tree (newState) of one entry in the sample diff,
        // so the engine computes at least one box. The sample has change(3,5,3, DIRT->STONE):
        level.setBlock(3, 5, 3, new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:stone"));

        OverlayClientState state = new OverlayClientState();
        state.setLevelSupplier(() -> level);
        state.setActiveDimension(net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD);
        feedAll(state, payload(), Framing.DEFAULT_MAX_FRAME_BYTES, 0L);
        state.tickEngine(64);

        org.junit.jupiter.api.Assertions.assertNotNull(state.current());
        org.junit.jupiter.api.Assertions.assertTrue(state.current().boxes(
            net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD).size() >= 1);
    }

    @Test
    void onDisconnectResetsEngine() {
        OverlayClientState state = new OverlayClientState();
        state.onDisconnect();
        org.junit.jupiter.api.Assertions.assertNull(state.current());
    }

    // ---- keybind = subscription toggle (issue #92) ---------------------------------------------

    @Test
    void firstToggleSubscribesAndSendsSubscribe() {
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();

        DiffControl control = state.toggleSubscription(sender);

        assertEquals(DiffControl.SUBSCRIBE, control, "first toggle subscribes");
        assertTrue(state.isSubscribed(), "now subscribed");
        assertEquals(Arrays.asList(DiffControl.SUBSCRIBE), sender.sent, "SUBSCRIBE went on the wire");
    }

    @Test
    void subscribeWhileEmptyStillSubscribesThenShowsIncomingPush() {
        // The inversion's whole point: subscribing with nothing held is valid — it is what makes the
        // server start pushing. Nothing renders until the first push arrives.
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();

        state.toggleSubscription(sender);
        assertTrue(state.isSubscribed());
        assertNull(state.current(), "no overlay held yet");
        assertFalse(state.isVisible(), "nothing to show before the first push");

        state.setLevelSupplier(() -> sampleLevel());
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 100L);
        state.tickEngine(64);
        assertFalse(state.current() == null, "the pushed overlay is now computed");
        assertTrue(state.isVisible(), "and shown while subscribed");
    }

    @Test
    void secondToggleUnsubscribesSendsUnsubscribeAndClearsHeldOverlay() {
        OverlayClientState state = seededState(sampleLevel());
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);          // SUBSCRIBE
        feedAll(state, payload(), 16, 0L);          // a push lands
        state.tickEngine(64);
        assertFalse(state.current() == null, "sanity: overlay computed while subscribed");

        DiffControl control = state.toggleSubscription(sender);   // UNSUBSCRIBE

        assertEquals(DiffControl.UNSUBSCRIBE, control, "second toggle unsubscribes");
        assertFalse(state.isSubscribed(), "no longer subscribed");
        assertNull(state.current(), "UNSUB resets the engine — nothing computed");
        assertFalse(state.isVisible());
        assertEquals(Arrays.asList(DiffControl.SUBSCRIBE, DiffControl.UNSUBSCRIBE), sender.sent,
                "SUBSCRIBE then UNSUBSCRIBE on the wire");
    }

    @Test
    void toggleAlternatesControlAcrossPresses() {
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();

        state.toggleSubscription(sender);
        state.toggleSubscription(sender);
        state.toggleSubscription(sender);

        assertEquals(
                Arrays.asList(DiffControl.SUBSCRIBE, DiffControl.UNSUBSCRIBE, DiffControl.SUBSCRIBE),
                sender.sent);
        assertTrue(state.isSubscribed(), "odd number of presses leaves it subscribed");
    }

    @Test
    void failedSubscribeSendLeavesClientUnsubscribed() {
        // On a server that doesn't speak minegit:diffsub (plugin/vanilla), the loader send throws
        // (NeoForge's checkPacket: "Payload minegit:diffsub may not be sent to the server!"). The
        // toggle sends BEFORE mutating, so a throw leaves the subscription honest — the client is not
        // marked subscribed to a server that never received the SUBSCRIBE.
        OverlayClientState state = new OverlayClientState();
        OverlayClientState.ControlSender boom = c -> {
            throw new IllegalStateException("channel not negotiated");
        };

        assertThrows(IllegalStateException.class, () -> state.toggleSubscription(boom));

        assertFalse(state.isSubscribed(), "a failed SUBSCRIBE send leaves the client unsubscribed");
        assertFalse(state.isVisible(), "and nothing made visible");
    }

    @Test
    void failedUnsubscribeSendKeepsSubscriptionAndOverlay() {
        OverlayClientState state = seededState(sampleLevel());
        RecordingSender ok = new RecordingSender();
        state.toggleSubscription(ok);                 // SUBSCRIBE lands
        feedAll(state, payload(), 16, 0L);             // overlay computed
        state.tickEngine(64);
        OverlayClientState.ControlSender boom = c -> {
            throw new IllegalStateException("connection gone");
        };

        assertThrows(IllegalStateException.class, () -> state.toggleSubscription(boom));

        assertTrue(state.isSubscribed(), "a failed UNSUBSCRIBE leaves the subscription live");
        assertFalse(state.current() == null, "overlay not cleared when the UNSUB never reached the server");
    }

    // ---- lifecycle: dimension change, disconnect (no auto-expire) ------------------------------

    @Test
    void dimensionChangeDropsOldDimensionsBoxes() {
        // Migrated: a dimension change no longer clears the whole holder — it drops the *old*
        // dimension from the engine (so its diff can't bleed into the new level). The overlay then
        // has no boxes for the now-active dimension, so nothing renders until a fresh push lands.
        OverlayClientState state = seededState(sampleLevel());
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);
        assertFalse(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "sanity: overworld boxes computed before dimension change");

        state.setActiveDimension(DimensionId.THE_NETHER);

        assertTrue(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "the old dimension's boxes are dropped on dimension change");
        assertFalse(state.shouldRender(), "nothing to draw in the new dimension yet");
        assertEquals(DimensionId.THE_NETHER, state.activeDimension());
    }

    @Test
    void sameDimensionDoesNotDrop() {
        OverlayClientState state = seededState(sampleLevel());
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);

        state.setActiveDimension(DimensionId.OVERWORLD);

        assertFalse(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "re-setting the same dimension keeps the computed overlay");
    }

    @Test
    void dimensionChangeClearsOverlayButKeepsSubscription() {
        // Spec C batch 2 §2.2/§2.3: a dimension change clears the held overlay (a diff for one
        // dimension must not bleed into another), but the subscription stays live — the server
        // recomputes for the new level and pushes a fresh overlay; no UNSUB is sent.
        OverlayClientState state = seededState(sampleLevel());
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);

        state.setActiveDimension(DimensionId.THE_NETHER);

        assertTrue(state.current().boxes(DimensionId.OVERWORLD).isEmpty(),
                "the old dimension's boxes are dropped on dimension change");
        assertTrue(state.isSubscribed(), "but the subscription stays live (no UNSUB)");
        assertEquals(Arrays.asList(DiffControl.SUBSCRIBE), sender.sent, "no extra control sent");
    }

    @Test
    void disconnectClearsOverlayAndDimension() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);

        state.onDisconnect();

        assertNull(state.current(), "disconnect clears the overlay");
        assertFalse(state.isVisible());
        assertNull(state.activeDimension(), "disconnect forgets the active dimension");
    }

    @Test
    void overlayDoesNotSelfExpireWhileSubscribed() {
        // Auto-expire is retired from the live model (issue #92): a held overlay is never dropped by
        // the passage of time — only toggle-off, disconnect, or dimension change clear it.
        OverlayClientState state = seededState(sampleLevel());
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);

        // No tick clock to advance, no expiry call — the overlay simply stays.
        assertFalse(state.current() == null, "overlay survives indefinitely while subscribed");
        assertTrue(state.shouldRender(), "and keeps rendering");
    }

    // ---- render decision -----------------------------------------------------------------------

    @Test
    void shouldRenderRequiresVisibleHeldMatchingDimension() {
        OverlayClientState state = seededState(sampleLevel());
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);

        assertTrue(state.shouldRender(), "visible, held, dimension matches");

        state.toggleSubscription(sender);   // UNSUB resets the engine
        assertFalse(state.shouldRender(), "unsubscribed → nothing to draw");
    }

    @Test
    void shouldRenderFalseWhenDimensionHasNoBoxes() {
        OverlayClientState state = seededState(sampleLevel());
        feedAll(state, payload(), 16, 0L);
        state.tickEngine(64);
        assertTrue(state.shouldRender(), "sanity: overworld boxes render");
        // Move to a dimension the overlay has no boxes for: setActiveDimension drops the old dim, so a
        // re-push would be needed; here we assert the computed overlay has nothing for the new dim.
        state.setActiveDimension(DimensionId.THE_END);

        assertFalse(state.shouldRender(), "no boxes for the active dimension → nothing to draw");
    }

    @Test
    void clearResetsEverythingIncludingPartialFrames() {
        OverlayClientState state = new OverlayClientState();
        List<Frame> frames = Framing.frame(payload(), 16);
        state.acceptFrame(frames.get(0).toBytes(), 0L); // partial, mid-session

        state.clear();

        // After clear, feeding the *remaining* frames must not silently complete the old session —
        // a fresh full payload is required.
        Optional<OverlayState> tail = Optional.empty();
        for (int i = 1; i < frames.size(); i++) {
            tail = state.acceptFrame(frames.get(i).toBytes(), 0L);
        }
        assertFalse(tail.isPresent(), "clear drops the in-flight reassembly session");
    }
}
