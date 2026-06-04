package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.Objects;

/**
 * Process-wide bridge between the platform {@code setBlockState} mixins (the <em>writers</em>) and
 * the per-command {@link ModWorldAdapter} (the <em>reader</em>). A mixin cannot reach the
 * command runtime's {@link DirtyTrackerRegistry} directly, so {@link MineGitMod#init} publishes that
 * one registry here via {@link #install}; the mixin then calls {@link #markDirty} on every block
 * change and the matching chunk lands in the same {@link DirtyChunkSet} the adapter drains.
 *
 * <p><strong>Key/dimension alignment (load-bearing).</strong> For a drained {@link ChunkRef} to be
 * readable by the adapter, two things must line up exactly with {@code ServerCommandRuntime}:
 *
 * <ol>
 *   <li>The registry <em>key</em> ({@code levelKey} string) the mixin passes must equal what
 *       {@code ServerCommandRuntime.levelKey(ServerLevel)} produces — both are
 *       {@code level.dimension().identifier().toString()} (see {@link ServerLevelAccess#levelKeyOf}),
 *       so the mixin and the command resolve the <em>identical</em> {@link DirtyChunkSet} instance.
 *   <li>The {@link DimensionId} inside each {@link ChunkRef} must {@code .equals(...)} the one
 *       {@code ModWorldAdapter.dimension()} reports — both are {@link DimensionMapping#fromKey} over
 *       that same identifier (see {@link ServerLevelAccess#dimensionOf}), so
 *       {@code ModWorldAdapter.read(ref.getDimension(), ...)} does not reject the chunk.
 * </ol>
 *
 * The mixin (platform side) does the {@code ServerLevel}→(levelKey, DimensionId) extraction through
 * those two {@link ServerLevelAccess} helpers and hands this class only primitives + a
 * {@link DimensionId}, so this bridge stays free of Minecraft types and unit-testable.
 *
 * <p><strong>No-op before install.</strong> A mixin can fire during early boot before the runtime is
 * built; {@link #markDirty} is a silent no-op while the registry is {@code null}, so an early block
 * change never NPEs. (Pre-install changes are reconciled by the unprimed full-rescan on the first
 * commit/status anyway.) {@link #markDirty} is safe to call from any thread — it only adds to the
 * thread-safe {@link DirtyChunkSet}.
 */
public final class DirtyTracking {

    private static volatile DirtyTrackerRegistry registry;

    private DirtyTracking() {
    }

    /**
     * Publishes the command runtime's dirty-tracker registry as the process-wide target for mixin
     * block-change events. Idempotent re-install (e.g. on {@code /reload}) simply swaps the holder.
     *
     * @param registry the shared registry; never {@code null}
     */
    public static void install(DirtyTrackerRegistry registry) {
        DirtyTracking.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Test seam: clears the installed registry so {@link #markDirty} reverts to a no-op. */
    static void reset() {
        registry = null;
    }

    /**
     * The registry currently {@linkplain #install installed}, or {@code null} if none has been. Lets a
     * GameTest read back the <em>same</em> {@link DirtyTrackerRegistry} the {@code setBlockState} mixin
     * writes to, so a real block change can be asserted end to end (mixin → {@link #markDirty}).
     */
    public static DirtyTrackerRegistry installedRegistry() {
        return registry;
    }

    /**
     * Marks the chunk containing block {@code (blockX, blockZ)} dirty in the {@link DirtyChunkSet}
     * for {@code levelKey}. No-op if no registry has been {@linkplain #install installed} yet.
     *
     * @param levelKey  the level-key string (see {@link ServerLevelAccess#levelKeyOf}); never {@code null}
     * @param dimension the chunk's dimension (see {@link ServerLevelAccess#dimensionOf}); never {@code null}
     * @param blockX    the changed block's world X
     * @param blockZ    the changed block's world Z
     */
    public static void markDirty(String levelKey, DimensionId dimension, int blockX, int blockZ) {
        DirtyTrackerRegistry r = registry;
        if (r == null) {
            return; // not installed yet (early boot) — safe to drop; full rescan reconciles later
        }
        Objects.requireNonNull(levelKey, "levelKey");
        Objects.requireNonNull(dimension, "dimension");
        DirtyChunkSet set = r.tracker(levelKey);
        ChunkPos pos = new ChunkPos(blockX >> 4, blockZ >> 4);
        set.markDirty(new ChunkRef(dimension, pos));
    }
}
