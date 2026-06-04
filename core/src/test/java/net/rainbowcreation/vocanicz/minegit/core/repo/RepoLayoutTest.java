package net.rainbowcreation.vocanicz.minegit.core.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class RepoLayoutTest {

    private final RepoLayout layout = new RepoLayout(Paths.get("/repo"));

    @Test
    void chunkPathBucketsByRegionPositive() {
        Path p = layout.chunkPath(DimensionId.OVERWORLD, new ChunkPos(33, 65));
        assertEquals(
            Paths.get("/repo/dimensions/overworld/region/r.1.2/c.33.65.mgc"),
            p);
    }

    @Test
    void chunkPathBucketsByRegionNegative() {
        // -1 >> 5 == -1, -33 >> 5 == -2 (arithmetic shift floors toward -inf)
        Path p = layout.chunkPath(DimensionId.THE_NETHER, new ChunkPos(-1, -33));
        assertEquals(
            Paths.get("/repo/dimensions/the_nether/region/r.-1.-2/c.-1.-33.mgc"),
            p);
    }

    @Test
    void chunkPathRoundTripsPositive() {
        DimensionId dim = DimensionId.OVERWORLD;
        ChunkPos pos = new ChunkPos(33, 65);
        Path p = layout.chunkPath(dim, pos);
        RepoLayout.ChunkRef ref = layout.parseChunkPath(p);
        assertEquals(dim, ref.getDimension());
        assertEquals(pos, ref.getPos());
    }

    @Test
    void chunkPathRoundTripsNegative() {
        DimensionId dim = DimensionId.THE_END;
        ChunkPos pos = new ChunkPos(-1, -33);
        Path p = layout.chunkPath(dim, pos);
        RepoLayout.ChunkRef ref = layout.parseChunkPath(p);
        assertEquals(dim, ref.getDimension());
        assertEquals(pos, ref.getPos());
    }

    @Test
    void blockEntityPath() {
        Path p = layout.blockEntityPath(DimensionId.OVERWORLD, new ChunkPos(-1, 2));
        assertEquals(
            Paths.get("/repo/dimensions/overworld/blockentities/be.-1.2.snbt"),
            p);
    }

    @Test
    void levelDatPath() {
        assertEquals(Paths.get("/repo/level/level.dat.snbt"), layout.levelDatPath());
    }

    @Test
    void minegitJsonPath() {
        assertEquals(Paths.get("/repo/minegit.json"), layout.minegitJsonPath());
    }
}
