package net.rainbowcreation.vocanicz.minegit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.fake.BareRepo;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteCliTest {

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
    void remoteSetThenPushThenFetch(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Path work = tmp.resolve("work");
            assertEquals(0, run(work, "init").code);

            Result set = run(work, "remote", "set", remote.fileUrl());
            assertEquals(0, set.code, () -> "remote set failed: " + set.err);

            Result push = run(work, "push");
            assertEquals(0, push.code, () -> "push failed: " + push.err);
            assertTrue(push.out.contains("master"), () -> "push output missing ref: " + push.out);
            assertTrue(push.out.contains("OK"), () -> "push output missing status: " + push.out);

            Result fetch = run(work, "fetch");
            assertEquals(0, fetch.code, () -> "fetch failed: " + fetch.err);
        }
    }

    @Test
    void cloneMaterializesWorldThenStatusIsClean(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            // Publisher builds a one-block world and pushes it.
            Path pub = tmp.resolve("pub");
            assertEquals(0, run(pub, "init").code);
            assertEquals(0, run(pub, "remote", "set", remote.fileUrl()).code);
            assertEquals(0, run(pub, "set", "overworld", "1", "64", "2", "minecraft:stone").code);
            assertEquals(0, run(pub, "commit", "-m", "publish").code);
            assertEquals(0, run(pub, "push").code);

            // Clone into a fresh dir.
            Result clone = run(tmp, "clone", remote.fileUrl(), "fresh");
            assertEquals(0, clone.code, () -> "clone failed: " + clone.err);

            // The materialized world is clean against HEAD and carries the published block.
            Path fresh = tmp.resolve("fresh");
            Result status = run(fresh, "status");
            assertEquals(0, status.code, () -> "status failed: " + status.err);
            assertTrue(status.out.contains("+0/-0/~0"), () -> "expected clean tree: " + status.out);

            Result diff = run(fresh, "diff", "HEAD~1", "HEAD");
            assertTrue(
                    diff.out.contains("minecraft:stone"),
                    () -> "cloned history missing the block: " + diff.out);
        }
    }

    @Test
    void pullAppliesRemoteWorld(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            Path pub = tmp.resolve("pub");
            assertEquals(0, run(pub, "init").code);
            assertEquals(0, run(pub, "remote", "set", remote.fileUrl()).code);
            assertEquals(0, run(pub, "set", "overworld", "1", "64", "2", "minecraft:stone").code);
            assertEquals(0, run(pub, "commit", "-m", "publish").code);
            assertEquals(0, run(pub, "push").code);

            // Fresh subscriber pulls the published world.
            Path sub = tmp.resolve("sub");
            assertEquals(0, run(sub, "init").code);
            assertEquals(0, run(sub, "remote", "set", remote.fileUrl()).code);

            Result pull = run(sub, "pull");
            assertEquals(0, pull.code, () -> "pull failed: " + pull.err);
            assertTrue(pull.out.contains("+1/"), () -> "pull summary missing add: " + pull.out);

            // After pull the working tree is clean (live world == HEAD).
            Result status = run(sub, "status");
            assertTrue(status.out.contains("+0/-0/~0"), () -> "not clean after pull: " + status.out);
        }
    }

    @Test
    void cloneRequiresUrlAndDir(@TempDir Path tmp) {
        Result bad = run(tmp, "clone", "only-one-arg");
        assertTrue(bad.code != 0, "clone needs url and dir");
    }

    @Test
    void remoteSetRequiresUrl(@TempDir Path tmp) {
        Path work = tmp.resolve("work");
        run(work, "init");
        Result bad = run(work, "remote", "set");
        assertTrue(bad.code != 0, "missing url is a usage error");
    }
}
