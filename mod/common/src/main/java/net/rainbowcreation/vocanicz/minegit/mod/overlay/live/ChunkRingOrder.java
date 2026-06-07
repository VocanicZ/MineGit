package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk-offset ordering for baseline seeding (SP2): the player's own chunk first, then outward by
 * Chebyshev ring. The client baselines a bounded square of chunks around the player a few per tick
 * ({@code ClientDiffEngine.SEED_BUDGET}); ordering center-out guarantees the chunk the player is in
 * — and its immediate neighbours, where edits actually land — are frozen on the first tick(s), so a
 * block placed right after toggling the overlay still raises a box (rather than being baked into the
 * not-yet-frozen HEAD baseline). Pure / headless-testable.
 */
public final class ChunkRingOrder {

    private ChunkRingOrder() {}

    /**
     * The {@code (dx, dz)} chunk offsets within the {@code [-radius, radius]} square around the
     * origin, ordered center-out: index 0 is {@code (0, 0)}, then the full Chebyshev ring at
     * distance 1, then 2, … up to {@code radius}. {@code radius} must be &gt;= 0.
     */
    public static List<int[]> centerOut(int radius) {
        List<int[]> out = new ArrayList<int[]>();
        out.add(new int[] {0, 0});
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                out.add(new int[] {dx, -r}); // north edge of the ring
                out.add(new int[] {dx, r}); // south edge
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                out.add(new int[] {-r, dz}); // west edge (corners already emitted above)
                out.add(new int[] {r, dz}); // east edge
            }
        }
        return out;
    }
}
