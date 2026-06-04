package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mod's immutable, read-only {@link SnapshotWorldAdapter} (Spec D §5): chunks captured on the
 * server thread are served back from the captured map so the off-thread git step never touches the
 * live level. Mirrors the plugin's snapshot adapter, in core-only types.
 */
class SnapshotWorldAdapterTest {

    private static final DimensionId OVERWORLD = new DimensionId("overworld");

    private static NormalizedChunk chunkAt(int cx, int cz) {
        return new NormalizedChunk(cx, cz, 0, new NormalizedSection[0], new int[0],
                Collections.emptyList());
    }

    @Test
    void servesCapturedChunksAndDimensions() {
        ChunkRef ref = new ChunkRef(OVERWORLD, new ChunkPos(1, 2));
        Map<ChunkRef, NormalizedChunk> captured = new HashMap<ChunkRef, NormalizedChunk>();
        captured.put(ref, chunkAt(1, 2));
        SnapshotWorldAdapter snap =
                new SnapshotWorldAdapter(Collections.singleton(OVERWORLD), captured);

        assertEquals(Collections.singleton(OVERWORLD), snap.dimensions());
        assertEquals(chunkAt(1, 2), snap.read(OVERWORLD, new ChunkPos(1, 2)));
        assertTrue(snap.allChunks().contains(ref));
        assertEquals(snap.allChunks(), snap.drainDirty());
    }

    @Test
    void readsAnUncapturedRefAsNull() {
        SnapshotWorldAdapter snap = new SnapshotWorldAdapter(
                Collections.singleton(OVERWORLD), new HashMap<ChunkRef, NormalizedChunk>());
        assertNull(snap.read(OVERWORLD, new ChunkPos(9, 9)));
    }

    @Test
    void isReadOnly() {
        SnapshotWorldAdapter snap = new SnapshotWorldAdapter(
                Collections.singleton(OVERWORLD), new HashMap<ChunkRef, NormalizedChunk>());
        assertThrows(UnsupportedOperationException.class,
                () -> snap.apply(OVERWORLD, new ChunkPos(0, 0),
                        Collections.<BlockChange>emptyList()));
        assertThrows(UnsupportedOperationException.class,
                () -> snap.writeChunk(OVERWORLD, chunkAt(0, 0)));
    }
}
