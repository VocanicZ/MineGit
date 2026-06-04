package com.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@link SnapshotWorldAdapter} is the read-only world image the plugin hands to core's
 * {@code commit} on the async git thread (Spec B §6): all Bukkit reads happened earlier on the main
 * thread, so this adapter serves the captured chunks and never touches the live world.
 */
class SnapshotWorldAdapterTest {

    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static final ChunkRef REF = new ChunkRef(DimensionId.OVERWORLD, POS);

    private static NormalizedChunk chunk() {
        return new NormalizedChunk(0, 0, 0, new com.minegit.core.model.NormalizedSection[0], new int[0],
                Collections.<com.minegit.core.model.BlockEntity>emptyList());
    }

    @Test
    void readReturnsTheCapturedChunk() {
        NormalizedChunk c = chunk();
        Map<ChunkRef, NormalizedChunk> captured = new HashMap<ChunkRef, NormalizedChunk>();
        captured.put(REF, c);

        SnapshotWorldAdapter snap =
                new SnapshotWorldAdapter(Collections.singleton(DimensionId.OVERWORLD), captured);

        assertEquals(c, snap.read(DimensionId.OVERWORLD, POS));
    }

    @Test
    void allChunksAndDrainDirtyReturnTheCapturedRefSet() {
        Map<ChunkRef, NormalizedChunk> captured = new HashMap<ChunkRef, NormalizedChunk>();
        captured.put(REF, chunk());

        SnapshotWorldAdapter snap =
                new SnapshotWorldAdapter(Collections.singleton(DimensionId.OVERWORLD), captured);

        Set<ChunkRef> all = snap.allChunks();
        assertTrue(all.contains(REF));
        assertEquals(all, snap.drainDirty());
    }

    @Test
    void readOfAnUncapturedChunkIsNull() {
        SnapshotWorldAdapter snap =
                new SnapshotWorldAdapter(Collections.singleton(DimensionId.OVERWORLD),
                        new HashMap<ChunkRef, NormalizedChunk>());

        assertNull(snap.read(DimensionId.OVERWORLD, POS));
    }

    @Test
    void applyIsRejectedBecauseTheSnapshotIsReadOnly() {
        SnapshotWorldAdapter snap =
                new SnapshotWorldAdapter(Collections.singleton(DimensionId.OVERWORLD),
                        new HashMap<ChunkRef, NormalizedChunk>());

        assertThrows(UnsupportedOperationException.class,
                () -> snap.apply(DimensionId.OVERWORLD, POS, Collections.<com.minegit.core.model.BlockChange>emptyList()));
    }
}
