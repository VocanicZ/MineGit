package net.rainbowcreation.vocanicz.minegit.plugin.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-lifetime registry of {@link DirtyChunkSet}s, keyed by Bukkit world name
 * ({@code World.getName()}). One {@link DirtyChunkSet} per world is created on first access and
 * reused for all subsequent commands and the block-change listener operating on that world.
 *
 * <p>The registry must live in a long-lived holder (the plugin) rather than being recreated
 * per-command, because the dirty set accumulates block-change events between commands and must
 * survive the individual {@link BukkitWorldAdapter} instances that are built per-command. The same
 * registry instance feeds the adapter factory, commit/status, and the event listener (Spec E task 5).
 */
public final class WorldDirtyRegistry {

    private final ConcurrentHashMap<String, DirtyChunkSet> map =
            new ConcurrentHashMap<String, DirtyChunkSet>();

    /**
     * Returns the {@link DirtyChunkSet} for {@code worldName}, creating it on first access. Subsequent
     * calls with the same name return the <em>identical</em> instance.
     *
     * @param worldName the Bukkit world name (e.g. {@code "world"})
     * @return the per-world dirty set; never {@code null}
     */
    public DirtyChunkSet tracker(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        return map.computeIfAbsent(worldName, k -> new DirtyChunkSet());
    }
}
