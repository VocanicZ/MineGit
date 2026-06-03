package com.minegit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.fake.BareRepo;
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
    void remoteSetRequiresUrl(@TempDir Path tmp) {
        Path work = tmp.resolve("work");
        run(work, "init");
        Result bad = run(work, "remote", "set");
        assertTrue(bad.code != 0, "missing url is a usage error");
    }
}
