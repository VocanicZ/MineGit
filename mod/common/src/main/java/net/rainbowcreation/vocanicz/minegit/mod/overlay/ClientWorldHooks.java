package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import dev.architectury.injectables.annotations.ExpectPlatform;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/**
 * Registers client-side block-change and chunk-load callbacks that feed the live differ (SP2 task
 * C2). Loader-agnostic — names no Minecraft types — so it lives in {@code common}. Both feeds are
 * pushed from the loader side via the MC-aware {@link ClientWorldEventBridge} (which the loader event
 * + the {@code ClientLevelChunkMixin} call), because the loader subprojects can't see core types on
 * their compile classpath; this class only holds the core-typed sinks and routes to them.
 *
 * <p>Client-only.
 */
public final class ClientWorldHooks {

    private ClientWorldHooks() {
    }

    /** Fed each time a block changes on the client world. */
    public interface BlockChangeListener {
        void onClientBlockChange(DimensionId dim, int x, int y, int z);
    }

    /** Fed each time a chunk loads on the client world. */
    public interface ChunkLoadListener {
        void onClientChunkLoad(DimensionId dim, ChunkPos pos);
    }

    /**
     * Installs {@code onBlock}/{@code onChunk} as the client world's change feed: stores both sinks
     * (so {@link ClientWorldEventBridge} can route the mixin's block pushes and the loader event's
     * chunk loads to them) and registers the loader's client chunk-load callback.
     * {@code @ExpectPlatform} stitches in the per-loader impl.
     */
    @ExpectPlatform
    public static void register(BlockChangeListener onBlock, ChunkLoadListener onChunk) {
        throw new AssertionError("@ExpectPlatform stub — replaced by ClientWorldHooksImpl at build time");
    }

    /**
     * The block-change sink the {@code ClientLevelChunkMixin} pushes through (via {@link
     * ClientWorldEventBridge}). Volatile because the mixin fires on the client thread while {@code
     * register} runs at client init.
     */
    private static volatile BlockChangeListener blockSink;

    /** The chunk-load sink the loader's client chunk event pushes through (via the bridge). */
    private static volatile ChunkLoadListener chunkSink;

    /** Installs the block-change sink the per-loader {@code register} routes pushes to. */
    public static void setBlockSink(BlockChangeListener sink) {
        blockSink = sink;
    }

    /** Installs the chunk-load sink the per-loader {@code register} routes loads to. */
    public static void setChunkSink(ChunkLoadListener sink) {
        chunkSink = sink;
    }

    /**
     * Pushed by the {@code ClientLevelChunkMixin} (via {@link ClientWorldEventBridge}) on every
     * client-side block change. No-ops until a sink is installed (i.e. before client init wires the
     * engine).
     */
    public static void fireBlockChange(DimensionId dim, int x, int y, int z) {
        BlockChangeListener sink = blockSink;
        if (sink != null) {
            sink.onClientBlockChange(dim, x, y, z);
        }
    }

    /**
     * Pushed by the loader's client chunk-load event (via {@link ClientWorldEventBridge}) on every
     * client chunk load. No-ops until a sink is installed.
     */
    public static void fireChunkLoad(DimensionId dim, ChunkPos pos) {
        ChunkLoadListener sink = chunkSink;
        if (sink != null) {
            sink.onClientChunkLoad(dim, pos);
        }
    }
}
