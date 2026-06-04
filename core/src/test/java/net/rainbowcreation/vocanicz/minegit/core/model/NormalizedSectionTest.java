package net.rainbowcreation.vocanicz.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizedSectionTest {

    private static List<BlockState> palette() {
        return Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
    }

    private static int[] indices(int fill) {
        int[] idx = new int[4096];
        Arrays.fill(idx, fill);
        return idx;
    }

    @Test
    void equalByValue() {
        NormalizedSection a = new NormalizedSection(palette(), indices(1));
        NormalizedSection b = new NormalizedSection(palette(), indices(1));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void unequalWhenIndicesDiffer() {
        assertNotEquals(
                new NormalizedSection(palette(), indices(0)),
                new NormalizedSection(palette(), indices(1)));
    }

    @Test
    void unequalWhenPaletteDiffers() {
        List<BlockState> other = Arrays.asList(BlockState.AIR, new BlockState("minecraft:dirt"));
        assertNotEquals(
                new NormalizedSection(palette(), indices(1)),
                new NormalizedSection(other, indices(1)));
    }

    @Test
    void exposesPaletteAndIndices() {
        NormalizedSection s = new NormalizedSection(palette(), indices(1));
        assertEquals(palette(), s.getPalette());
        assertArrayEquals(indices(1), s.getIndices());
    }

    @Test
    void rejectsIndicesOfWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedSection(palette(), new int[10]));
    }

    @Test
    void paletteIsImmutable() {
        NormalizedSection s = new NormalizedSection(palette(), indices(1));
        assertThrows(UnsupportedOperationException.class,
                () -> s.getPalette().add(new BlockState("minecraft:dirt")));
    }

    @Test
    void mutatingReturnedIndicesDoesNotAffectSection() {
        NormalizedSection s = new NormalizedSection(palette(), indices(1));
        int[] got = s.getIndices();
        got[0] = 999;
        assertEquals(1, s.getIndices()[0]);
    }

    @Test
    void nullPaletteRejected() {
        assertThrows(NullPointerException.class,
                () -> new NormalizedSection(null, indices(0)));
    }

    @Test
    void emptyPaletteWithAllAirIndicesAllowed() {
        // A degenerate single-entry palette is the common "section is all one block" case.
        List<BlockState> air = Collections.singletonList(BlockState.AIR);
        NormalizedSection s = new NormalizedSection(air, indices(0));
        assertEquals(air, s.getPalette());
    }
}
