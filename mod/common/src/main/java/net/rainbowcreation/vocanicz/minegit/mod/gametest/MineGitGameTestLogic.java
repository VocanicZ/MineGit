package net.rainbowcreation.vocanicz.minegit.mod.gametest;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.command.MineGitService;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlPayload;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffOverlaySender;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffRawPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Reassembler;
import net.rainbowcreation.vocanicz.minegit.mod.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.mod.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTracking;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTrackerRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.world.ModWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.mod.world.ServerLevelAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The loader-agnostic body of the MineGit GameTests (Spec D §6, issue #64): the headless
 * <strong>place → commit → mutate → checkout → assert-reverted</strong> loop, plus a no-op checkout
 * that must leave the world untouched. Both loaders register thin wrappers that call these methods;
 * the logic lives here so Fabric and NeoForge prove the <em>same</em> behaviour.
 *
 * <p>It drives the production seam end to end: a real {@link ModWorldAdapter} over a
 * {@link GameTestLevelAccess} (genuine {@code ServerLevel} block I/O) through the real
 * {@link CommitService}/{@link CheckoutService}. The thread executors are {@linkplain #INLINE inline}
 * (the GameTest method already runs on the server thread), and the repo lives in a throwaway temp
 * directory, so JGit runs for real without a save folder.
 */
public final class MineGitGameTestLogic {

    /** GameTest methods already run on the server thread, so both executors run their task inline. */
    private static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static final int CHUNKS_PER_TICK = 16;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1780000000L), ZoneOffset.UTC);
    private static final Author AUTHOR = new Author("GameTest", "gametest@minegit.local");

    /** The blocks built/mutated/reverted, relative to the test structure origin (one chunk, mid-air). */
    private static final BlockPos[] SPOTS = {
        new BlockPos(1, 2, 1),
        new BlockPos(2, 2, 1),
        new BlockPos(1, 2, 2),
        new BlockPos(2, 2, 2),
    };

    private MineGitGameTestLogic() {
    }

    /**
     * Full loop: build a gold structure → {@code commit A} → mutate it to diamond → {@code commit B}
     * → {@code checkout HEAD~1} and assert every block reverted to gold. This is the headless proof of
     * the real chunk I/O the plugin frontend could not auto-test.
     */
    public static void placeCommitMutateCheckoutRevert(GameTestHelper helper) {
        WorldAdapter adapter = adapterFor(helper);
        Path repo = tempRepo();

        // Build: a known gold structure committed as A.
        setAll(helper, Blocks.GOLD_BLOCK);
        MineGitService.init(repo, adapter, CLOCK);
        require(commit(adapter, repo, "A") != null, "commit A should snapshot the gold build");

        // Mutate: change every block to diamond and commit as B so HEAD moves.
        setAll(helper, Blocks.DIAMOND_BLOCK);
        require(commit(adapter, repo, "B") != null, "commit B should snapshot the diamond mutation");
        assertAll(helper, Blocks.DIAMOND_BLOCK);

        // Checkout the previous commit: the live world must revert to gold (B -> A applied).
        CheckoutService.Result result = checkout(adapter, repo, "HEAD~1", false);
        require(!result.isError(), "checkout HEAD~1 should succeed on a clean world");
        require(result.applied().getRemoved() == 0, "revert replaces, does not remove, blocks");
        assertAll(helper, Blocks.GOLD_BLOCK);

        helper.succeed();
    }

    /**
     * A no-op checkout is clean: with the world matching {@code HEAD}, {@code checkout HEAD} succeeds,
     * applies nothing, and leaves every block in place.
     */
    public static void noOpCheckoutIsClean(GameTestHelper helper) {
        WorldAdapter adapter = adapterFor(helper);
        Path repo = tempRepo();

        setAll(helper, Blocks.GOLD_BLOCK);
        MineGitService.init(repo, adapter, CLOCK);
        require(commit(adapter, repo, "A") != null, "commit A should snapshot the gold build");

        CheckoutService.Result result = checkout(adapter, repo, "HEAD", false);
        require(!result.isError(), "no-op checkout of HEAD should be clean");
        require(result.applied().getChanged() == 0, "no-op checkout changes nothing");
        require(result.applied().getAdded() == 0, "no-op checkout adds nothing");
        require(result.applied().getRemoved() == 0, "no-op checkout removes nothing");
        assertAll(helper, Blocks.GOLD_BLOCK);

        helper.succeed();
    }

    /**
     * Incremental/dirty-path loop: a priming commit over the full structure, then change ONE block and
     * mark only its chunk dirty (mirroring the {@code setBlockState} mixin) → {@code commit B} drains
     * exactly that chunk → checkout the priming commit and assert the block reverted. This proves the
     * event-based {@link DirtyChunkSet} feeds the same {@link ModWorldAdapter} the production command
     * uses, so commits can be incremental rather than full-world.
     *
     * <p>Why drive the dirty set directly: a GameTest world sets blocks through the
     * {@link GameTestLevelAccess} seam, and whether the {@code LevelChunk.setBlockState} mixin fires
     * for those writes is not guaranteed in the headless test harness. The mixin and this test both
     * funnel into the <em>same</em> {@link DirtyChunkSet} via the <em>same</em> levelKey+DimensionId
     * scheme ({@link ServerLevelAccess#levelKeyOf}/{@link ServerLevelAccess#dimensionOf}), so marking
     * the set here exercises the identical incremental commit path the mixin would.
     */
    public static void incrementalDirtyCommitReverts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtyTrackerRegistry trackers = new DirtyTrackerRegistry();
        String levelKey = ServerLevelAccess.levelKeyOf(level);
        DimensionId dimension = ServerLevelAccess.dimensionOf(level);
        DirtyChunkSet dirty = trackers.tracker(levelKey);
        WorldAdapter adapter = trackedAdapterFor(helper, dirty);
        Path repo = tempRepo();

        // Prime: full gold structure committed as A (unprimed → full scan, primes the tracker).
        setAll(helper, Blocks.GOLD_BLOCK);
        MineGitService.init(repo, adapter, CLOCK);
        require(commit(adapter, repo, "A", dirty) != null, "priming commit A should snapshot the gold build");
        require(dirty.isPrimed(), "the first commit must prime the dirty tracker");
        require(dirty.peekDirty().isEmpty(), "priming drains the dirty set");

        // Mutate exactly ONE block and mark only its chunk dirty, exactly as the mixin bridge would.
        BlockPos one = SPOTS[0];
        helper.setBlock(one, Blocks.DIAMOND_BLOCK);
        BlockPos abs = helper.absolutePos(one);
        markDirty(dirty, dimension, abs);

        Set<ChunkRef> peeked = dirty.peekDirty();
        require(peeked.size() == 1, "exactly one chunk should be dirty after one block change, was " + peeked.size());
        ChunkRef onlyDirty = peeked.iterator().next();
        require(onlyDirty.getDimension().equals(dimension),
                "dirty ref dimension must match the adapter dimension (mixin/adapter alignment)");
        require(onlyDirty.getPos().equals(new ChunkPos(abs.getX() >> 4, abs.getZ() >> 4)),
                "dirty ref must point at the changed block's chunk");

        // Incremental commit B: drains only the one dirty chunk (primed path), HEAD moves to diamond.
        require(commit(adapter, repo, "B", dirty) != null, "incremental commit B should snapshot the one dirty chunk");
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, one);

        // Checkout the priming commit via the dirty-scoped guard: the tracker is primed and the dirty
        // set was drained by commit B, so the guard reads nothing yet still reverts the one block.
        CheckoutService.Result result = checkout(adapter, repo, "HEAD~1", false, true);
        require(!result.isError(), "checkout HEAD~1 should succeed on a clean world");
        assertAll(helper, Blocks.GOLD_BLOCK);

        helper.succeed();
    }

    /**
     * Proves the {@code LevelChunk.setBlockState} mixin actually fires on a real block change and feeds
     * the published {@link DirtyTracking} registry end to end (Spec E task 4). Unlike
     * {@link #incrementalDirtyCommitReverts} — which drives the dirty set directly — this performs a
     * <em>real</em> server-level write via {@code helper.getLevel().setBlock(absPos, state, 3)}, which
     * funnels through {@code Level.setBlock} → {@code LevelChunk.setBlockState} (verified against the
     * 1.21.11 bytecode), so the mixin runs in the live call path and must mark the chunk dirty in the
     * <em>same</em> registry {@code MineGitMod.init} installed.
     *
     * <p>It reads back {@link DirtyTracking#installedRegistry()} (the production registry the mixin
     * writes to), keyed by the {@code ServerLevelAccess.levelKeyOf} scheme — the identical key the
     * mixin uses — and asserts the changed block's chunk is present with the aligned dimension. If the
     * mixin never fired, the set would not contain it and this test fails.
     */
    public static void mixinFiresOnRealBlockChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtyTrackerRegistry installed = DirtyTracking.installedRegistry();
        require(installed != null,
                "MineGitMod.init must have installed the dirty registry before GameTests run");

        String levelKey = ServerLevelAccess.levelKeyOf(level);
        DimensionId dimension = ServerLevelAccess.dimensionOf(level);
        DirtyChunkSet dirty = installed.tracker(levelKey);
        // Drain anything boot/setup already accumulated so we observe only our own write.
        dirty.drainDirty();

        BlockPos rel = SPOTS[0];
        BlockPos abs = helper.absolutePos(rel);
        ChunkRef expected = new ChunkRef(dimension, new ChunkPos(abs.getX() >> 4, abs.getZ() >> 4));

        // A REAL routed write: ServerLevel.setBlock -> LevelChunk.setBlockState (the mixin's target).
        boolean changed = level.setBlock(abs, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        require(changed, "the real setBlock should change the block (and route through setBlockState)");
        helper.assertBlockPresent(Blocks.EMERALD_BLOCK, rel);

        Set<ChunkRef> peeked = dirty.peekDirty();
        require(!peeked.isEmpty(),
                "the setBlockState mixin must have marked the changed chunk dirty (it fired = end-to-end)");
        require(peeked.contains(expected),
                "the dirty set must contain the changed block's chunk " + expected + ", was " + peeked);

        helper.succeed();
    }

    /**
     * Proves the {@code minegit:diff} wire is open end to end on a dedicated server (issue #77),
     * without a live client: a real {@link Frame} → {@code toBytes()} → {@link DiffRawPayload} →
     * {@link DiffRawPayload#STREAM_CODEC} encode into a network buffer → decode back → the
     * loader-agnostic {@link DiffChannel#deliverToClient} → an installed capture sink. The captured
     * bytes must reconstruct the original {@link Frame} byte-for-byte. This exercises the actual packet
     * codec the per-loader receiver uses, so a "hand-sent test packet round-trips bytes to the client
     * handler" is asserted in CI on both loaders even though the GPU draw is not.
     */
    public static void diffPayloadRoundTripsToClientHandler(GameTestHelper helper) {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        DiffChannel.setClientHandler(captured::set);
        try {
            Frame source = new Frame(0xC0FFEE, 1, 3, new byte[] {10, 20, 30, 40, 50});
            byte[] wire = source.toBytes();

            // Encode the opaque payload through the real StreamCodec, then decode a fresh copy back —
            // exactly the path the client receiver decodes a received minegit:diff packet through.
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            DiffRawPayload.STREAM_CODEC.encode(buf, new DiffRawPayload(wire));
            DiffRawPayload decoded = DiffRawPayload.STREAM_CODEC.decode(buf);

            // Funnel the decoded opaque bytes to the client sink, as the @ExpectPlatform receiver does.
            DiffChannel.deliverToClient(decoded.bytes());

            byte[] seen = captured.get();
            require(seen != null, "the client handler must receive the round-tripped minegit:diff bytes");
            require(java.util.Arrays.equals(wire, seen),
                    "the opaque payload must survive encode/decode byte-for-byte");
            require(source.equals(Frame.fromBytes(seen)),
                    "the delivered bytes must reconstruct the source Frame");
        } finally {
            DiffChannel.resetClientHandler();
        }
        helper.succeed();
    }

    /**
     * Proves the {@code minegit:diffsub} control wire is open end to end on a dedicated server
     * (issue #91), without a live client: a real {@link DiffControl} → {@code encode()} →
     * {@link DiffControlPayload} → {@link DiffControlPayload#STREAM_CODEC} encode into a network buffer
     * → decode back → the loader-agnostic {@link DiffControlChannel#deliverToServer} → an installed
     * capture handler. The captured control must equal the source for both {@code SUBSCRIBE} and
     * {@code UNSUBSCRIBE}, and a malformed payload must be dropped (never dispatched). This exercises
     * the actual packet codec the per-loader server receiver uses, so a "hand-sent SUB/UNSUB reaches
     * the server handler" is asserted in CI on both loaders.
     */
    public static void controlPacketRoundTripsToServerHandler(GameTestHelper helper) {
        AtomicReference<DiffControl> captured = new AtomicReference<>();
        DiffControlChannel.setServerHandler((player, control) -> captured.set(control));
        try {
            for (DiffControl source : DiffControl.values()) {
                captured.set(null);

                // Encode the opaque control payload through the real StreamCodec, then decode a fresh
                // copy back — exactly the path the server receiver decodes a received diffsub packet.
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                DiffControlPayload.STREAM_CODEC.encode(buf, new DiffControlPayload(source.encode()));
                DiffControlPayload decoded = DiffControlPayload.STREAM_CODEC.decode(buf);

                // Funnel the decoded bytes to the server handler, as the @ExpectPlatform receiver does.
                DiffControlChannel.deliverToServer(null, decoded.bytes());

                require(captured.get() == source,
                        "the server handler must receive the round-tripped control " + source
                                + ", was " + captured.get());
            }

            // A malformed control byte must be dropped by deliverToServer, not dispatched.
            captured.set(null);
            FriendlyByteBuf junk = new FriendlyByteBuf(Unpooled.buffer());
            DiffControlPayload.STREAM_CODEC.encode(junk, new DiffControlPayload(new byte[] {99}));
            DiffControlChannel.deliverToServer(null, DiffControlPayload.STREAM_CODEC.decode(junk).bytes());
            require(captured.get() == null, "a malformed control must not reach the server handler");
        } finally {
            DiffControlChannel.resetServerHandler();
        }
        helper.succeed();
    }

    /**
     * Proves the {@code /mg diff} server-send path (issue #78) on a dedicated server, without a live
     * client: drives the {@link DiffOverlaySender} send entrypoint through a <em>captured-sink</em>
     * {@link DiffOverlaySender.Sink} (records instead of transmitting) and asserts (a) a capable
     * player gets the full framed payload, which reassembles + {@link DiffPayload#decode}s back to the
     * source {@link WorldDiff}, and (b) a {@code canSend=false} player is silently skipped — no packet.
     * This exercises the real {@code encode → frame → toBytes} path the {@code @ExpectPlatform}
     * transmit rides on both loaders, even though the GPU draw is not auto-testable.
     */
    public static void diffServerSendFramesReachCapturedSink(GameTestHelper helper) {
        WorldDiff source = sampleServerDiff();

        // (a) Capable player: every frame must be captured and reassemble back to the source diff.
        CapturingSink capable = new CapturingSink(true);
        int frames = DiffOverlaySender.send(null, source, "HEAD", "WORKING", capable);
        require(frames >= 1, "a capable player must receive at least one frame, got " + frames);
        require(capable.sent.size() == frames, "every framed packet must be enqueued to the sink");

        Reassembler reassembler = new Reassembler();
        byte[] full = null;
        for (byte[] frameBytes : capable.sent) {
            java.util.Optional<byte[]> done = reassembler.add(Frame.fromBytes(frameBytes));
            if (done.isPresent()) {
                full = done.get();
            }
        }
        require(full != null, "the captured frames must reassemble to a complete payload");
        require(source.equals(DiffPayload.decode(full)),
                "the framed bytes must decode back to the source WorldDiff");

        // (b) Incapable player: the canSend gate skips the send entirely — no packet enqueued.
        CapturingSink incapable = new CapturingSink(false);
        int skipped = DiffOverlaySender.send(null, source, "HEAD", "WORKING", incapable);
        require(skipped == 0, "a canSend=false player triggers no send, got " + skipped);
        require(incapable.sent.isEmpty(), "no packet may be enqueued for an incapable player");

        helper.succeed();
    }

    /** A small representative diff (one chunk, all three kinds) for the server-send GameTest. */
    private static WorldDiff sampleServerDiff() {
        java.util.List<net.rainbowcreation.vocanicz.minegit.core.model.BlockChange> changes =
                new java.util.ArrayList<>();
        changes.add(net.rainbowcreation.vocanicz.minegit.core.model.BlockChange.add(
                3, 70, 5, new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:gold_block")));
        changes.add(net.rainbowcreation.vocanicz.minegit.core.model.BlockChange.remove(
                7, 12, 9, new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:dirt")));
        changes.add(net.rainbowcreation.vocanicz.minegit.core.model.BlockChange.change(
                1, 64, 1,
                new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:dirt"),
                new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:diamond_block")));
        net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff cd =
                new net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff(
                        new ChunkPos(0, 0), changes);
        java.util.Map<DimensionId, java.util.List<net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff>> dims =
                new java.util.HashMap<>();
        dims.put(DimensionId.OVERWORLD, java.util.Arrays.asList(cd));
        return new WorldDiff(dims, 1, 1, 1);
    }

    /** Captured network sink: records each frame's bytes instead of transmitting, gated by canSend. */
    private static final class CapturingSink implements DiffOverlaySender.Sink {
        private final boolean capable;
        final java.util.List<byte[]> sent = new java.util.ArrayList<>();

        CapturingSink(boolean capable) {
            this.capable = capable;
        }

        @Override
        public boolean canSend(net.minecraft.server.level.ServerPlayer player) {
            return capable;
        }

        @Override
        public void sendTo(net.minecraft.server.level.ServerPlayer player, byte[] frameBytes) {
            sent.add(frameBytes);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static WorldAdapter adapterFor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Set<ChunkPos> chunks = new LinkedHashSet<ChunkPos>();
        for (BlockPos rel : SPOTS) {
            BlockPos abs = helper.absolutePos(rel);
            chunks.add(new ChunkPos(abs.getX() >> 4, abs.getZ() >> 4));
        }
        return new ModWorldAdapter(new GameTestLevelAccess(level, chunks));
    }

    /** Like {@link #adapterFor} but backed by {@code dirty}, so commits can follow the incremental path. */
    private static WorldAdapter trackedAdapterFor(GameTestHelper helper, DirtyChunkSet dirty) {
        ServerLevel level = helper.getLevel();
        Set<ChunkPos> chunks = new LinkedHashSet<ChunkPos>();
        for (BlockPos rel : SPOTS) {
            BlockPos abs = helper.absolutePos(rel);
            chunks.add(new ChunkPos(abs.getX() >> 4, abs.getZ() >> 4));
        }
        return new ModWorldAdapter(new GameTestLevelAccess(level, chunks), dirty);
    }

    /** Marks the chunk of the absolute block {@code abs} dirty, mirroring the {@code setBlockState} mixin. */
    private static void markDirty(DirtyChunkSet dirty, DimensionId dimension, BlockPos abs) {
        dirty.markDirty(new ChunkRef(dimension, new ChunkPos(abs.getX() >> 4, abs.getZ() >> 4)));
    }

    private static void setAll(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
        for (BlockPos rel : SPOTS) {
            helper.setBlock(rel, block);
        }
    }

    private static void assertAll(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
        for (BlockPos rel : SPOTS) {
            helper.assertBlockPresent(block, rel);
        }
    }

    private static CommitInfo commit(WorldAdapter adapter, Path repo, String message) {
        return commit(adapter, repo, message, null);
    }

    private static CommitInfo commit(WorldAdapter adapter, Path repo, String message, DirtyChunkSet tracker) {
        AtomicReference<CommitService.Result> out = new AtomicReference<CommitService.Result>();
        new CommitService(INLINE, INLINE, CHUNKS_PER_TICK)
                .commit(repo, adapter, CLOCK, message, AUTHOR, tracker, out::set);
        CommitService.Result result = out.get();
        if (result == null) {
            throw new IllegalStateException("commit '" + message + "' never completed");
        }
        if (result.isError()) {
            throw new IllegalStateException("commit '" + message + "' failed", result.error());
        }
        return result.commit();
    }

    private static CheckoutService.Result checkout(
            WorldAdapter adapter, Path repo, String target, boolean force) {
        return checkout(adapter, repo, target, force, false);
    }

    private static CheckoutService.Result checkout(
            WorldAdapter adapter, Path repo, String target, boolean force, boolean dirtyScoped) {
        AtomicReference<CheckoutService.Result> out = new AtomicReference<CheckoutService.Result>();
        new CheckoutService(INLINE, INLINE, CHUNKS_PER_TICK)
                .checkout(repo, adapter, CLOCK, target, force, dirtyScoped, out::set);
        CheckoutService.Result result = out.get();
        if (result == null) {
            throw new IllegalStateException("checkout '" + target + "' never completed");
        }
        return result;
    }

    private static Path tempRepo() {
        try {
            return Files.createTempDirectory("minegit-gametest");
        } catch (IOException e) {
            throw new UncheckedIOException("could not create a temp MineGit repo", e);
        }
    }

    /** Fails the GameTest (the framework records a thrown exception as the failure) when {@code cond} is false. */
    private static void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalStateException(message);
        }
    }
}
