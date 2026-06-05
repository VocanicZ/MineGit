package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;

/**
 * The GPU-agnostic overlay core: a decoded {@link WorldDiff} reduced to what the renderer and HUD
 * need and nothing more. Pure data — <b>no Minecraft render/client imports</b> — so everything that
 * can be wrong in a {@code /mg diff} overlay is exercised headless in JUnit, and the loader-specific
 * renderer is a thin reader over this state.
 *
 * <p>Holds, per {@link DimensionId}, a flat list of {@link OverlayBox} (colored by the
 * {@link OverlayColor} legend), the aggregate added/removed/changed counts, the {@code fromRef}/
 * {@code toRef} labels, and a {@code receivedAt} tick stamp used for auto-expiry.
 *
 * <p>Build it on the client receive path after a payload reassembles:
 * {@code Frame.fromBytes} → {@code Reassembler.add} → on complete → {@link #fromPayload}.
 */
public final class OverlayState {

    private final Map<DimensionId, List<OverlayBox>> boxesByDimension;
    private final int added;
    private final int removed;
    private final int changed;
    private final String fromRef;
    private final String toRef;
    private final long receivedAt;

    /**
     * Builds the overlay directly from a decoded diff and its header labels.
     *
     * @param diff the decoded world diff (per-dimension changes + aggregate counts)
     * @param fromRef the {@code fromRef} label the HUD shows
     * @param toRef the {@code toRef} label the HUD shows
     * @param receivedAt the client tick this overlay arrived (expiry baseline)
     */
    public OverlayState(WorldDiff diff, String fromRef, String toRef, long receivedAt) {
        Objects.requireNonNull(diff, "diff");
        this.fromRef = Objects.requireNonNull(fromRef, "fromRef");
        this.toRef = Objects.requireNonNull(toRef, "toRef");
        this.receivedAt = receivedAt;
        this.added = diff.getAdded();
        this.removed = diff.getRemoved();
        this.changed = diff.getChanged();

        Map<DimensionId, List<OverlayBox>> byDim =
                new LinkedHashMap<DimensionId, List<OverlayBox>>();
        for (Map.Entry<DimensionId, List<ChunkDiff>> e : diff.getDimensions().entrySet()) {
            List<OverlayBox> boxes = new ArrayList<OverlayBox>();
            for (ChunkDiff chunk : e.getValue()) {
                for (BlockChange ch : chunk.getChanges()) {
                    boxes.add(new OverlayBox(
                            ch.getX(), ch.getY(), ch.getZ(),
                            OverlayColor.forKind(ch.getKind())));
                }
            }
            byDim.put(e.getKey(), Collections.unmodifiableList(boxes));
        }
        this.boxesByDimension = Collections.unmodifiableMap(byDim);
    }

    /**
     * Decodes a reassembled {@code minegit:diff} payload into overlay state, reading the
     * {@code fromRef}/{@code toRef} labels from the payload header.
     *
     * @param payload the full payload bytes a {@code Reassembler} yielded
     * @param receivedAt the client tick this overlay arrived (expiry baseline)
     */
    public static OverlayState fromPayload(byte[] payload, long receivedAt) {
        Objects.requireNonNull(payload, "payload");
        WorldDiff diff = DiffPayload.decode(payload);
        String fromRef = DiffPayload.readFromRef(payload);
        String toRef = DiffPayload.readToRef(payload);
        return new OverlayState(diff, fromRef, toRef, receivedAt);
    }

    /** Unmodifiable per-dimension map of overlay boxes. */
    public Map<DimensionId, List<OverlayBox>> getBoxesByDimension() {
        return boxesByDimension;
    }

    /** Overlay boxes for {@code dim}, or an empty list when the dimension has no changes. */
    public List<OverlayBox> boxes(DimensionId dim) {
        List<OverlayBox> boxes = boxesByDimension.get(dim);
        return boxes != null ? boxes : Collections.<OverlayBox>emptyList();
    }

    /** Total blocks added (air → solid). */
    public int getAdded() {
        return added;
    }

    /** Total blocks removed (solid → air). */
    public int getRemoved() {
        return removed;
    }

    /** Total blocks changed (non-air → different non-air). */
    public int getChanged() {
        return changed;
    }

    /** The {@code fromRef} label the HUD shows. */
    public String getFromRef() {
        return fromRef;
    }

    /** The {@code toRef} label the HUD shows. */
    public String getToRef() {
        return toRef;
    }

    /** The client tick this overlay was received (expiry baseline). */
    public long getReceivedAt() {
        return receivedAt;
    }

    /**
     * Whether this overlay has outlived its lifetime, i.e. {@code now - receivedAt >= lifetimeTicks}.
     * A non-positive {@code lifetimeTicks} disables expiry (never expired) — mirroring the config's
     * {@code autoExpireSeconds = 0} "disable the timer" rule.
     *
     * @param now the current client tick
     * @param lifetimeTicks the overlay lifetime in ticks; {@code <= 0} disables expiry
     */
    public boolean isExpired(long now, long lifetimeTicks) {
        if (lifetimeTicks <= 0) {
            return false;
        }
        return now - receivedAt >= lifetimeTicks;
    }

    /**
     * The boxes of {@code dim} the renderer should draw this frame: those within {@code maxDistance}
     * of the camera, sorted nearest-first, truncated to {@code cap}. The returned
     * {@link VisibleBoxes} also carries how many in-range boxes were cut by the cap (the HUD's
     * {@code (+J more)}).
     *
     * <p>Distance is measured to each box's block center. Boxes beyond {@code maxDistance} are
     * culled and do <b>not</b> count toward the dropped total — only in-range boxes the cap removed
     * do. Ties break by {@code (x, y, z)} so the selection is deterministic for tests.
     *
     * @param dim the dimension to draw (the held overlay's active dimension)
     * @param camX camera x
     * @param camY camera y
     * @param camZ camera z
     * @param maxDistance cull radius in blocks; boxes farther than this are excluded
     * @param cap max boxes returned; the rest of the in-range boxes are reported as dropped
     */
    public VisibleBoxes visibleBoxes(
            DimensionId dim, double camX, double camY, double camZ, double maxDistance, int cap) {
        if (cap < 0) {
            throw new IllegalArgumentException("cap must be >= 0, got " + cap);
        }
        double maxSq = maxDistance * maxDistance;
        List<OverlayBox> inRange = new ArrayList<OverlayBox>();
        for (OverlayBox box : boxes(dim)) {
            if (box.distanceSqTo(camX, camY, camZ) <= maxSq) {
                inRange.add(box);
            }
        }

        final double cx = camX;
        final double cy = camY;
        final double cz = camZ;
        Collections.sort(inRange, new Comparator<OverlayBox>() {
            @Override
            public int compare(OverlayBox a, OverlayBox b) {
                int byDist = Double.compare(
                        a.distanceSqTo(cx, cy, cz), b.distanceSqTo(cx, cy, cz));
                if (byDist != 0) {
                    return byDist;
                }
                if (a.getX() != b.getX()) {
                    return Integer.compare(a.getX(), b.getX());
                }
                if (a.getY() != b.getY()) {
                    return Integer.compare(a.getY(), b.getY());
                }
                return Integer.compare(a.getZ(), b.getZ());
            }
        });

        int kept = Math.min(cap, inRange.size());
        int dropped = inRange.size() - kept;
        List<OverlayBox> visible = new ArrayList<OverlayBox>(inRange.subList(0, kept));
        return new VisibleBoxes(Collections.unmodifiableList(visible), dropped);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OverlayState)) {
            return false;
        }
        OverlayState that = (OverlayState) o;
        return added == that.added
                && removed == that.removed
                && changed == that.changed
                && receivedAt == that.receivedAt
                && fromRef.equals(that.fromRef)
                && toRef.equals(that.toRef)
                && boxesByDimension.equals(that.boxesByDimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                boxesByDimension, added, removed, changed, fromRef, toRef, receivedAt);
    }

    @Override
    public String toString() {
        return "OverlayState(+" + added + "/-" + removed + "/~" + changed
                + ", " + fromRef + "->" + toRef
                + ", dimensions=" + boxesByDimension.keySet()
                + ", receivedAt=" + receivedAt + ")";
    }

    /**
     * The result of {@link #visibleBoxes}: the boxes to draw (nearest-first, capped) plus the count
     * of in-range boxes the cap dropped — the HUD's {@code (+J more)}.
     */
    public static final class VisibleBoxes {

        private final List<OverlayBox> boxes;
        private final int dropped;

        VisibleBoxes(List<OverlayBox> boxes, int dropped) {
            this.boxes = boxes;
            this.dropped = dropped;
        }

        /** The boxes to draw this frame: distance-filtered, nearest-first, truncated to the cap. */
        public List<OverlayBox> getBoxes() {
            return boxes;
        }

        /** How many in-range boxes the cap dropped (the HUD's {@code (+J more)}). */
        public int getDropped() {
            return dropped;
        }

        @Override
        public String toString() {
            return "VisibleBoxes(" + boxes.size() + " drawn, " + dropped + " dropped)";
        }
    }
}
