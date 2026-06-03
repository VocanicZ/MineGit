package com.minegit.cli;

import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.DimensionId;
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
