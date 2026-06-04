package com.minegit.mod.world;

import com.minegit.core.model.DimensionId;

/**
 * Maps a level's namespaced key (e.g. {@code minecraft:overworld}) to a core {@link DimensionId}.
 * Vanilla {@code minecraft:} dimensions collapse to their bare path so they match the canonical
 * {@link DimensionId} constants; modded dimensions keep their full {@code namespace:path} id.
 *
 * <p>Pure string logic with no Minecraft dependency, so it is unit-testable; {@code
 * ServerLevelAccess} feeds it {@code level.dimension().identifier().toString()}.
 */
public final class DimensionMapping {

    private static final String VANILLA_NAMESPACE = "minecraft";

    private DimensionMapping() {
    }

    /** The core {@link DimensionId} for the namespaced level key {@code key}. */
    public static DimensionId fromKey(String key) {
        int colon = key.indexOf(':');
        if (colon >= 0) {
            String namespace = key.substring(0, colon);
            String path = key.substring(colon + 1);
            if (VANILLA_NAMESPACE.equals(namespace)) {
                return new DimensionId(path);
            }
        }
        return new DimensionId(key);
    }
}
