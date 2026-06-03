package com.minegit.core.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Version-agnostic normalized block state: a namespaced block id plus its (possibly empty) set of
 * block-state properties.
 *
 * <p>Properties are held in a key-sorted {@link SortedMap} so that two states with the same id and
 * the same property entries compare {@linkplain #equals(Object) equal} regardless of the order in
 * which the properties were supplied. This value-based equality is required by the diff engine and
 * by git deduplication of unchanged chunks.
 *
 * <p>Instances are immutable; the property map is defensively copied and exposed unmodifiable.
 */
public final class BlockState {

    /** Canonical "empty" baseline used by the diff engine to define ADD/REMOVE. */
    public static final BlockState AIR = new BlockState("minecraft:air");

    private final String id;
    private final SortedMap<String, String> props;

    /** Creates a propertyless block state (e.g. {@code minecraft:stone}). */
    public BlockState(String id) {
        this(id, Collections.<String, String>emptyMap());
    }

    /**
     * Creates a block state with properties. The {@code props} map is copied into a key-sorted
     * unmodifiable map; later mutation of the supplied map does not affect this state.
     */
    public BlockState(String id, Map<String, String> props) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(props, "props");
        SortedMap<String, String> sorted = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            sorted.put(
                    Objects.requireNonNull(e.getKey(), "prop key"),
                    Objects.requireNonNull(e.getValue(), "prop value"));
        }
        this.props = Collections.unmodifiableSortedMap(sorted);
    }

    /** The namespaced block id, e.g. {@code "minecraft:stone"}. */
    public String getId() {
        return id;
    }

    /** Key-sorted, unmodifiable view of this state's properties. */
    public SortedMap<String, String> getProps() {
        return props;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockState)) {
            return false;
        }
        BlockState that = (BlockState) o;
        return id.equals(that.id) && props.equals(that.props);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, props);
    }

    @Override
    public String toString() {
        return props.isEmpty() ? id : id + props;
    }
}
