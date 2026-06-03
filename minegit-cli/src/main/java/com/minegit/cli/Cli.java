package com.minegit.cli;

import com.minegit.core.diff.WorldDiffer;
import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.git.Author;
import com.minegit.core.git.BranchRef;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Command dispatch for the standalone {@code minegit} CLI. Each subcommand drives {@code core}
 * end-to-end through a {@link FakeWorldStore}-backed {@link FakeWorldAdapter}, with the fake world
 * persisted in {@code world.json} so {@code set} mutations compose across invocations (Spec A §10).
 *
 * <p>{@link #run(String[], Path, PrintStream, PrintStream)} is the testable entry point: it takes the
 * working directory and the output streams explicitly so the integration smoke test can run it
 * in-process. No Minecraft dependencies.
 */
final class Cli {

    private static final String DEFAULT_AUTHOR = "minegit";

    private Cli() {
    }

    /**
     * Executes one CLI invocation in {@code workingDir}.
     *
     * @return a process exit code: {@code 0} on success, non-zero on a usage or runtime error.
     */
    static int run(String[] args, Path workingDir, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: minegit <init|set|commit|log|status|diff|branch|checkout> ...");
            return 2;
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (command) {
                case "init":
                    return cmdInit(workingDir, out);
                case "set":
                    return cmdSet(rest, workingDir, out, err);
                case "commit":
                    return cmdCommit(rest, workingDir, out, err);
                case "log":
                    return cmdLog(workingDir, out);
                case "status":
                    return cmdStatus(workingDir, out);
                case "diff":
                    return cmdDiff(rest, workingDir, out, err);
                case "branch":
                    return cmdBranch(rest, workingDir, out, err);
                case "checkout":
                    return cmdCheckout(rest, workingDir, out, err);
                default:
                    err.println("unknown command: " + command);
                    return 2;
            }
        } catch (RuntimeException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ---- subcommands --------------------------------------------------------------------------

    private static int cmdInit(Path dir, PrintStream out) {
        try {
            Files.createDirectories(dir);
            // Write .gitignore before init so the harness's world.json is never versioned.
            Files.write(
                    dir.resolve(".gitignore"),
                    ("/" + FakeWorldStore.FILE_NAME + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("init failed", e);
        }
        FakeWorldStore store = FakeWorldStore.load(dir);
        store.save(dir);
        try (MineGitRepo repo = MineGitRepo.init(dir, store.toAdapter())) {
            out.println("Initialized MineGit repository in " + dir.toAbsolutePath());
        }
        return 0;
    }

    private static int cmdSet(String[] args, Path dir, PrintStream out, PrintStream err) {
        if (args.length != 5) {
            err.println("usage: minegit set <dim> <x> <y> <z> <blockid>");
            return 2;
        }
        String dim = args[0];
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        String id = args[4];
        FakeWorldStore store = FakeWorldStore.load(dir);
        store.set(dim, x, y, z, id);
        store.save(dir);
        out.println("set " + dim + " " + x + " " + y + " " + z + " " + id);
        return 0;
    }

    private static int cmdCommit(String[] args, Path dir, PrintStream out, PrintStream err) {
        String message = null;
        Author author = Author.of(DEFAULT_AUTHOR);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("-m".equals(a) || "--message".equals(a)) && i + 1 < args.length) {
                message = args[++i];
            } else if ("--author".equals(a) && i + 1 < args.length) {
                author = parseAuthor(args[++i]);
            }
        }
        if (message == null) {
            err.println("usage: minegit commit -m \"msg\" [--author name:uuid]");
            return 2;
        }
        FakeWorldStore store = FakeWorldStore.load(dir);
        try (MineGitRepo repo = MineGitRepo.open(dir, store.toAdapter())) {
            CommitInfo info = repo.commit(message, author);
            if (info == null) {
                out.println("nothing to commit");
                return 0;
            }
            out.println("committed " + shortId(info.getId()) + " " + info.getMessage()
                    + " (" + info.getAuthor() + ")");
        }
        return 0;
    }

    private static int cmdLog(Path dir, PrintStream out) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        try (MineGitRepo repo = MineGitRepo.open(dir, store.toAdapter())) {
            List<CommitInfo> commits = repo.log();
            if (commits.isEmpty()) {
                out.println("(no commits)");
                return 0;
            }
            for (CommitInfo c : commits) {
                out.println(shortId(c.getId()) + " " + c.getAuthor() + " " + c.getMessage());
            }
        }
        return 0;
    }

    private static int cmdStatus(Path dir, PrintStream out) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        FakeWorldAdapter adapter = store.toAdapter();
        try (MineGitRepo repo = MineGitRepo.open(dir, adapter)) {
            WorldDiff diff = WorldDiffer.diffWorkingTree(repo, adapter);
            out.println(summary(diff));
        }
        return 0;
    }

    private static int cmdDiff(String[] args, Path dir, PrintStream out, PrintStream err) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        FakeWorldAdapter adapter = store.toAdapter();
        try (MineGitRepo repo = MineGitRepo.open(dir, adapter)) {
            WorldDiff diff;
            if (args.length == 0) {
                diff = WorldDiffer.diffWorkingTree(repo, adapter);
            } else if (args.length == 2) {
                diff = WorldDiffer.diffRefs(repo, args[0], args[1]);
            } else {
                err.println("usage: minegit diff [refA refB]");
                return 2;
            }
            printDiff(diff, out);
        }
        return 0;
    }

    private static int cmdBranch(String[] args, Path dir, PrintStream out, PrintStream err) {
        if (args.length > 1) {
            err.println("usage: minegit branch [name]");
            return 2;
        }
        FakeWorldStore store = FakeWorldStore.load(dir);
        try (MineGitRepo repo = MineGitRepo.open(dir, store.toAdapter())) {
            if (args.length == 1) {
                BranchRef ref = repo.branch(args[0]);
                out.println("created branch " + ref.getName());
                return 0;
            }
            List<BranchRef> branches = new ArrayList<BranchRef>(repo.branches());
            branches.sort(Comparator.comparing(BranchRef::isRemote).thenComparing(BranchRef::getName));
            for (BranchRef b : branches) {
                out.println(b.isRemote() ? "remotes/" + b.getName() : b.getName());
            }
        }
        return 0;
    }

    private static int cmdCheckout(String[] args, Path dir, PrintStream out, PrintStream err) {
        String ref = null;
        boolean force = false;
        for (String a : args) {
            if ("--force".equals(a) || "-f".equals(a)) {
                force = true;
            } else if (ref == null) {
                ref = a;
            } else {
                err.println("usage: minegit checkout <ref> [--force]");
                return 2;
            }
        }
        if (ref == null) {
            err.println("usage: minegit checkout <ref> [--force]");
            return 2;
        }
        FakeWorldStore store = FakeWorldStore.load(dir);
        FakeWorldAdapter adapter = store.toAdapter();
        try (MineGitRepo repo = MineGitRepo.open(dir, adapter)) {
            WorldDiff applied = repo.checkout(ref, force);
            store.apply(applied);
            store.save(dir);
            out.println("checked out " + ref + " (" + summary(applied) + ")");
        }
        return 0;
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Parses {@code name:uuid} into an {@link Author}; a bare token becomes {@code Author.of}. */
    private static Author parseAuthor(String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return Author.of(spec);
        }
        String name = spec.substring(0, colon);
        String uuid = spec.substring(colon + 1);
        return new Author(name, uuid);
    }

    private static String summary(WorldDiff diff) {
        return "+" + diff.getAdded() + "/-" + diff.getRemoved() + "/~" + diff.getChanged();
    }

    private static void printDiff(WorldDiff diff, PrintStream out) {
        List<DimensionId> dims = new ArrayList<DimensionId>(diff.getDimensions().keySet());
        dims.sort(Comparator.comparing(DimensionId::getId));
        for (DimensionId dim : dims) {
            out.println(dim.getId());
            for (ChunkDiff chunkDiff : diff.getChunkDiffs(dim)) {
                for (BlockChange change : chunkDiff.getChanges()) {
                    out.println("  " + formatChange(change));
                }
            }
        }
        out.println(summary(diff));
    }

    private static String formatChange(BlockChange change) {
        String at = change.getX() + " " + change.getY() + " " + change.getZ();
        switch (change.getKind()) {
            case ADD:
                return "+ " + at + " " + idOf(change.getNewState());
            case REMOVE:
                return "- " + at + " " + idOf(change.getOldState());
            case CHANGE:
                return "~ " + at + " " + idOf(change.getOldState()) + " -> "
                        + idOf(change.getNewState());
            default:
                return "? " + at;
        }
    }

    private static String idOf(BlockState state) {
        return state == null ? "minecraft:air" : state.getId();
    }

    private static String shortId(String fullId) {
        return fullId.length() <= 8 ? fullId : fullId.substring(0, 8);
    }
}
