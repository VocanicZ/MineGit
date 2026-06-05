package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- keybind toggle ------------------------------------------------------------------------

    @Test
    void toggleFlipsVisibilityWhenOverlayHeld() {
        OverlayClientState state = new OverlayClientState();
        feedAll(state, payload(), 16, 0L);
        assertTrue(state.isVisible());

        assertFalse(state.toggle(), "toggle hides a shown overlay and returns the new state");
        assertFalse(state.isVisible());
        assertTrue(state.toggle(), "toggle shows it again");
        assertTrue(state.isVisible());
    }

    @Test
    void toggleIsNoOpWithoutOverlay() {
        OverlayClientState state = new OverlayClientState();

        assertFalse(state.toggle(), "toggling with no overlay is a no-op, stays hidden");
        assertFalse(state.isVisible());
        assertNull(state.current());
    }

    // ---- lifecycle: dimension change, disconnect, expiry ---------------------------------------

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
    void tickExpiryClearsAfterLifetime() {
        OverlayClientState state = new OverlayClientState();
        feedAll(state, payload(), 16, 0L);
        long lifetime = 20L; // ticks

        assertFalse(state.tickExpiry(19L, lifetime), "not yet expired");
        assertFalse(state.current() == null, "still held before lifetime elapses");

        assertTrue(state.tickExpiry(20L, lifetime), "expires once now - receivedAt >= lifetime");
        assertNull(state.current(), "the expired overlay is cleared");
        assertFalse(state.isVisible());
    }

    @Test
    void tickExpiryNeverClearsWhenDisabled() {
        OverlayClientState state = new OverlayClientState();
        feedAll(state, payload(), 16, 0L);

        assertFalse(state.tickExpiry(1_000_000L, 0L), "lifetime <= 0 disables auto-expire");
        assertFalse(state.current() == null, "overlay survives with expiry disabled");
    }

    @Test
    void tickExpiryIsNoOpWithoutOverlay() {
        OverlayClientState state = new OverlayClientState();
        assertFalse(state.tickExpiry(100L, 20L), "no overlay, nothing to expire");
    }

    // ---- render decision -----------------------------------------------------------------------

    @Test
    void shouldRenderRequiresVisibleHeldUnexpiredMatchingDimension() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);

        assertTrue(state.shouldRender(0L, 60L), "visible, held, fresh, dimension matches");

        state.toggle();
        assertFalse(state.shouldRender(0L, 60L), "hidden overlay does not render");
        state.toggle();

        assertFalse(state.shouldRender(60L, 60L), "expired overlay does not render");
    }

    @Test
    void shouldRenderFalseWhenDimensionHasNoBoxes() {
        OverlayClientState state = new OverlayClientState();
        state.setActiveDimension(DimensionId.OVERWORLD);
        feedAll(state, payload(), 16, 0L);
        // Move to a dimension the overlay has no changes for: setActiveDimension clears, so re-receive
        // would be needed; here we assert the cleared state simply does not render.
        state.setActiveDimension(DimensionId.THE_END);

        assertFalse(state.shouldRender(0L, 60L), "no overlay after dimension change → nothing to draw");
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
