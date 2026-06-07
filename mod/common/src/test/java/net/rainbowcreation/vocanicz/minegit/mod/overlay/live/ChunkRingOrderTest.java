package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkRingOrderTest {

    private static int chebyshev(int[] off) {
        return Math.max(Math.abs(off[0]), Math.abs(off[1]));
    }

    @Test
    void radiusZeroIsJustTheCenter() {
        List<int[]> o = ChunkRingOrder.centerOut(0);
        assertEquals(1, o.size());
        assertArrayEquals(new int[] {0, 0}, o.get(0));
    }

    @Test
    void centerIsFirstThenStrictlyNonDecreasingRings() {
        List<int[]> o = ChunkRingOrder.centerOut(3);
        assertArrayEquals(new int[] {0, 0}, o.get(0)); // player's own chunk seeds first
        int prev = 0;
        for (int[] off : o) {
            assertEquals(true, chebyshev(off) >= prev); // never jumps inward
            prev = chebyshev(off);
        }
    }

    @Test
    void coversTheFullSquareWithoutDuplicates() {
        int radius = 5;
        List<int[]> o = ChunkRingOrder.centerOut(radius);
        int side = 2 * radius + 1;
        assertEquals(side * side, o.size()); // 11x11 = 121 at the default overlay radius
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        for (int[] off : o) {
            assertEquals(true, Math.abs(off[0]) <= radius && Math.abs(off[1]) <= radius);
            assertEquals(true, seen.add(((long) off[0] << 32) ^ (off[1] & 0xffffffffL)));
        }
    }

    @Test
    void firstNineAreTheCenterAndItsEightNeighbours() {
        List<int[]> o = ChunkRingOrder.centerOut(4);
        for (int i = 0; i < 9; i++) {
            assertEquals(true, chebyshev(o.get(i)) <= 1); // center + ring 1 reachable-edit zone
        }
    }
}
