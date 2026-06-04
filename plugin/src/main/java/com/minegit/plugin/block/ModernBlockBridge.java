package com.minegit.plugin.block;

import com.minegit.core.model.BlockState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/**
 * Modern (1.13+) block bridge (Spec B §3).
 *
 * <p>The 1.13 flattening introduced {@code org.bukkit.block.data.BlockData}, which is <em>absent from
 * the 1.8.8 compile classpath</em>. So this bridge never names that type at compile time — it reaches
 * the flattened-string API entirely by reflection:
 *
 * <ul>
 *   <li><b>read:</b> {@code Block.getBlockData().getAsString()} &rarr;
 *       {@link FlattenedBlockStates#parse}.
 *   <li><b>write:</b> {@link FlattenedBlockStates#format} &rarr;
 *       {@code Server.createBlockData(String)} &rarr; {@code Block.setBlockData(BlockData)}.
 * </ul>
 *
 * <p>Modern servers already emit flattened ids, so this bridge bypasses {@code LegacyBlockMapper}
 * entirely. The reflected {@link Method} handles are resolved once (lazily, on first use) and cached —
 * resolving them eagerly would require a live modern server, which keeps construction cheap enough
 * that {@link BlockBridges#forVersion} can build one off-server for the selection unit test. The
 * reflection calls themselves are validated in-game / on a modern test server.
 */
public final class ModernBlockBridge implements BlockBridge {

    // Resolved lazily on first read/write — see class doc. `getAsString` is resolved alongside the
    // block-data object the first time we hold one.
    private Method blockGetBlockData;   // Block#getBlockData()
    private Method blockDataGetAsString; // BlockData#getAsString()
    private Method serverCreateBlockData; // Server#createBlockData(String)
    private Method blockSetBlockData;    // Block#setBlockData(BlockData)

    @Override
    public BlockState read(Block block) {
        try {
            if (blockGetBlockData == null) {
                blockGetBlockData = block.getClass().getMethod("getBlockData");
            }
            Object blockData = blockGetBlockData.invoke(block);
            if (blockDataGetAsString == null) {
                blockDataGetAsString = blockData.getClass().getMethod("getAsString");
            }
            String flattened = (String) blockDataGetAsString.invoke(blockData);
            return FlattenedBlockStates.parse(flattened);
        } catch (ReflectiveOperationException e) {
            throw reflectionFailure("read", e);
        }
    }

    @Override
    public void write(Block block, BlockState state) {
        String flattened = FlattenedBlockStates.format(state);
        try {
            if (serverCreateBlockData == null) {
                serverCreateBlockData =
                        Bukkit.getServer().getClass().getMethod("createBlockData", String.class);
            }
            Object blockData = serverCreateBlockData.invoke(Bukkit.getServer(), flattened);
            if (blockSetBlockData == null) {
                blockSetBlockData = findSetBlockData(block.getClass(), blockData.getClass());
            }
            blockSetBlockData.invoke(block, blockData);
        } catch (ReflectiveOperationException e) {
            throw reflectionFailure("write '" + flattened + "'", e);
        }
    }

    /**
     * Resolve {@code Block#setBlockData(BlockData)}. The parameter is the {@code BlockData} interface,
     * but the concrete object we hold is an implementation, so {@code getMethod(name, impl.class)}
     * would miss it — search by name + single argument instead.
     */
    private static Method findSetBlockData(Class<?> blockClass, Class<?> blockDataClass) {
        for (Method m : blockClass.getMethods()) {
            if (m.getName().equals("setBlockData") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isAssignableFrom(blockDataClass)) {
                return m;
            }
        }
        throw new IllegalStateException(
                "no setBlockData(BlockData) on " + blockClass.getName() + " — not a 1.13+ server?");
    }

    private static IllegalStateException reflectionFailure(String op, ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        return new IllegalStateException("modern block bridge " + op + " failed via reflection", cause);
    }
}
