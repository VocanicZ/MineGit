package com.minegit.mod.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Bridges Minecraft's {@code BlockState} and core's version-agnostic {@link
 * com.minegit.core.model.BlockState}.
 *
 * <p><b>Read</b> ({@link #toCore}): the block's registry key plus {@code state.getValues()} (the
 * property map) become a core {@code BlockState}. The 1.21.11 world is already flattened, so there
 * is no {@code LegacyBlockMapper} — this is modern-only.
 *
 * <p><b>Write</b> ({@link #toMinecraft}): the core state is rendered as a vanilla block-state string
 * ({@code id[prop=val,...]}) and handed to {@link BlockStateParser}, the same parser {@code
 * /setblock} uses. No reflection on either side.
 */
public final class BlockStateBridge {

    private BlockStateBridge() {
    }

    /**
     * Reads a Minecraft {@code BlockState} into a core {@code BlockState}: registry key →
     * {@code id}, {@code state.getValues()} → property map (rendered with each property's own
     * vanilla value names, e.g. {@code facing=east}, {@code half=top}).
     */
    public static com.minegit.core.model.BlockState toCore(
            net.minecraft.world.level.block.state.BlockState mc) {
        String id = BuiltInRegistries.BLOCK.getKey(mc.getBlock()).toString();
        Map<String, String> props = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> e : mc.getValues().entrySet()) {
            Property<?> prop = e.getKey();
            props.put(prop.getName(), valueName(prop, e.getValue()));
        }
        return new com.minegit.core.model.BlockState(id, props);
    }

    /**
     * Writes a core {@code BlockState} back to a Minecraft {@code BlockState} by parsing the
     * vanilla block-state string via {@link BlockStateParser}.
     *
     * @throws IllegalArgumentException if the id is not a registered block or a property/value is
     *     invalid for it
     */
    public static net.minecraft.world.level.block.state.BlockState toMinecraft(
            com.minegit.core.model.BlockState core) {
        String spec = render(core);
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, spec, false).blockState();
        } catch (CommandSyntaxException ex) {
            throw new IllegalArgumentException("Unparseable block state: " + spec, ex);
        }
    }

    /** Renders a core state as the vanilla block-state string {@code id[k=v,...]}. */
    private static String render(com.minegit.core.model.BlockState core) {
        if (core.getProps().isEmpty()) {
            return core.getId();
        }
        StringBuilder sb = new StringBuilder(core.getId()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : core.getProps().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<?> prop, Comparable<?> value) {
        return ((Property<T>) prop).getName((T) value);
    }
}
