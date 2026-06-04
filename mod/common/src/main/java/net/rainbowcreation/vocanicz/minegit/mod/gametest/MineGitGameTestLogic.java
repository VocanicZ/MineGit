package net.rainbowcreation.vocanicz.minegit.mod.gametest;

import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.mod.command.MineGitService;
import net.rainbowcreation.vocanicz.minegit.mod.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.mod.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.mod.world.ModWorldAdapter;
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
import net.minecraft.core.BlockPos;
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
        AtomicReference<CommitService.Result> out = new AtomicReference<CommitService.Result>();
        new CommitService(INLINE, INLINE, CHUNKS_PER_TICK)
                .commit(repo, adapter, CLOCK, message, AUTHOR, null, out::set);
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
        AtomicReference<CheckoutService.Result> out = new AtomicReference<CheckoutService.Result>();
        new CheckoutService(INLINE, INLINE, CHUNKS_PER_TICK)
                .checkout(repo, adapter, CLOCK, target, force, out::set);
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
