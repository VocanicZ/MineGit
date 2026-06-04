package net.rainbowcreation.vocanicz.minegit.core.repo;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Deterministic mapping between MineGit repository contents and their on-disk paths.
 *
 * <p>Layout (relative to the repository root {@code repoDir}):
 *
 * <pre>
 * &lt;repoDir&gt;/
 *   minegit.json
 *   dimensions/&lt;dim&gt;/region/r.&lt;rx&gt;.&lt;rz&gt;/c.&lt;cx&gt;.&lt;cz&gt;.mgc   rx = cx&gt;&gt;5, rz = cz&gt;&gt;5
 *   dimensions/&lt;dim&gt;/blockentities/be.&lt;cx&gt;.&lt;cz&gt;.snbt
 *   level/level.dat.snbt
 * </pre>
 *
 * <p>One MineGit repository corresponds to one logical world (all of its dimensions). This class is
 * pure path arithmetic; it performs no I/O and has no Minecraft dependencies.
 */
public final class RepoLayout {

    /** Chunks per region edge: {@code 32 == 1 << REGION_SHIFT}. */
    public static final int REGION_SHIFT = 5;

    private final Path repoDir;

    public RepoLayout(Path repoDir) {
        this.repoDir = Objects.requireNonNull(repoDir, "repoDir");
    }

    public Path getRepoDir() {
        return repoDir;
    }

    /**
     * Resolves the region-bucketed {@code .mgc} path for a chunk. The region bucket is
     * {@code rx = cx >> 5}, {@code rz = cz >> 5} (arithmetic shift, so it floors toward negative
     * infinity for negative coordinates).
     */
    public Path chunkPath(DimensionId dim, ChunkPos pos) {
        Objects.requireNonNull(dim, "dim");
        Objects.requireNonNull(pos, "pos");
        int rx = pos.getCx() >> REGION_SHIFT;
        int rz = pos.getCz() >> REGION_SHIFT;
        return repoDir
            .resolve("dimensions")
            .resolve(dim.getId())
            .resolve("region")
            .resolve("r." + rx + "." + rz)
            .resolve("c." + pos.getCx() + "." + pos.getCz() + ".mgc");
    }

    /**
     * Inverse of {@link #chunkPath(DimensionId, ChunkPos)}: recovers the {@code (dimension, pos)}
     * pair from a {@code .mgc} path. The path may be absolute (under {@code repoDir}) or relative to
     * {@code repoDir}.
     *
     * @throws IllegalArgumentException if the path is not a well-formed chunk path for this layout
     */
    public ChunkRef parseChunkPath(Path path) {
        Objects.requireNonNull(path, "path");
        Path rel = path.isAbsolute() ? repoDir.relativize(path) : path;
        int n = rel.getNameCount();
        if (n < 5) {
            throw new IllegalArgumentException("not a chunk path: " + path);
        }
        if (!"dimensions".equals(rel.getName(0).toString())
            || !"region".equals(rel.getName(n - 3).toString())) {
            throw new IllegalArgumentException("not a chunk path: " + path);
        }
        String dimId = rel.getName(1).toString();
        String file = rel.getName(n - 1).toString();
        if (!file.startsWith("c.") || !file.endsWith(".mgc")) {
            throw new IllegalArgumentException("not a chunk file: " + path);
        }
        String coords = file.substring(2, file.length() - ".mgc".length());
        int dot = coords.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("not a chunk file: " + path);
        }
        int cx = Integer.parseInt(coords.substring(0, dot));
        int cz = Integer.parseInt(coords.substring(dot + 1));
        return new ChunkRef(new DimensionId(dimId), new ChunkPos(cx, cz));
    }

    /**
     * Resolves the block-entity SNBT path for a chunk:
     * {@code dimensions/<dim>/blockentities/be.<cx>.<cz>.snbt}.
     */
    public Path blockEntityPath(DimensionId dim, ChunkPos pos) {
        Objects.requireNonNull(dim, "dim");
        Objects.requireNonNull(pos, "pos");
        return repoDir
            .resolve("dimensions")
            .resolve(dim.getId())
            .resolve("blockentities")
            .resolve("be." + pos.getCx() + "." + pos.getCz() + ".snbt");
    }

    /** Resolves the normalized world-metadata path {@code level/level.dat.snbt}. */
    public Path levelDatPath() {
        return repoDir.resolve("level").resolve("level.dat.snbt");
    }

    /** Resolves the repository metadata path {@code minegit.json}. */
    public Path minegitJsonPath() {
        return repoDir.resolve("minegit.json");
    }

    /** A {@code (dimension, chunk position)} pair recovered from a chunk path. */
    public static final class ChunkRef {

        private final DimensionId dimension;
        private final ChunkPos pos;

        public ChunkRef(DimensionId dimension, ChunkPos pos) {
            this.dimension = Objects.requireNonNull(dimension, "dimension");
            this.pos = Objects.requireNonNull(pos, "pos");
        }

        public DimensionId getDimension() {
            return dimension;
        }

        public ChunkPos getPos() {
            return pos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ChunkRef)) {
                return false;
            }
            ChunkRef that = (ChunkRef) o;
            return dimension.equals(that.dimension) && pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return 31 * dimension.hashCode() + pos.hashCode();
        }

        @Override
        public String toString() {
            return "ChunkRef(" + dimension + ", " + pos + ")";
        }
    }
}
