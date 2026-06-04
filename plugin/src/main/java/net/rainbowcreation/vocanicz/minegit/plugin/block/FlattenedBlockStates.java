package net.rainbowcreation.vocanicz.minegit.plugin.block;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, Bukkit-free translation between a 1.13+ flattened block-state <em>string</em> and the
 * version-agnostic core {@link BlockState}.
 *
 * <p>Modern servers represent a block as a string such as
 * {@code "minecraft:oak_stairs[facing=east,half=bottom,waterlogged=false]"}; the
 * {@link ModernBlockBridge} obtains and consumes that string via reflection
 * ({@code BlockData.getAsString()} / {@code Server.createBlockData(String)}) but delegates the actual
 * parsing and formatting here so the format logic stays unit-testable on a plain JVM (Spec B §3).
 *
 * <p>Formatting is deterministic: properties are emitted in key order (the core {@link BlockState}
 * already key-sorts its property map), so a captured state round-trips to a byte-stable string.
 */
public final class FlattenedBlockStates {

    private FlattenedBlockStates() {}

    /**
     * Parse a flattened state string into a {@link BlockState}.
     *
     * @throws IllegalArgumentException if the string is null/blank or contains a malformed property
     */
    public static BlockState parse(String flattened) {
        if (flattened == null) {
            throw new IllegalArgumentException("flattened block state is null");
        }
        String s = flattened.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("flattened block state is blank");
        }
        int open = s.indexOf('[');
        if (open < 0) {
            return new BlockState(s);
        }
        if (s.charAt(s.length() - 1) != ']') {
            throw new IllegalArgumentException("unterminated property list: '" + flattened + "'");
        }
        String id = s.substring(0, open).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("missing block id: '" + flattened + "'");
        }
        String body = s.substring(open + 1, s.length() - 1).trim();
        Map<String, String> props = new LinkedHashMap<String, String>();
        if (!body.isEmpty()) {
            for (String pair : body.split(",")) {
                String p = pair.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                if (eq <= 0 || eq == p.length() - 1) {
                    throw new IllegalArgumentException("malformed property '" + p + "' in '" + flattened + "'");
                }
                props.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        return new BlockState(id, props);
    }

    /**
     * Format a {@link BlockState} back to a flattened state string. Properties are emitted in the
     * state's (key-sorted) order, so the output is stable and {@link #parse parse}-round-trippable.
     */
    public static String format(BlockState state) {
        if (state.getProps().isEmpty()) {
            return state.getId();
        }
        StringBuilder sb = new StringBuilder(state.getId()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : state.getProps().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
