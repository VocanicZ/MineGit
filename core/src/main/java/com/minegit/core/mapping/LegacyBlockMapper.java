package com.minegit.core.mapping;

import com.minegit.core.model.BlockState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps 1.8 numeric {@code (blockId, meta)} pairs to flattened (1.13+) {@link BlockState}s.
 *
 * <p>This is the platform-agnostic half of legacy block translation: it deals in <strong>pure
 * ints</strong> and carries no Minecraft / Bukkit types. The future {@code v1_8} plugin module is
 * responsible for turning Bukkit {@code MaterialData} into {@code (id, meta)} before calling
 * {@link #map(int, int)}; modern (1.13+) servers already emit flattened ids and never need this.
 *
 * <p>The mapping is data-driven: it is loaded once from a bundled classpath resource
 * ({@code legacy-blocks.tsv}) covering <strong>common</strong> blocks. The full ~4000-entry table is
 * a deferred data-fill follow-up. Any {@code (id, meta)} not present in the table maps to a flagged
 * fallback {@code minegit:unknown} state carrying {@code legacy_id} / {@code legacy_meta} properties,
 * so unrecognised blocks are never silently lost.
 *
 * <p>Instances are immutable after construction and safe to share across threads.
 */
public final class LegacyBlockMapper {

    /** Namespaced id used for blocks absent from the table. */
    public static final String UNKNOWN_ID = "minegit:unknown";

    /** Default bundled table resource path (classpath-relative). */
    private static final String DEFAULT_RESOURCE = "/com/minegit/core/mapping/legacy-blocks.tsv";

    /** A legacy 1.8 numeric block coordinate. */
    public static final class LegacyId {
        public final int blockId;
        public final int meta;

        public LegacyId(int blockId, int meta) {
            this.blockId = blockId;
            this.meta = meta;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LegacyId)) {
                return false;
            }
            LegacyId that = (LegacyId) o;
            return blockId == that.blockId && meta == that.meta;
        }

        @Override
        public int hashCode() {
            return blockId * 31 + meta;
        }

        @Override
        public String toString() {
            return blockId + ":" + meta;
        }
    }

    private final Map<Long, BlockState> forward;
    private final Map<BlockState, LegacyId> reverse;

    /** Loads the default bundled common-blocks table. */
    public LegacyBlockMapper() {
        this(DEFAULT_RESOURCE);
    }

    /** Loads a table from the given classpath resource path. Package-visible for testing. */
    LegacyBlockMapper(String resourcePath) {
        Map<Long, BlockState> fwd = new HashMap<Long, BlockState>();
        Map<BlockState, LegacyId> rev = new HashMap<BlockState, LegacyId>();
        // Track which flattened states are reversible (a state reachable from exactly one legacy
        // coord). If two legacy coords flatten to the same state, reverse is ambiguous -> dropped.
        Map<BlockState, Boolean> ambiguous = new HashMap<BlockState, Boolean>();

        try (InputStream in = openResource(resourcePath)) {
            parse(in, fwd, rev, ambiguous);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading legacy block table: " + resourcePath, e);
        }

        for (Map.Entry<BlockState, Boolean> e : ambiguous.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                rev.remove(e.getKey());
            }
        }

        this.forward = Collections.unmodifiableMap(fwd);
        this.reverse = Collections.unmodifiableMap(rev);
    }

    private static InputStream openResource(String resourcePath) {
        InputStream in = LegacyBlockMapper.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("legacy block table resource not found: " + resourcePath);
        }
        return in;
    }

    private static void parse(
            InputStream in,
            Map<Long, BlockState> fwd,
            Map<BlockState, LegacyId> rev,
            Map<BlockState, Boolean> ambiguous)
            throws IOException {
        BufferedReader r =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        while ((line = r.readLine()) != null) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            String[] cols = line.split("\t");
            if (cols.length < 3) {
                throw new IllegalStateException(
                        "malformed legacy block table at line " + lineNo + ": " + line);
            }
            int blockId = parseInt(cols[0], lineNo);
            int meta = parseInt(cols[1], lineNo);
            String flattenedId = cols[2].trim();
            Map<String, String> props =
                    cols.length >= 4 ? parseProps(cols[3], lineNo) : Collections.<String, String>emptyMap();

            BlockState state = new BlockState(flattenedId, props);
            long key = key(blockId, meta);
            if (fwd.put(key, state) != null) {
                throw new IllegalStateException(
                        "duplicate legacy key " + blockId + ":" + meta + " at line " + lineNo);
            }
            if (rev.containsKey(state)) {
                ambiguous.put(state, Boolean.TRUE);
            } else {
                rev.put(state, new LegacyId(blockId, meta));
            }
        }
    }

    private static int parseInt(String s, int lineNo) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "non-numeric id/meta in legacy block table at line " + lineNo + ": " + s, e);
        }
    }

    private static Map<String, String> parseProps(String col, int lineNo) {
        String trimmed = col.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> props = new LinkedHashMap<String, String>();
        for (String pair : trimmed.split(";")) {
            String p = pair.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(
                        "malformed prop '" + p + "' at line " + lineNo);
            }
            props.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return props;
    }

    private static long key(int blockId, int meta) {
        return (((long) blockId) << 8) | (meta & 0xFFL);
    }

    /**
     * Maps a 1.8 {@code (blockId, meta)} pair to its flattened {@link BlockState}.
     *
     * <p>If the pair is not in the table, returns the flagged fallback {@code minegit:unknown} state
     * carrying {@code legacy_id} and {@code legacy_meta} properties.
     */
    public BlockState map(int blockId, int meta) {
        BlockState state = forward.get(key(blockId, meta));
        if (state != null) {
            return state;
        }
        Map<String, String> props = new TreeMap<String, String>();
        props.put("legacy_id", Integer.toString(blockId));
        props.put("legacy_meta", Integer.toString(meta));
        return new BlockState(UNKNOWN_ID, props);
    }

    /**
     * Reverse lookup: the legacy coord that maps to {@code state}, or {@code null} if the state is not
     * in the table or is reachable from more than one legacy coord (ambiguous, not reversible).
     */
    public LegacyId reverse(BlockState state) {
        return reverse.get(state);
    }

    /** Number of legacy {@code (id, meta)} entries loaded from the table. */
    public int size() {
        return forward.size();
    }
}
