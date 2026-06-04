package net.rainbowcreation.vocanicz.minegit.cli;

import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent fake-world state for the {@code minegit} CLI, stored as {@code world.json} in the repo
 * dir so that {@code set} mutations compose across separate CLI invocations (Spec A §10). The store
 * is an ordered map of {@code (dimension, x, y, z) -> blockId}; {@link #toAdapter()} replays it into
 * a fresh {@link FakeWorldAdapter}, the in-memory {@code WorldAdapter} core already ships, so the CLI
 * drives the real engine with zero Minecraft.
 */
final class FakeWorldStore {

    static final String FILE_NAME = "world.json";

    private final int minSection;
    private final int sectionCount;
    private final Map<BlockKey, String> blocks;

    private FakeWorldStore(int minSection, int sectionCount, Map<BlockKey, String> blocks) {
        this.minSection = minSection;
        this.sectionCount = sectionCount;
        this.blocks = blocks;
    }

    /** Loads the store from {@code repoDir/world.json}, or an empty store if the file is absent. */
    static FakeWorldStore load(Path repoDir) {
        Objects.requireNonNull(repoDir, "repoDir");
        Path file = repoDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new FakeWorldStore(
                    FakeWorldDefaults.MIN_SECTION,
                    FakeWorldDefaults.SECTION_COUNT,
                    new LinkedHashMap<BlockKey, String>());
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            int minSection = intValue(root.get("minSection"), FakeWorldDefaults.MIN_SECTION);
            int sectionCount = intValue(root.get("sectionCount"), FakeWorldDefaults.SECTION_COUNT);
            Map<BlockKey, String> blocks = new LinkedHashMap<BlockKey, String>();
            Object rawBlocks = root.get("blocks");
            if (rawBlocks instanceof List) {
                for (Object item : (List<?>) rawBlocks) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> b = (Map<String, Object>) item;
                    String dim = (String) b.get("dim");
                    int x = intValue(b.get("x"), 0);
                    int y = intValue(b.get("y"), 0);
                    int z = intValue(b.get("z"), 0);
                    String id = (String) b.get("id");
                    blocks.put(new BlockKey(dim, x, y, z), id);
                }
            }
            return new FakeWorldStore(minSection, sectionCount, blocks);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }

    /** Records {@code id} at {@code (dim, x, y, z)}, overwriting any previous value there. */
    void set(String dim, int x, int y, int z, String id) {
        Objects.requireNonNull(dim, "dim");
        Objects.requireNonNull(id, "id");
        blocks.put(new BlockKey(dim, x, y, z), id);
    }

    /** Writes the store to {@code repoDir/world.json} in a deterministic shape. */
    void save(Path repoDir) {
        Objects.requireNonNull(repoDir, "repoDir");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("minSection", (long) minSection);
        root.put("sectionCount", (long) sectionCount);
        List<Object> list = new ArrayList<Object>();
        for (Map.Entry<BlockKey, String> e : blocks.entrySet()) {
            BlockKey k = e.getKey();
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            b.put("dim", k.dim);
            b.put("x", (long) k.x);
            b.put("y", (long) k.y);
            b.put("z", (long) k.z);
            b.put("id", e.getValue());
            list.add(b);
        }
        root.put("blocks", list);
        try {
            Files.write(
                    repoDir.resolve(FILE_NAME),
                    Json.write(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + FILE_NAME, e);
        }
    }

    /**
     * Mirrors a {@code checkout}/{@code pull} delta into the persistent store so {@code world.json}
     * stays in lock-step with the in-memory adapter: an {@link BlockChange.Kind#ADD}/{@code CHANGE}
     * records the new block id, a {@link BlockChange.Kind#REMOVE} deletes the key (back to air).
     */
    void apply(WorldDiff diff) {
        Objects.requireNonNull(diff, "diff");
        for (Map.Entry<DimensionId, List<ChunkDiff>> e : diff.getDimensions().entrySet()) {
            String dim = e.getKey().getId();
            for (ChunkDiff chunkDiff : e.getValue()) {
                for (BlockChange c : chunkDiff.getChanges()) {
                    BlockKey key = new BlockKey(dim, c.getX(), c.getY(), c.getZ());
                    if (c.getNewState() != null) {
                        blocks.put(key, c.getNewState().getId());
                    } else {
                        blocks.remove(key);
                    }
                }
            }
        }
    }

    /**
     * Captures the full block content of {@code adapter} into a new store, so a freshly {@code clone}d
     * (materialized) world can be persisted to {@code world.json}. Each non-air block of every chunk in
     * {@link FakeWorldAdapter#allChunks()} is recorded; the store then round-trips back through
     * {@link #toAdapter()} to a world identical to the materialized one.
     */
    static FakeWorldStore fromAdapter(FakeWorldAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        Map<BlockKey, String> blocks = new LinkedHashMap<BlockKey, String>();
        for (net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef ref : adapter.allChunks()) {
            String dim = ref.getDimension().getId();
            net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk chunk =
                    adapter.read(ref.getDimension(), ref.getPos());
            if (chunk == null) {
                continue;
            }
            int cx = chunk.getCx();
            int cz = chunk.getCz();
            net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection section = sections[s];
                if (section == null) {
                    continue;
                }
                int sectionY = chunk.getMinSection() + s;
                List<BlockState> palette = section.getPalette();
                int[] indices = section.getIndices();
                for (int i = 0; i < net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection.VOLUME; i++) {
                    BlockState state = palette.get(indices[i]);
                    if (state.equals(BlockState.AIR)) {
                        continue;
                    }
                    int x = cx * 16 + i % 16;
                    int y = sectionY * 16 + i / 256;
                    int z = cz * 16 + (i % 256) / 16;
                    blocks.put(new BlockKey(dim, x, y, z), state.getId());
                }
            }
        }
        return new FakeWorldStore(adapter.getMinSection(), adapter.getSectionCount(), blocks);
    }

    /** Replays the stored blocks into a fresh in-memory {@link FakeWorldAdapter}. */
    FakeWorldAdapter toAdapter() {
        FakeWorldAdapter adapter = new FakeWorldAdapter(minSection, sectionCount);
        for (Map.Entry<BlockKey, String> e : blocks.entrySet()) {
            BlockKey k = e.getKey();
            adapter.setBlock(new DimensionId(k.dim), k.x, k.y, k.z, new BlockState(e.getValue()));
        }
        return adapter;
    }

    private static int intValue(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return fallback;
    }

    /** Default vertical range mirrors {@link FakeWorldAdapter}'s modern build range. */
    static final class FakeWorldDefaults {
        static final int MIN_SECTION = -4;
        static final int SECTION_COUNT = 24;

        private FakeWorldDefaults() {
        }
    }

    /** Immutable {@code (dimension, x, y, z)} key for the block map. */
    private static final class BlockKey {
        private final String dim;
        private final int x;
        private final int y;
        private final int z;

        BlockKey(String dim, int x, int y, int z) {
            this.dim = Objects.requireNonNull(dim, "dim");
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof BlockKey)) {
                return false;
            }
            BlockKey that = (BlockKey) o;
            return x == that.x && y == that.y && z == that.z && dim.equals(that.dim);
        }

        @Override
        public int hashCode() {
            int h = dim.hashCode();
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }
}
