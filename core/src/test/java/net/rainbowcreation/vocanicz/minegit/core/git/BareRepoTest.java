package net.rainbowcreation.vocanicz.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.fake.BareRepo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BareRepoTest {

    @Test
    void create_makesABareRepoServedOverAFileUrl(@TempDir Path tmp) throws Exception {
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            assertTrue(remote.fileUrl().startsWith("file:"), "served over a file:// URL");
            Repository repo = remote.repository();
            assertTrue(repo.isBare(), "the harness repo is bare");
        }
    }

    @Test
    void bareRepo_isUsableAsARemoteForCloneCommitPushFetch(@TempDir Path tmp) throws Exception {
        Credential cred = DefaultGitCredential.INSTANCE;
        try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
            String url = remote.fileUrl();

            // clone the (empty) bare repo, commit, and push back
            Path workA = tmp.resolve("workA");
            org.eclipse.jgit.api.CloneCommand cloneA =
                Git.cloneRepository().setURI(url).setDirectory(workA.toFile());
            cred.applyTo(cloneA);
            try (Git a = cloneA.call()) {
                Files.write(
                    workA.resolve("hello.txt"), "world".getBytes(StandardCharsets.UTF_8));
                a.add().addFilepattern(".").call();
                RevCommit committed =
                    a.commit().setMessage("seed").setAuthor("T", "t@e").setCommitter("T", "t@e").call();

                org.eclipse.jgit.api.PushCommand push = a.push();
                cred.applyTo(push);
                push.call();

                // a fresh clone sees the pushed commit (clone + fetch path)
                Path workB = tmp.resolve("workB");
                try (Git b = Git.cloneRepository().setURI(url).setDirectory(workB.toFile()).call()) {
                    assertTrue(
                        Files.exists(workB.resolve("hello.txt")), "pushed file present after clone");
                    List<RevCommit> logB = toList(b.log().call());
                    assertEquals(1, logB.size());
                    assertEquals(committed.getName(), logB.get(0).getName(), "same commit fetched");
                }

                // an explicit fetch into workB after a second push also works
                Files.write(
                    workA.resolve("hello.txt"), "again".getBytes(StandardCharsets.UTF_8));
                a.add().addFilepattern(".").call();
                a.commit().setMessage("second").setAuthor("T", "t@e").setCommitter("T", "t@e").call();
                org.eclipse.jgit.api.PushCommand push2 = a.push();
                cred.applyTo(push2);
                push2.call();

                try (Git b = Git.open(tmp.resolve("workB").toFile())) {
                    org.eclipse.jgit.api.FetchCommand fetch = b.fetch();
                    cred.applyTo(fetch);
                    fetch.call();
                    long remoteCommits =
                        toList(b.log().add(b.getRepository().resolve("origin/master")).call()).size();
                    assertEquals(2, remoteCommits, "fetch pulled the second commit");
                }
            }
        }
    }

    @Test
    void close_releasesTheRepositoryHandle(@TempDir Path tmp) throws Exception {
        BareRepo remote = BareRepo.create(tmp.resolve("remote.git"));
        Path dir = remote.path();
        remote.close();
        assertFalse(dir.toString().isEmpty());
    }

    private static List<RevCommit> toList(Iterable<RevCommit> commits) {
        java.util.ArrayList<RevCommit> out = new java.util.ArrayList<RevCommit>();
        for (RevCommit c : commits) {
            out.add(c);
        }
        return out;
    }
}
