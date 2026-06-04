package com.minegit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    /** Runs one CLI invocation in {@code dir} and returns its captured stdout. */
    private static Result run(Path dir, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, "UTF-8");
                PrintStream e = new PrintStream(err, true, "UTF-8")) {
            code = Cli.run(args, dir, o, e);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return new Result(
                code,
                new String(out.toByteArray(), StandardCharsets.UTF_8),
                new String(err.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    @Test
    void initCreatesRepoAndWorldJson(@TempDir Path dir) {
        Result r = run(dir, "init");
        assertEquals(0, r.code, () -> "init failed: " + r.err);
        assertTrue(Files.isDirectory(dir.resolve(".git")), "git repo created");
        assertTrue(Files.exists(dir.resolve("world.json")), "world.json created");
    }

    @Test
    void endToEndInitSetCommitLogStatusDiff(@TempDir Path dir) {
        assertEquals(0, run(dir, "init").code);

        assertEquals(0, run(dir, "set", "overworld", "0", "64", "0", "minecraft:stone").code);
        assertEquals(0, run(dir, "set", "overworld", "1", "64", "0", "minecraft:dirt").code);

        Result commit = run(dir, "commit", "-m", "first snapshot", "--author", "Steve:uuid-steve");
        assertEquals(0, commit.code, () -> "commit failed: " + commit.err);

        Result log = run(dir, "log");
        assertEquals(0, log.code);
        assertTrue(log.out.contains("first snapshot"), () -> "log message missing: " + log.out);
        assertTrue(log.out.contains("Steve"), () -> "log author missing: " + log.out);

        // Clean working tree right after commit: nothing differs from HEAD.
        Result statusClean = run(dir, "status");
        assertEquals(0, statusClean.code);
        assertTrue(
                statusClean.out.contains("+0/-0/~0"),
                () -> "expected clean status, got: " + statusClean.out);

        // Remove one block, change another, add a third.
        run(dir, "set", "overworld", "0", "64", "0", "minecraft:air"); // remove stone
        run(dir, "set", "overworld", "1", "64", "0", "minecraft:glass"); // dirt -> glass
        run(dir, "set", "overworld", "2", "64", "0", "minecraft:gold_block"); // add

        Result status = run(dir, "status");
        assertEquals(0, status.code);
        assertTrue(
                status.out.contains("+1/-1/~1"),
                () -> "expected +1/-1/~1, got: " + status.out);

        Result diff = run(dir, "diff");
        assertEquals(0, diff.code);
        assertTrue(
                diff.out.contains("minecraft:gold_block"),
                () -> "diff should mention the added block: " + diff.out);
        assertTrue(diff.out.contains("+1/-1/~1"), () -> "diff summary wrong: " + diff.out);

        Result commit2 = run(dir, "commit", "-m", "second snapshot");
        assertEquals(0, commit2.code, () -> "second commit failed: " + commit2.err);

        // ref-vs-ref diff between the two commits reports the same block-level deltas.
        Result diffRefs = run(dir, "diff", "HEAD~1", "HEAD");
        assertEquals(0, diffRefs.code, () -> "ref diff failed: " + diffRefs.err);
        assertTrue(
                diffRefs.out.contains("+1/-1/~1"),
                () -> "ref diff summary wrong: " + diffRefs.out);
    }

    @Test
    void authorFlagIsParsedIntoTheCommit(@TempDir Path dir) {
        run(dir, "init");
        run(dir, "set", "overworld", "10", "70", "10", "minecraft:stone");
        Result commit = run(dir, "commit", "-m", "msg", "--author", "Alice:uid-alice");
        assertEquals(0, commit.code, () -> "commit failed: " + commit.err);
        Result log = run(dir, "log");
        assertTrue(log.out.contains("Alice"), () -> "author not recorded: " + log.out);
    }

    @Test
    void commitWithNothingToCommitReportsClean(@TempDir Path dir) {
        run(dir, "init");
        run(dir, "set", "overworld", "0", "64", "0", "minecraft:stone");
        run(dir, "commit", "-m", "first");
        // No further sets: a second commit has no block delta.
        Result again = run(dir, "commit", "-m", "noop");
        assertEquals(0, again.code, () -> "noop commit failed: " + again.err);
        assertTrue(
                again.out.toLowerCase().contains("nothing"),
                () -> "expected nothing-to-commit message, got: " + again.out);
    }

    @Test
    void branchCreateThenListShowsLocalBranches(@TempDir Path dir) {
        assertEquals(0, run(dir, "init").code);

        Result create = run(dir, "branch", "feature");
        assertEquals(0, create.code, () -> "branch create failed: " + create.err);
        assertTrue(create.out.contains("feature"), () -> "create output missing name: " + create.out);

        Result list = run(dir, "branch");
        assertEquals(0, list.code, () -> "branch list failed: " + list.err);
        assertTrue(list.out.contains("feature"), () -> "list missing feature: " + list.out);
        assertTrue(list.out.contains("master"), () -> "list missing default branch: " + list.out);
    }

    @Test
    void branchListMarksCurrentBranch(@TempDir Path dir) {
        assertEquals(0, run(dir, "init").code);
        assertEquals(0, run(dir, "branch", "feature").code);

        Result list = run(dir, "branch");
        assertEquals(0, list.code, () -> "branch list failed: " + list.err);
        // HEAD is still master after creating feature: master is marked, feature is not.
        assertTrue(
                list.out.lines().anyMatch(l -> l.equals("* master")),
                () -> "current branch master not marked with '* ': " + list.out);
        assertTrue(
                list.out.lines().anyMatch(l -> l.equals("  feature")),
                () -> "non-current branch feature not prefixed with two spaces: " + list.out);
    }

    @Test
    void checkoutRevertsWorldToPriorCommit(@TempDir Path dir) {
        assertEquals(0, run(dir, "init").code);
        run(dir, "set", "overworld", "0", "64", "0", "minecraft:stone");
        run(dir, "commit", "-m", "A");
        run(dir, "set", "overworld", "100", "64", "0", "minecraft:dirt");
        run(dir, "commit", "-m", "B");

        Result checkout = run(dir, "checkout", "HEAD~1");
        assertEquals(0, checkout.code, () -> "checkout failed: " + checkout.err);

        // The dirt placed in B is gone; the working tree is clean against the reverted HEAD.
        Result status = run(dir, "status");
        assertEquals("+0/-0/~0", status.out.trim(), () -> "expected clean status: " + status.out);

        // The local ref moved: only init + A remain on the branch.
        Result log = run(dir, "log");
        assertTrue(log.out.contains("A"), () -> "log missing A: " + log.out);
        assertTrue(!log.out.contains(" B"), () -> "B should be gone from branch: " + log.out);
    }

    @Test
    void checkoutRefusesDirtyTreeUnlessForced(@TempDir Path dir) {
        assertEquals(0, run(dir, "init").code);
        run(dir, "set", "overworld", "0", "64", "0", "minecraft:stone");
        run(dir, "commit", "-m", "A");
        run(dir, "set", "overworld", "100", "64", "0", "minecraft:dirt");
        run(dir, "commit", "-m", "B");

        // Uncommitted edit makes the working tree dirty.
        run(dir, "set", "overworld", "200", "64", "0", "minecraft:glass");

        Result refused = run(dir, "checkout", "HEAD~1");
        assertNotEquals(0, refused.code, "dirty checkout should be refused");

        Result forced = run(dir, "checkout", "HEAD~1", "--force");
        assertEquals(0, forced.code, () -> "forced checkout failed: " + forced.err);
    }

    @Test
    void unknownCommandIsAnError(@TempDir Path dir) {
        Result r = run(dir, "frobnicate");
        assertNotEquals(0, r.code, "unknown command should be non-zero exit");
    }
}
