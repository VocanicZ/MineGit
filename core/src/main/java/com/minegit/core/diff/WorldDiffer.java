package com.minegit.core.diff;

import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import com.minegit.core.model.WorldDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The MineGit block-diff engine. Compares two {@link ChunkSource}s block-for-block and produces a
 * {@link WorldDiff} of air-aware {@link BlockChange}s with aggregate summary counts.
 *
 * <p>The single hot path is {@link #diff(ChunkSource, ChunkSource)}; {@code status} and {@code diff}
 * reuse it through the convenience overloads ({@link #diffWorkingTree} for live-vs-HEAD and
 * {@link #diffRefs} for ref-vs-ref). For every chunk position present in <em>either</em> source the
 * engine short-circuits when the two {@link NormalizedChunk}s are equal, and otherwise walks every
 * block position, emitting:
 *
 * <ul>
 *   <li>{@link BlockChange.Kind#ADD} where {@code a} is air/absent and {@code b} is solid,</li>
 *   <li>{@link BlockChange.Kind#REMOVE} where {@code a} is solid and {@code b} is air/absent,</li>
 *   <li>{@link BlockChange.Kind#CHANGE} where both are solid but differ.</li>
 * </ul>
 *
 * <p>{@code a} is the "before" side and {@code b} the "after" side. Output is deterministic: chunk
 * diffs are ordered by {@code (cx, cz)} and block changes within a chunk by section then
 * {@code (y, z, x)}. No Minecraft dependencies.
 */
public final class WorldDiffer {

    private static final int SIZE = 16;

    private WorldDiffer() {}

    /** Diffs the world's current state ({@code live}) against the committed {@code HEAD}. */
    public static WorldDiff diffWorkingTree(MineGitRepo repo, WorldAdapter adapter) {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(adapter, "adapter");
        return diff(ChunkSources.tree(repo, "HEAD"), ChunkSources.live(adapter));
    }

    /** Diffs two committed revisions, {@code revA} (before) against {@code revB} (after). */
    public static WorldDiff diffRefs(MineGitRepo repo, String revA, String revB) {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(revA, "revA");
        Objects.requireNonNull(revB, "revB");
        // Fail loudly on an unresolvable ref instead of letting it collapse to an empty tree and
        // emit a misleading "everything removed" diff.
        repo.requireRef(revA);
        repo.requireRef(revB);
        return diff(ChunkSources.tree(repo, revA), ChunkSources.tree(repo, revB));
    }

    /**
     * Core block-diff over the union of chunk positions in {@code a} (before) and {@code b} (after).
     */
    public static WorldDiff diff(ChunkSource a, ChunkSource b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");

        Map<DimensionId, List<ChunkDiff>> byDim = new HashMap<DimensionId, List<ChunkDiff>>();
        int added = 0;
        int removed = 0;
        int changed = 0;

        Set<DimensionId> dims = new HashSet<DimensionId>(a.dimensions());
        dims.addAll(b.dimensions());

        for (DimensionId dim : dims) {
            Set<ChunkPos> positions = new HashSet<ChunkPos>(a.chunks(dim));
            positions.addAll(b.chunks(dim));

            List<ChunkPos> ordered = new ArrayList<ChunkPos>(positions);
            ordered.sort(CHUNK_POS_ORDER);

            List<ChunkDiff> chunkDiffs = new ArrayList<ChunkDiff>();
            for (ChunkPos pos : ordered) {
                NormalizedChunk ca = a.read(dim, pos);
                NormalizedChunk cb = b.read(dim, pos);
                if (Objects.equals(ca, cb)) {
                    continue; // equal chunks (incl. both absent) short-circuit
                }
                List<BlockChange> changes = diffChunk(pos, ca, cb);
                for (BlockChange c : changes) {
                    switch (c.getKind()) {
                        case ADD:
                            added++;
                            break;
                        case REMOVE:
                            removed++;
                            break;
                        case CHANGE:
                            changed++;
                            break;
                        default:
                            break;
                    }
                }
                if (!changes.isEmpty()) {
                    chunkDiffs.add(new ChunkDiff(pos, changes));
                }
            }
            if (!chunkDiffs.isEmpty()) {
                byDim.put(dim, chunkDiffs);
            }
        }

        return new WorldDiff(byDim, added, removed, changed);
    }

    /** Per-chunk block diff over the union of the two chunks' vertical (section) ranges. */
    private static List<BlockChange> diffChunk(ChunkPos pos, NormalizedChunk a, NormalizedChunk b) {
        int cx = pos.getCx();
        int cz = pos.getCz();

        NormalizedSection[] sa = a != null ? a.getSections() : null;
        NormalizedSection[] sb = b != null ? b.getSections() : null;

        int loSection = Integer.MAX_VALUE;
        int hiSection = Integer.MIN_VALUE; // exclusive
        if (a != null) {
            loSection = Math.min(loSection, a.getMinSection());
            hiSection = Math.max(hiSection, a.getMinSection() + sa.length);
        }
        if (b != null) {
            loSection = Math.min(loSection, b.getMinSection());
            hiSection = Math.max(hiSection, b.getMinSection() + sb.length);
        }

        List<BlockChange> out = new ArrayList<BlockChange>();
        for (int sectionY = loSection; sectionY < hiSection; sectionY++) {
            NormalizedSection secA = sectionAt(a, sa, sectionY);
            NormalizedSection secB = sectionAt(b, sb, sectionY);
            if (secA == null && secB == null) {
                continue;
            }
            if (secA != null && secA.equals(secB)) {
                continue; // identical section short-circuit
            }
            int[] idxA = secA != null ? secA.getIndices() : null;
            int[] idxB = secB != null ? secB.getIndices() : null;
            List<BlockState> palA = secA != null ? secA.getPalette() : null;
            List<BlockState> palB = secB != null ? secB.getPalette() : null;

            for (int ly = 0; ly < SIZE; ly++) {
                for (int lz = 0; lz < SIZE; lz++) {
                    for (int lx = 0; lx < SIZE; lx++) {
                        int local = ly * 256 + lz * 16 + lx;
                        BlockState stA = secA != null ? palA.get(idxA[local]) : BlockState.AIR;
                        BlockState stB = secB != null ? palB.get(idxB[local]) : BlockState.AIR;
                        if (stA.equals(stB)) {
                            continue;
                        }
                        int wx = cx * 16 + lx;
                        int wy = sectionY * 16 + ly;
                        int wz = cz * 16 + lz;
                        boolean aAir = stA.equals(BlockState.AIR);
                        boolean bAir = stB.equals(BlockState.AIR);
                        if (aAir) {
                            out.add(BlockChange.add(wx, wy, wz, stB));
                        } else if (bAir) {
                            out.add(BlockChange.remove(wx, wy, wz, stA));
                        } else {
                            out.add(BlockChange.change(wx, wy, wz, stA, stB));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The section at section-Y {@code sectionY} in {@code chunk}, or {@code null} (absent/all-air). */
    private static NormalizedSection sectionAt(
            NormalizedChunk chunk, NormalizedSection[] sections, int sectionY) {
        if (chunk == null) {
            return null;
        }
        int idx = sectionY - chunk.getMinSection();
        if (idx < 0 || idx >= sections.length) {
            return null;
        }
        return sections[idx];
    }

    private static final Comparator<ChunkPos> CHUNK_POS_ORDER =
            new Comparator<ChunkPos>() {
                @Override
                public int compare(ChunkPos p, ChunkPos q) {
                    if (p.getCx() != q.getCx()) {
                        return Integer.compare(p.getCx(), q.getCx());
                    }
                    return Integer.compare(p.getCz(), q.getCz());
                }
            };
}
