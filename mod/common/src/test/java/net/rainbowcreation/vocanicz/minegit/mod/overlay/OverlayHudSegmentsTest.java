package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;

/**
 * Headless unit tests for {@link OverlayHud#segments(OverlayState, int)} — the pure HUD-text/color
 * helper (Spec C §3, issue #89). No GPU: asserts segment text + argb per count and the
 * {@code (+J more)} segment present iff {@code dropped > 0}.
 */
class OverlayHudSegmentsTest {

    private static final int GREEN = 0xFF000000 | OverlayColor.GREEN.rgb();
    private static final int RED = 0xFF000000 | OverlayColor.RED.rgb();
    private static final int YELLOW = 0xFF000000 | OverlayColor.YELLOW.rgb();
    private static final int GRAY = OverlayHud.GRAY_ARGB;

    /** An overlay with the given aggregate counts (no boxes needed for the HUD text). */
    private static OverlayState state(int added, int removed, int changed) {
        Map<DimensionId, List<ChunkDiff>> dims =
                new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Collections.<ChunkDiff>emptyList());
        WorldDiff diff = new WorldDiff(dims, added, removed, changed);
        return new OverlayState(diff, "main", "HEAD", 0L);
    }

    @Test
    void threeColoredCountsWhenNothingDropped() {
        List<OverlayHud.Segment> segs = OverlayHud.segments(state(2, 1, 3), 0);

        assertEquals(3, segs.size());

        assertEquals("+2", segs.get(0).getText());
        assertEquals(GREEN, segs.get(0).getArgb());

        assertEquals("-1", segs.get(1).getText());
        assertEquals(RED, segs.get(1).getArgb());

        assertEquals("~3", segs.get(2).getText());
        assertEquals(YELLOW, segs.get(2).getArgb());
    }

    @Test
    void grayMoreSegmentAppendedWhenDropped() {
        List<OverlayHud.Segment> segs = OverlayHud.segments(state(10, 0, 0), 7);

        assertEquals(4, segs.size());

        assertEquals("+10", segs.get(0).getText());
        assertEquals(GREEN, segs.get(0).getArgb());
        assertEquals("-0", segs.get(1).getText());
        assertEquals("~0", segs.get(2).getText());

        assertEquals("(+7 more)", segs.get(3).getText());
        assertEquals(GRAY, segs.get(3).getArgb());
    }

    @Test
    void noMoreSegmentWhenDroppedIsZeroOrNegative() {
        assertEquals(3, OverlayHud.segments(state(0, 0, 0), 0).size());
        assertEquals(3, OverlayHud.segments(state(0, 0, 0), -5).size());
    }

    @Test
    void zeroCountsStillRenderAllThreeSegments() {
        List<OverlayHud.Segment> segs = OverlayHud.segments(state(0, 0, 0), 0);
        assertEquals("+0", segs.get(0).getText());
        assertEquals("-0", segs.get(1).getText());
        assertEquals("~0", segs.get(2).getText());
    }
}
