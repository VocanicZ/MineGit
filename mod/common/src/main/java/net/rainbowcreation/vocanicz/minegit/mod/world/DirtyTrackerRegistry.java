package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-lifetime registry of {@link DirtyChunkSet}s, keyed by level key (the dimension identifier
 * string returned by {@code ServerCommandRuntime.levelKey()}). One {@link DirtyChunkSet} per level
 * key is created on first access and reused for all subsequent commands operating on that level.
 *
 * <p>The registry must live in a long-lived holder (e.g. {@link
 * net.rainbowcreation.vocanicz.minegit.mod.command.ServerCommandRuntime}) rather than being
 * recreated per-command, because the dirty set accumulates block-change events between commands and
 * must survive the individual {@link ModWorldAdapter} instances that are built per-command.
 */
public final class DirtyTrackerRegistry {

    private final ConcurrentHashMap<String, DirtyChunkSet> map = new ConcurrentHashMap<String, DirtyChunkSet>();

    /**
     * Returns the {@link DirtyChunkSet} for {@code levelKey}, creating it on first access. Subsequent
     * calls with the same key return the <em>identical</em> instance.
     *
     * @param levelKey the dimension identifier string (e.g. {@code "minecraft:overworld"})
     * @return the per-level dirty set; never {@code null}
     */
    public DirtyChunkSet tracker(String levelKey) {
        Objects.requireNonNull(levelKey, "levelKey");
        return map.computeIfAbsent(levelKey, k -> new DirtyChunkSet());
    }
}
