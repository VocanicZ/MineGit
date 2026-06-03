package com.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.fake.BareRepo;
import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Remote-op tests for {@link MineGitRepo}: {@code remoteSet} / {@code fetch} / {@code push}. */
class RemoteOpsTest {

    private static final Credential CRED = DefaultGitCredential.INSTANCE;

    @Test
    void remoteSet_configuresOrigin(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"));
                MineGitRepo repo = MineGitRepo.init(tmp.resolve("work"), new FakeWorldAdapter())) {
            repo.remoteSet(remote.fileUrl());
        }
        try (Git git = Git.open(tmp.resolve("work").toFile())) {
            StoredConfig cfg = git.getRepository().getConfig();
            assertEquals(
                    remoteFileUrl(tmp), cfg.getString("remote", "origin", "url"),
                    "origin url configured");
            assertNotNull(
                    cfg.getString("remote", "origin", "fetch"), "origin fetch refspec configured");
        }
    }

    @Test
    void push_reportsPerRefStatus_okThenUpToDate(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"));
                MineGitRepo repo = MineGitRepo.init(tmp.resolve("work"), new FakeWorldAdapter())) {
            repo.remoteSet(remote.fileUrl());

            PushResult first = repo.push(CRED);
            assertEquals(1, first.getUpdates().size(), "one branch pushed");
            PushResult.RefUpdate u = first.getUpdates().get(0);
            assertTrue(u.getRemoteRef().endsWith("master"), "pushed master: " + u.getRemoteRef());
            assertEquals(PushResult.Status.OK, u.getStatus(), "first push is OK");
            assertTrue(first.isOk());

            // Pushing again with no new commits is up-to-date, not rejected.
            PushResult second = repo.push(CRED);
            assertEquals(
                    PushResult.Status.UP_TO_DATE,
                    second.getUpdates().get(0).getStatus(),
                    "second push up-to-date");
            assertTrue(second.isOk());
        }
    }

    @Test
    void push_nonFastForward_reportsRejected(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            FakeWorldAdapter world = new FakeWorldAdapter();
            try (MineGitRepo repo = MineGitRepo.init(tmp.resolve("work"), world)) {
                repo.remoteSet(remote.fileUrl());
                assertTrue(repo.push(CRED).isOk(), "initial push ok");

                // Another client advances remote master past our HEAD.
                advanceRemoteMaster(remote.fileUrl(), tmp.resolve("other"));

                // We commit locally (diverging) and try to push — non-fast-forward.
                world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, new BlockState("minecraft:stone"));
                assertNotNull(repo.commit("local change", Author.of("Steve")));

                PushResult rejected = repo.push(CRED);
                assertFalse(rejected.isOk(), "non-ff push is not ok");
                assertEquals(
                        PushResult.Status.REJECTED,
                        rejected.getUpdates().get(0).getStatus(),
                        "non-ff push rejected");
            }
        }
    }

    @Test
    void fetch_updatesOriginRefs_withoutTouchingWorld(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            try (MineGitRepo repo = MineGitRepo.init(tmp.resolve("work"), new FakeWorldAdapter())) {
                repo.remoteSet(remote.fileUrl());
                repo.push(CRED);

                Path work = tmp.resolve("work");
                ObjectId localBefore;
                byte[] worldFilesBefore;
                try (Git git = Git.open(work.toFile())) {
                    localBefore = git.getRepository().resolve("refs/heads/master");
                }
                worldFilesBefore = snapshotTree(work);

                // A second client advances remote master.
                ObjectId remoteHead = advanceRemoteMaster(remote.fileUrl(), tmp.resolve("other"));

                repo.fetch(CRED);

                try (Git git = Git.open(work.toFile())) {
                    Repository r = git.getRepository();
                    assertEquals(
                            remoteHead, r.resolve("refs/remotes/origin/master"),
                            "fetch updated origin/master to the remote head");
                    assertEquals(
                            localBefore, r.resolve("refs/heads/master"),
                            "fetch did not move local master");
                }
                assertEquals(
                        worldFilesBefore.length,
                        snapshotTree(work).length,
                        "fetch did not touch the working tree");
                assertNotEquals(
                        localBefore, remoteHead, "the two heads genuinely differ");
            }
        }
    }

    @Test
    void pull_fetchesAppliesAndReturnsDelta(@TempDir Path tmp) throws Exception {
        BlockState stone = new BlockState("minecraft:stone");
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            // Publisher commits a stone block and pushes it to the remote.
            FakeWorldAdapter pubWorld = new FakeWorldAdapter();
            try (MineGitRepo pub = MineGitRepo.init(tmp.resolve("pub"), pubWorld)) {
                pub.remoteSet(remote.fileUrl());
                pubWorld.setBlock(DimensionId.OVERWORLD, 1, 64, 2, stone);
                assertNotNull(pub.commit("publish stone", Author.of("Pub")));
                assertTrue(pub.push(CRED).isOk(), "publisher push ok");
            }

            // A fresh, empty subscriber pulls the published world.
            FakeWorldAdapter subWorld = new FakeWorldAdapter();
            try (MineGitRepo sub = MineGitRepo.init(tmp.resolve("sub"), subWorld)) {
                sub.remoteSet(remote.fileUrl());

                WorldDiff applied = sub.pull(CRED);

                // The stone arrived in the live world and the returned delta describes it.
                assertEquals(stone, subWorld.getBlock(DimensionId.OVERWORLD, 1, 64, 2));
                assertEquals(1, applied.getAdded(), "one block added by pull");
                assertEquals(0, applied.getRemoved());
                assertEquals(0, applied.getChanged());

                // Local master fast-forwarded to the remote head: a re-pull is a no-op.
                WorldDiff again = sub.pull(CRED);
                assertTrue(again.getDimensions().isEmpty(), "second pull applies nothing");
            }
        }
    }

    @Test
    void clone_materializesWorldFromRemote(@TempDir Path tmp) throws Exception {
        BlockState stone = new BlockState("minecraft:stone");
        BlockState dirt = new BlockState("minecraft:dirt");
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            // Publisher builds a world spanning two chunks and pushes it.
            FakeWorldAdapter pubWorld = new FakeWorldAdapter();
            try (MineGitRepo pub = MineGitRepo.init(tmp.resolve("pub"), pubWorld)) {
                pub.remoteSet(remote.fileUrl());
                pubWorld.setBlock(DimensionId.OVERWORLD, 1, 64, 2, stone);
                pubWorld.setBlock(DimensionId.OVERWORLD, 20, -10, 0, dirt); // chunk (1,0)
                pub.commit("publish world", Author.of("Pub"));
                assertTrue(pub.push(CRED).isOk(), "publisher push ok");
            }

            // Clone into a fresh dir + empty world.
            Path fresh = tmp.resolve("fresh");
            FakeWorldAdapter world = new FakeWorldAdapter();
            try (MineGitRepo repo = MineGitRepo.clone(remote.fileUrl(), fresh, CRED, world)) {
                // Every published block was materialized into the live world.
                assertEquals(stone, world.getBlock(DimensionId.OVERWORLD, 1, 64, 2));
                assertEquals(dirt, world.getBlock(DimensionId.OVERWORLD, 20, -10, 0));

                // The clone is a real repo with the published history, and its working tree is clean
                // against the materialized world (live world == HEAD).
                assertFalse(repo.log().isEmpty(), "clone carries history");
                assertTrue(
                        com.minegit.core.diff.WorldDiffer.diffWorkingTree(repo, world)
                                .getDimensions().isEmpty(),
                        "materialized world matches HEAD");
            }
        }
    }

    @Test
    void pull_refusesDirtyTree(@TempDir Path tmp) throws Exception {
        BlockState stone = new BlockState("minecraft:stone");
        BlockState glass = new BlockState("minecraft:glass");
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            FakeWorldAdapter pubWorld = new FakeWorldAdapter();
            try (MineGitRepo pub = MineGitRepo.init(tmp.resolve("pub"), pubWorld)) {
                pub.remoteSet(remote.fileUrl());
                pubWorld.setBlock(DimensionId.OVERWORLD, 1, 64, 2, stone);
                pub.commit("publish stone", Author.of("Pub"));
                pub.push(CRED);
            }

            FakeWorldAdapter subWorld = new FakeWorldAdapter();
            try (MineGitRepo sub = MineGitRepo.init(tmp.resolve("sub"), subWorld)) {
                sub.remoteSet(remote.fileUrl());
                // Uncommitted local edit makes the working tree dirty.
                subWorld.setBlock(DimensionId.OVERWORLD, 9, 64, 9, glass);
                org.junit.jupiter.api.Assertions.assertThrows(
                        WorkingTreeDirtyException.class, () -> sub.pull(CRED),
                        "dirty tree refused by pull");
            }
        }
    }

    // ---- helpers ----

    private static String remoteFileUrl(Path tmp) {
        return tmp.resolve("remote.git").toUri().toString();
    }

    /** Clones {@code url}, adds a commit, pushes it back, and returns the new remote master id. */
    private static ObjectId advanceRemoteMaster(String url, Path workDir) throws Exception {
        org.eclipse.jgit.api.CloneCommand clone =
                Git.cloneRepository().setURI(url).setDirectory(workDir.toFile());
        CRED.applyTo(clone);
        try (Git g = clone.call()) {
            Files.write(workDir.resolve("other.txt"), "x".getBytes(StandardCharsets.UTF_8));
            g.add().addFilepattern(".").call();
            ObjectId id = g.commit()
                    .setMessage("other client")
                    .setAuthor("O", "o@e")
                    .setCommitter("O", "o@e")
                    .call();
            org.eclipse.jgit.api.PushCommand push = g.push();
            CRED.applyTo(push);
            push.call();
            return id;
        }
    }

    /** A flat byte snapshot of the non-.git working-tree files, for change detection. */
    private static byte[] snapshotTree(Path work) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.List<Path> files = new java.util.ArrayList<Path>();
        try (java.util.stream.Stream<Path> walk = Files.walk(work)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(java.io.File.separator + ".git"))
                    .sorted()
                    .forEach(files::add);
        }
        for (Path p : files) {
            bos.write(work.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
            bos.write(Files.readAllBytes(p));
        }
        return bos.toByteArray();
    }
}
