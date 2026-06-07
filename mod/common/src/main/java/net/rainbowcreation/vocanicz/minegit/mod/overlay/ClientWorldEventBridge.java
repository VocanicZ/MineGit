package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/**
 * The MC-aware common bridge between the loader-side client world events and the core-typed {@link
 * ClientWorldHooks} sinks (SP2 task C2). The per-loader {@code ClientLevelChunkMixin} and the
 * {@code ClientWorldHooksImpl} chunk event call these with a {@code ClientLevel} + raw coords; the
 * {@code ClientLevel}→{@link DimensionId}/{@link ChunkPos} extraction happens here — on the MC-aware
 * common side — so the loader subprojects never name core types, which are not on their compile
 * classpath. Mirrors how the server {@code LevelChunkMixin} delegates to {@code
 * ServerLevelAccess.markDirty}.
 *
 * <p>Client-only.
 */
@Environment(EnvType.CLIENT)
public final class ClientWorldEventBridge {

    private ClientWorldEventBridge() {
    }

    /**
     * Fired by the {@code ClientLevelChunkMixin} for every client-side block change. Extracts the
     * core dimension from {@code level} and pushes through {@link ClientWorldHooks#fireBlockChange}.
     */
    public static void blockChanged(ClientLevel level, int x, int y, int z) {
        ClientWorldHooks.fireBlockChange(ClientWorldLevelAccess.dimensionOf(level), x, y, z);
    }

    /**
     * Fired by the loader's client chunk-load event for every client chunk load. Extracts the core
     * dimension + chunk pos and pushes through {@link ClientWorldHooks#fireChunkLoad}.
     */
    public static void chunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
        ClientWorldHooks.fireChunkLoad(
                ClientWorldLevelAccess.dimensionOf(level), new ChunkPos(chunkX, chunkZ));
    }
}
