package net.rainbowcreation.vocanicz.minegit.plugin.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection;
import net.rainbowcreation.vocanicz.minegit.plugin.block.BlockBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * {@link WorldAdapter} over a single bound Bukkit {@link World}, reading and writing blocks through a
 * {@link BlockBridge} (Spec B §4).
 *
 * <p>This first slice is <strong>blocks only</strong> (no block entities or biomes) and snapshots
 * <strong>currently loaded chunks</strong> ({@link World#getLoadedChunks()}); event-based dirty
 * tracking is a follow-up batch, so {@link #drainDirty()} returns the loaded set and downstream
 * determinism dedupes unchanged chunks. All Bukkit access is expected on the server main thread; the
 * frontend owns thread hopping (Spec B §6).
 *
 * <p>The adapter is bound to one world, hence one {@link DimensionId} (mapped from the world's
 * {@link World.Environment}); reads/writes for any other dimension are treated as absent.
 */
public final class BukkitWorldAdapter implements WorldAdapter {

    private static final int SECTION_SIZE = 16;

    private final World world;
    private final BlockBridge bridge;
    private final DimensionId dimension;
    private final int minSection;
    private final int sectionCount;

    /**
     * Binds the adapter to {@code world}, deriving its dimension from the world environment and its
     * vertical section range from the world height. The build range spans
     * {@code [getMinHeight(), getMaxHeight())} — {@code getMinHeight()} is read reflectively because
     * it is absent from the 1.8.8 API (pre-1.18 worlds start at Y=0).
     */
    public BukkitWorldAdapter(World world, BlockBridge bridge) {
        this.world = Objects.requireNonNull(world, "world");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.dimension = dimensionOf(world);
        int minHeight = minHeightOf(world);
        int maxHeight = world.getMaxHeight();
        this.minSection = Math.floorDiv(minHeight, SECTION_SIZE);
        this.sectionCount = Math.max(1, (maxHeight - minHeight) / SECTION_SIZE);
    }

    /** The dimension this world maps to (overworld / the_nether / the_end). */
    public DimensionId dimension() {
        return dimension;
    }

    @Override
    public Set<DimensionId> dimensions() {
        return Collections.singleton(dimension);
    }

    @Override
    public Set<ChunkRef> allChunks() {
        Set<ChunkRef> all = new HashSet<ChunkRef>();
        for (Chunk c : world.getLoadedChunks()) {
            all.add(new ChunkRef(dimension, new ChunkPos(c.getX(), c.getZ())));
        }
        return all;
    }

    @Override
    public Set<ChunkRef> drainDirty() {
        // First slice: no event-based dirty set. The loaded chunks are the candidate set; the engine
        // dedupes unchanged ones by content. Event tracking is the immediate follow-up (Spec B §4).
        return allChunks();
    }

    @Override
    public NormalizedChunk read(DimensionId requested, ChunkPos pos) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(pos, "pos");
        if (!dimension.equals(requested)) {
            return null;
        }
        Chunk chunk = world.getChunkAt(pos.getCx(), pos.getCz());
        if (chunk == null) {
            return null;
        }
        NormalizedSection[] sections = new NormalizedSection[sectionCount];
        for (int s = 0; s < sectionCount; s++) {
            sections[s] = readSection(chunk, s);
        }
        return new NormalizedChunk(
                pos.getCx(),
                pos.getCz(),
                minSection,
                sections,
                new int[0],
                Collections.<net.rainbowcreation.vocanicz.minegit.core.model.BlockEntity>emptyList());
    }

    @Override
    public void apply(DimensionId requested, ChunkPos pos, List<BlockChange> changes) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(changes, "changes");
        if (!dimension.equals(requested)) {
            return;
        }
        for (BlockChange c : changes) {
            BlockState target = c.getNewState() != null ? c.getNewState() : BlockState.AIR;
            Block block = world.getBlockAt(c.getX(), c.getY(), c.getZ());
            bridge.write(block, target);
        }
    }

    @Override
    public void writeChunk(DimensionId requested, NormalizedChunk chunk) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        if (!dimension.equals(requested)) {
            return;
        }
        int cx = chunk.getCx();
        int cz = chunk.getCz();
        NormalizedSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            NormalizedSection section = sections[s];
            if (section == null) {
                continue; // all-air section, nothing to place
            }
            int sectionY = chunk.getMinSection() + s;
            List<BlockState> palette = section.getPalette();
            int[] indices = section.getIndices();
            for (int i = 0; i < NormalizedSection.VOLUME; i++) {
                BlockState state = palette.get(indices[i]);
                if (state.equals(BlockState.AIR)) {
                    continue;
                }
                int localY = i / 256;
                int localZ = (i % 256) / 16;
                int localX = i % 16;
                Block block = world.getBlockAt(
                        cx * 16 + localX, sectionY * 16 + localY, cz * 16 + localZ);
                bridge.write(block, state);
            }
        }
    }

    /**
     * Reads section index {@code s} of {@code chunk} into a {@link NormalizedSection}, or {@code null}
     * if the section is all air (the model's empty-section convention). The palette is ordered by
     * first appearance scanning {@code Y*256 + Z*16 + X}.
     */
    private NormalizedSection readSection(Chunk chunk, int s) {
        int sectionY = minSection + s;
        List<BlockState> palette = new ArrayList<BlockState>();
        Map<BlockState, Integer> paletteIndex = new HashMap<BlockState, Integer>();
        int[] indices = new int[NormalizedSection.VOLUME];
        boolean anyNonAir = false;
        for (int i = 0; i < NormalizedSection.VOLUME; i++) {
            int localY = i / 256;
            int localZ = (i % 256) / 16;
            int localX = i % 16;
            Block block = chunk.getBlock(localX, sectionY * 16 + localY, localZ);
            BlockState state = bridge.read(block);
            if (state == null) {
                state = BlockState.AIR;
            }
            if (!state.equals(BlockState.AIR)) {
                anyNonAir = true;
            }
            Integer idx = paletteIndex.get(state);
            if (idx == null) {
                idx = palette.size();
                palette.add(state);
                paletteIndex.put(state, idx);
            }
            indices[i] = idx;
        }
        return anyNonAir ? new NormalizedSection(palette, indices) : null;
    }

    /** Maps a Bukkit world environment to the engine's {@link DimensionId}. */
    private static DimensionId dimensionOf(World world) {
        switch (world.getEnvironment()) {
            case NETHER:
                return DimensionId.THE_NETHER;
            case THE_END:
                return DimensionId.THE_END;
            case NORMAL:
            default:
                return DimensionId.OVERWORLD;
        }
    }

    /**
     * The lowest build Y of {@code world}. {@code World.getMinHeight()} only exists on 1.18+ servers
     * and is absent from the 1.8.8 compile classpath, so it is invoked reflectively; pre-1.18 worlds
     * start at Y=0.
     */
    private static int minHeightOf(World world) {
        try {
            Object result = World.class.getMethod("getMinHeight").invoke(world);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older API (or a mock without the method): worlds start at Y=0.
        }
        return 0;
    }
}
