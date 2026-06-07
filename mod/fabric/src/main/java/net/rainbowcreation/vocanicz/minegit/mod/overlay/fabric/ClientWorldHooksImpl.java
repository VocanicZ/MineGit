package net.rainbowcreation.vocanicz.minegit.mod.overlay.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldEventBridge;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldHooks;

/**
 * Fabric implementation of the {@code ClientWorldHooks} {@code @ExpectPlatform} seam (SP2 task C2).
 * Stores the core-typed sinks and registers Fabric's {@code ClientChunkEvents.CHUNK_LOAD}; the
 * chunk's {@code ClientLevel}→core extraction is delegated to the MC-aware common
 * {@link ClientWorldEventBridge} so no core type is named on the loader classpath. The block-change
 * feed is the same sink the {@code ClientLevelChunkMixin} pushes through.
 */
@Environment(EnvType.CLIENT)
public final class ClientWorldHooksImpl {

    private ClientWorldHooksImpl() {
    }

    /** Installs both sinks and registers Fabric's client chunk-load event. */
    public static void register(
            ClientWorldHooks.BlockChangeListener onBlock, ClientWorldHooks.ChunkLoadListener onChunk) {
        ClientWorldHooks.setBlockSink(onBlock);
        ClientWorldHooks.setChunkSink(onChunk);
        ClientChunkEvents.CHUNK_LOAD.register(
                (level, chunk) ->
                        ClientWorldEventBridge.chunkLoaded(level, chunk.getPos().x, chunk.getPos().z));
    }
}
