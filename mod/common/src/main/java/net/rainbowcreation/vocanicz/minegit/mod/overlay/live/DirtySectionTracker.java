package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/** Event-driven dirty-section set for the client live differ (Spec SP2 §2b). */
public final class DirtySectionTracker {

    /** A single dirty section address. */
    public static final class Section {
        private final DimensionId dimension;
        private final ChunkPos chunk;
        private final int sectionY;

        public Section(DimensionId dimension, ChunkPos chunk, int sectionY) {
            this.dimension = dimension;
            this.chunk = chunk;
            this.sectionY = sectionY;
        }

        public DimensionId dimension() { return dimension; }
        public ChunkPos chunk() { return chunk; }
        public int sectionY() { return sectionY; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Section)) return false;
            Section s = (Section) o;
            return sectionY == s.sectionY && dimension.equals(s.dimension) && chunk.equals(s.chunk);
        }

        @Override public int hashCode() {
            int h = dimension.hashCode();
            h = 31 * h + chunk.hashCode();
            h = 31 * h + sectionY;
            return h;
        }
    }

    private final Set<Section> dirty = new LinkedHashSet<Section>();

    public void markBlock(DimensionId dim, int x, int y, int z) {
        dirty.add(new Section(dim, new ChunkPos(x >> 4, z >> 4), SectionAddr.sectionY(y)));
    }

    public void markChunk(DimensionId dim, ChunkPos chunk, int minSectionY, int sectionCount) {
        for (int i = 0; i < sectionCount; i++) {
            dirty.add(new Section(dim, chunk, minSectionY + i));
        }
    }

    /** Remove and return up to {@code n} dirty sections (insertion order). */
    public List<Section> popBudget(int n) {
        List<Section> out = new ArrayList<Section>(Math.min(n, dirty.size()));
        Iterator<Section> it = dirty.iterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    public void dropDimension(DimensionId dim) {
        dirty.removeIf(s -> s.dimension.equals(dim));
    }

    public void clear() {
        dirty.clear();
    }

    public int size() {
        return dirty.size();
    }
}
