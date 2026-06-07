package net.rainbowcreation.vocanicz.minegit.mod.overlay.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldEventBridge;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldHooks;

/**
 * NeoForge implementation of the {@code ClientWorldHooks} {@code @ExpectPlatform} seam (SP2 task C2).
 * Stores the core-typed sinks and registers {@code ChunkEvent.Load} on the game bus, filtered to
 * client levels ({@code ChunkEvent.Load} fires server-side too). The chunk's {@code ClientLevel}→core
 * extraction is delegated to the MC-aware common {@link ClientWorldEventBridge} so no core type is
 * named on the loader classpath. The block-change feed is the same sink the {@code
 * ClientLevelChunkMixin} pushes through.
 */
public final class ClientWorldHooksImpl {

    private ClientWorldHooksImpl() {
    }

    /** Installs both sinks and registers the client-filtered chunk-load listener. */
    public static void register(
            ClientWorldHooks.BlockChangeListener onBlock, ClientWorldHooks.ChunkLoadListener onChunk) {
        ClientWorldHooks.setBlockSink(onBlock);
        ClientWorldHooks.setChunkSink(onChunk);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, ClientWorldHooksImpl::onChunkLoad);
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        LevelChunk chunk = event.getChunk();
        Level level = chunk.getLevel();
        if (!(level instanceof ClientLevel)) {
            return; // ChunkEvent.Load fires on the server world too — overlay is client-only
        }
        ClientWorldEventBridge.chunkLoaded((ClientLevel) level, chunk.getPos().x, chunk.getPos().z);
    }
}
