package net.rainbowcreation.vocanicz.minegit.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldRepoRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command parsing/dispatch, world&harr;repo binding via {@code init}, {@code status}/{@code log}
 * output, and permission gating (Spec B §5, issue #45). MockBukkit does not target the 1.8.8 API, so
 * the Bukkit seam ({@link Player}/{@link World}/{@link CommandSender}) is mocked with Mockito; an
 * empty in-memory {@link WorldAdapter} stands in for the live world.
 */
class MineGitCommandTest {

    @TempDir
    Path dataFolder;

    /** Captures every line the command sends, so assertions can inspect output text. */
    private static final class CapturingMessages implements MessageService {
        final List<String> lines = new ArrayList<String>();

        @Override
        public void send(CommandSender sender, String legacyText) {
            lines.add(legacyText);
        }

        String last() {
            return lines.get(lines.size() - 1);
        }

        boolean anyContains(String needle) {
            for (String l : lines) {
                if (l.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** An empty world: one overworld dimension, no chunks. Enough for init/status/log. */
    private static final class EmptyAdapter implements WorldAdapter {
        @Override
        public Set<DimensionId> dimensions() {
            return Collections.singleton(DimensionId.OVERWORLD);
        }

        @Override
        public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
            return null;
        }

        @Override
        public Set<ChunkRef> allChunks() {
            return Collections.emptySet();
        }

        @Override
        public Set<ChunkRef> drainDirty() {
            return Collections.emptySet();
        }

        @Override
        public Set<ChunkRef> peekDirty() {
            return Collections.emptySet();
        }

        @Override
        public void apply(DimensionId dimension, ChunkPos pos, List<BlockChange> changes) {}

        @Override
        public void writeChunk(DimensionId dimension, NormalizedChunk chunk) {}
    }

    /** An {@link Executor} that runs every task inline, collapsing the commit thread dance for tests. */
    private static final Executor INLINE = Runnable::run;

    private final CapturingMessages messages = new CapturingMessages();

    /** Override per-test to give a world real content (so commit produces a non-empty commit). */
    private Function<World, WorldAdapter> adapters = w -> new EmptyAdapter();

    private MineGitCommand command() {
        WorldRepoRegistry repos = new WorldRepoRegistry(dataFolder);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1780576496L), ZoneOffset.UTC);
        CommitService commits = new CommitService(INLINE, INLINE, 16);
        CheckoutService checkouts = new CheckoutService(INLINE, INLINE, 16);
        return new MineGitCommand(repos, adapters, clock, messages, commits, checkouts);
    }

    private Player player(String worldName) {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn(worldName);
        Player player = mock(Player.class);
        lenient().when(player.getWorld()).thenReturn(world);
        lenient().when(player.getName()).thenReturn("Steve");
        lenient().when(player.getUniqueId()).thenReturn(
                java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        lenient().when(player.hasPermission(MineGitCommand.PERM_USE)).thenReturn(true);
        lenient().when(player.hasPermission(MineGitCommand.PERM_ADMIN)).thenReturn(true);
        return player;
    }

    private boolean dispatch(CommandSender sender, String... args) {
        return command().onCommand(sender, null, "mg", args);
    }

    @Test
    void initCreatesGitRepoAndBindsWorld() {
        Player p = player("world");

        boolean handled = dispatch(p, "init");

        assertTrue(handled);
        WorldRepoRegistry repos = new WorldRepoRegistry(dataFolder);
        assertTrue(repos.isBound("world"), "world should be bound after init");
        assertTrue(Files.isDirectory(repos.repoPath("world").resolve(".git")), "git repo created");
        assertTrue(messages.anyContains("world"), "success message names the world");
    }

    @Test
    void initTwiceReportsAlreadyInitialized() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "init");

        assertTrue(messages.last().contains("already"), "second init warns it already exists");
    }

    @Test
    void initFromConsoleRefusesBecauseThereIsNoWorld() {
        CommandSender console = mock(CommandSender.class);
        lenient().when(console.hasPermission(MineGitCommand.PERM_USE)).thenReturn(true);

        dispatch(console, "init");

        assertTrue(messages.last().contains("in-game"), "console gets an in-game-only message");
    }

    @Test
    void noArgsShowsUsage() {
        Player p = player("world");

        dispatch(p);

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("usage"));
    }

    @Test
    void unknownSubcommandIsReported() {
        Player p = player("world");

        dispatch(p, "frobnicate");

        assertTrue(messages.anyContains("Unknown subcommand"));
    }

    @Test
    void statusWithoutRepoTellsPlayerToInit() {
        Player p = player("world");

        dispatch(p, "status");

        assertTrue(messages.last().contains("init"), "status nudges toward /mg init");
    }

    @Test
    void statusOnFreshRepoShowsZeroDelta() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "status");

        assertTrue(messages.last().contains("+0"), "fresh world matches HEAD: +0/-0/~0");
    }

    @Test
    void logWithoutRepoTellsPlayerToInit() {
        Player p = player("world");

        dispatch(p, "log");

        assertTrue(messages.last().contains("init"));
    }

    @Test
    void logListsTheInitialCommit() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "log");

        assertTrue(messages.anyContains("Initialize"), "log shows the initial commit message");
    }

    @Test
    void commitProducesACommitAuthoredByThePlayerAndLogShowsIt() {
        FakeWorldAdapter live = new FakeWorldAdapter();
        live.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        adapters = w -> live;

        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        boolean handled = dispatch(p, "commit", "-m", "\"build", "a", "tower\"");

        assertTrue(handled);
        assertTrue(messages.anyContains("Committed"), "player told the commit landed");

        // The acceptance criterion: a subsequent log shows a commit authored by the player.
        messages.lines.clear();
        dispatch(p, "log");
        assertTrue(messages.anyContains("Steve"), "log shows the player as author");
        assertTrue(messages.anyContains("build a tower"), "log shows the commit message");
    }

    @Test
    void commitWithoutAMessageShowsUsage() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "commit");

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("usage"));
    }

    @Test
    void commitWithoutRepoTellsPlayerToInit() {
        Player p = player("world");

        dispatch(p, "commit", "-m", "hi");

        assertTrue(messages.anyContains("init"), "commit nudges toward /mg init");
    }

    @Test
    void commitWithNoChangesReportsNothingToCommit() {
        FakeWorldAdapter live = new FakeWorldAdapter();
        live.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        adapters = w -> live;

        Player p = player("world");
        dispatch(p, "init");
        dispatch(p, "commit", "-m", "first");
        messages.lines.clear();

        dispatch(p, "commit", "-m", "again");

        assertTrue(messages.anyContains("Nothing to commit"), "byte-identical world => no commit");
    }

    @Test
    void commitParsesAnUnquotedSingleWordMessage() {
        assertEquals("hello", MineGitCommand.parseMessage(new String[] {"commit", "-m", "hello"}));
    }

    @Test
    void commitParsesAQuotedMultiWordMessage() {
        assertEquals("build a tower",
                MineGitCommand.parseMessage(new String[] {"commit", "-m", "\"build", "a", "tower\""}));
    }

    @Test
    void commitMessageMissingFlagIsNull() {
        assertNull(MineGitCommand.parseMessage(new String[] {"commit"}));
        assertNull(MineGitCommand.parseMessage(new String[] {"commit", "-m"}));
    }

    @Test
    void commitAppearsInTabCompletion() {
        Player p = player("world");
        List<String> completions = command().onTabComplete(p, null, "mg", new String[] {"co"});
        assertTrue(completions.contains("commit"), "'co' completes to commit");
    }

    @Test
    void diffWithoutRepoTellsPlayerToInit() {
        Player p = player("world");

        dispatch(p, "diff");

        assertTrue(messages.last().contains("init"), "diff nudges toward /mg init");
    }

    @Test
    void diffWorkingTreeShowsSummaryHeaderAndZeroDelta() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "diff");

        assertTrue(messages.anyContains("Diff"), "diff prints a header");
        assertTrue(messages.last().contains("+0"), "fresh world matches HEAD: +0/-0/~0 summary");
    }

    @Test
    void diffRefVsRefAgainstItselfIsEmpty() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "diff", "HEAD", "HEAD");

        assertTrue(messages.last().contains("+0"), "HEAD vs HEAD is an empty diff");
    }

    @Test
    void diffUnknownRefIsReportedConsistentlyWithCore() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "diff", "HEAD", "nonexistent");

        // Same loud unknown-ref wording as core #37, not a misleading "everything removed" diff.
        assertTrue(messages.anyContains("unknown ref"), "unknown ref surfaced loudly");
        assertTrue(messages.anyContains("nonexistent"), "names the offending ref");
    }

    @Test
    void diffWithOneRefShowsUsage() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "diff", "HEAD");

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("usage"),
                "diff needs zero or two refs");
    }

    @Test
    void tabCompletesDiffByPrefix() {
        Player p = player("world");

        List<String> completions =
                command().onTabComplete(p, null, "mg", new String[] {"d"});

        assertTrue(completions.contains("diff"), "'d' completes to diff");
    }

    @Test
    void statusDeniedWithoutUsePermission() {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Player p = mock(Player.class);
        lenient().when(p.getWorld()).thenReturn(world);
        when(p.hasPermission(MineGitCommand.PERM_USE)).thenReturn(false);

        dispatch(p, "status");

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("permission"));
    }

    @Test
    void tabCompletesSubcommandsByPrefix() {
        Player p = player("world");

        List<String> completions =
                command().onTabComplete(p, null, "mg", new String[] {"s"});

        assertTrue(completions.contains("status"), "'s' completes to status");
        assertFalse(completions.contains("init"), "'s' excludes non-matching subcommands");
    }

    /** A player with {@code minegit.use} but NOT {@code minegit.admin}. */
    private Player nonAdminPlayer(String worldName) {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn(worldName);
        Player p = mock(Player.class);
        lenient().when(p.getWorld()).thenReturn(world);
        lenient().when(p.getName()).thenReturn("Alex");
        lenient().when(p.getUniqueId()).thenReturn(
                java.util.UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
        lenient().when(p.hasPermission(MineGitCommand.PERM_USE)).thenReturn(true);
        lenient().when(p.hasPermission(MineGitCommand.PERM_ADMIN)).thenReturn(false);
        return p;
    }

    @Test
    void checkoutWithoutRepoTellsPlayerToInit() {
        Player p = player("world");

        dispatch(p, "checkout", "HEAD");

        assertTrue(messages.anyContains("init"), "checkout nudges toward /mg init");
    }

    @Test
    void checkoutDeniedWithoutAdminPermission() {
        Player p = nonAdminPlayer("world");

        dispatch(p, "checkout", "HEAD");

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("permission"),
                "checkout requires minegit.admin");
    }

    @Test
    void checkoutWithoutARefShowsUsage() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "checkout");

        assertTrue(messages.last().toLowerCase(java.util.Locale.ROOT).contains("usage"),
                "checkout needs a ref");
    }

    @Test
    void checkoutRevertsABuiltChangeAndTellsThePlayer() {
        FakeWorldAdapter live = new FakeWorldAdapter();
        live.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        adapters = w -> live;

        Player p = player("world");
        dispatch(p, "init");
        dispatch(p, "commit", "-m", "A");
        live.setBlock(DimensionId.OVERWORLD, 100, 64, 0, new BlockState("minecraft:dirt"));
        dispatch(p, "commit", "-m", "B");
        messages.lines.clear();

        dispatch(p, "checkout", "HEAD~1");

        assertSame(BlockState.AIR, live.getBlock(DimensionId.OVERWORLD, 100, 64, 0),
                "the dirt from B was reverted live");
        assertTrue(messages.anyContains("Checked out"), "player told the checkout landed");
    }

    @Test
    void checkoutDirtyTreeIsRefusedWithoutForceAndProceedsWithIt() {
        FakeWorldAdapter live = new FakeWorldAdapter();
        live.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        adapters = w -> live;

        Player p = player("world");
        dispatch(p, "init");
        dispatch(p, "commit", "-m", "A");
        live.setBlock(DimensionId.OVERWORLD, 100, 64, 0, new BlockState("minecraft:dirt"));
        dispatch(p, "commit", "-m", "B");
        // Uncommitted live edit makes the world dirty.
        live.setBlock(DimensionId.OVERWORLD, 200, 64, 0, new BlockState("minecraft:glass"));
        messages.lines.clear();

        dispatch(p, "checkout", "HEAD~1");
        assertTrue(messages.anyContains("--force"), "dirty tree refused with a --force hint");
        assertEquals(new BlockState("minecraft:dirt"),
                live.getBlock(DimensionId.OVERWORLD, 100, 64, 0), "refused checkout reverted nothing");

        messages.lines.clear();
        dispatch(p, "checkout", "HEAD~1", "--force");
        assertSame(BlockState.AIR, live.getBlock(DimensionId.OVERWORLD, 100, 64, 0),
                "forced checkout reverted B");
        assertTrue(messages.anyContains("Checked out"));
    }

    @Test
    void checkoutUnknownRefIsReportedLoudly() {
        Player p = player("world");
        dispatch(p, "init");
        messages.lines.clear();

        dispatch(p, "checkout", "nonexistent");

        assertTrue(messages.anyContains("unknown ref"), "unknown ref surfaced loudly");
        assertTrue(messages.anyContains("nonexistent"), "names the offending ref");
    }

    @Test
    void checkoutAppearsInTabCompletion() {
        Player p = player("world");
        List<String> completions = command().onTabComplete(p, null, "mg", new String[] {"che"});
        assertTrue(completions.contains("checkout"), "'che' completes to checkout");
    }

    @Test
    void checkoutHiddenFromTabCompletionWithoutAdmin() {
        Player p = nonAdminPlayer("world");
        List<String> completions = command().onTabComplete(p, null, "mg", new String[] {"che"});
        assertFalse(completions.contains("checkout"), "no minegit.admin -> no checkout offered");
    }

    @Test
    void tabHidesSubcommandsThePlayerCannotUse() {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Player p = mock(Player.class);
        when(p.hasPermission(MineGitCommand.PERM_USE)).thenReturn(false);

        List<String> completions =
                command().onTabComplete(p, null, "mg", new String[] {""});

        assertTrue(completions.isEmpty(), "no minegit.use -> no read subcommands offered");
    }
}
