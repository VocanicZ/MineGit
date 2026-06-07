package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        dims.put(DimensionId.OVERWORLD, Arrays.asList(
                chunk(0, 0,
                        BlockChange.add(1, 64, 1, STONE),
                        BlockChange.remove(2, 64, 2, DIRT),
                        BlockChange.change(3, 64, 3, DIRT, STONE))));
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
    void completedPayloadBuildsAndHoldsVisibleOverlay() {
        OverlayClientState state = new OverlayClientState();

        Optional<OverlayState> result = feedAll(state, payload(), 16, 100L);

        assertTrue(result.isPresent(), "the completing frame yields the overlay");
        assertSame(result.get(), state.current(), "the held overlay is the one just built");
        assertEquals(100L, state.current().getReceivedAt(), "receivedAt stamps the completing tick");
        assertEquals(1, state.current().getAdded());
        assertEquals(1, state.current().getRemoved());
        assertEquals(1, state.current().getChanged());
        assertTrue(state.isVisible(), "a freshly received overlay is shown");
    }

    @Test
    void newOverlayReplacesAndResetsExpiry() {
        OverlayClientState state = new OverlayClientState();
        feedAll(state, payload(), 16, 10L);
        OverlayState first = state.current();

        feedAll(state, payload(), 16, 500L);
        OverlayState second = state.current();

        assertSame(second, state.current(), "the newer overlay replaces the older");
        assertFalse(first == second, "replace-on-new builds a fresh overlay");
        assertEquals(500L, second.getReceivedAt(), "the replacement resets the expiry baseline");
        assertTrue(state.isVisible(), "the replacement is shown");
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

        feedAll(state, payload(), 16, 100L);
        assertFalse(state.current() == null, "the pushed overlay is now held");
        assertTrue(state.isVisible(), "and shown while subscribed");
    }

    @Test
    void secondToggleUnsubscribesSendsUnsubscribeAndClearsHeldOverlay() {
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);          // SUBSCRIBE
        feedAll(state, payload(), 16, 0L);          // a push lands
        assertFalse(state.current() == null, "sanity: overlay held while subscribed");

        DiffControl control = state.toggleSubscription(sender);   // UNSUBSCRIBE

        assertEquals(DiffControl.UNSUBSCRIBE, control, "second toggle unsubscribes");
        assertFalse(state.isSubscribed(), "no longer subscribed");
        assertNull(state.current(), "UNSUB clears the held overlay locally");
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
        OverlayClientState state = new OverlayClientState();
        RecordingSender ok = new RecordingSender();
        state.toggleSubscription(ok);                 // SUBSCRIBE lands
        feedAll(state, payload(), 16, 0L);             // overlay held
        OverlayClientState.ControlSender boom = c -> {
            throw new IllegalStateException("connection gone");
        };

        assertThrows(IllegalStateException.class, () -> state.toggleSubscription(boom));

        assertTrue(state.isSubscribed(), "a failed UNSUBSCRIBE leaves the subscription live");
        assertFalse(state.current() == null, "overlay not cleared when the UNSUB never reached the server");
    }

    // ---- lifecycle: dimension change, disconnect (no auto-expire) ------------------------------

    @Test
    void dimensionChangeClearsOverlay() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);
        assertFalse(state.current() == null, "sanity: overlay held before dimension change");

        state.setActiveDimension(DimensionId.THE_NETHER);

        assertNull(state.current(), "changing dimension auto-clears the held overlay");
        assertFalse(state.isVisible());
        assertEquals(DimensionId.THE_NETHER, state.activeDimension());
    }

    @Test
    void sameDimensionDoesNotClear() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);

        state.setActiveDimension(DimensionId.OVERWORLD);

        assertSame(state.current(), state.current());
        assertFalse(state.current() == null, "re-setting the same dimension keeps the overlay");
    }

    @Test
    void dimensionChangeClearsOverlayButKeepsSubscription() {
        // Spec C batch 2 §2.2/§2.3: a dimension change clears the held overlay (a diff for one
        // dimension must not bleed into another), but the subscription stays live — the server
        // recomputes for the new level and pushes a fresh overlay; no UNSUB is sent.
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();
        state.toggleSubscription(sender);
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);

        state.setActiveDimension(DimensionId.THE_NETHER);

        assertNull(state.current(), "the held overlay is cleared on dimension change");
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
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();
        state.setActiveDimension(DimensionId.OVERWORLD);
        state.toggleSubscription(sender);
        feedAll(state, payload(), 16, 0L);

        // No tick clock to advance, no expiry call — the overlay simply stays.
        assertFalse(state.current() == null, "overlay survives indefinitely while subscribed");
        assertTrue(state.shouldRender(), "and keeps rendering");
    }

    // ---- render decision -----------------------------------------------------------------------

    @Test
    void shouldRenderRequiresVisibleHeldMatchingDimension() {
        OverlayClientState state = new OverlayClientState();
        RecordingSender sender = new RecordingSender();
        state.setActiveDimension(DimensionId.OVERWORLD);
        state.toggleSubscription(sender);
        feedAll(state, payload(), 16, 0L);

        assertTrue(state.shouldRender(), "visible, held, dimension matches");

        state.toggleSubscription(sender);   // UNSUB clears the overlay
        assertFalse(state.shouldRender(), "unsubscribed → nothing to draw");
    }

    @Test
    void shouldRenderFalseWhenDimensionHasNoBoxes() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);
        // Move to a dimension the overlay has no changes for: setActiveDimension clears, so re-receive
        // would be needed; here we assert the cleared state simply does not render.
        state.setActiveDimension(DimensionId.THE_END);

        assertFalse(state.shouldRender(), "no overlay after dimension change → nothing to draw");
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
