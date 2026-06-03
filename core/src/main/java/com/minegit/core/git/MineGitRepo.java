package com.minegit.core.git;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.diff.WorldDiffer;
import com.minegit.core.format.MgcCodec;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.WorldDiff;
import com.minegit.core.repo.MineGitMeta;
import com.minegit.core.repo.RepoLayout;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * JGit-backed MineGit repository over an {@link RepoLayout MGRF} working tree.
 *
 * <p>The repository is a <strong>normal</strong> (non-bare) git repo whose working tree holds the
 * {@code .mgc} chunk blobs and SNBT metadata. Batch-1 operations:
 *
 * <ul>
 *   <li>{@link #init} — create the repo and the initial MGRF structures ({@code minegit.json},
 *       {@code level/level.dat.snbt}), then commit them.
 *   <li>{@link #commit} — serialize each dirty chunk to its MGRF {@code .mgc} path via
 *       {@link MgcCodec}, stage the changes, and create a commit whose author is the triggering
 *       player and whose committer is the fixed {@code MineGit <minegit@local>} identity. Because
 *       {@code .mgc} serialization is deterministic, a chunk whose content did not actually change
 *       produces no git delta and an otherwise-empty commit is skipped.
 *   <li>{@link #log} — walk commits newest-first.
 *   <li>{@link #readChunk} — decode a chunk's {@code .mgc} blob directly from a commit's tree via a
 *       {@link TreeWalk}, with no working-tree checkout.
 * </ul>
 *
 * <p>The class owns no threads; the caller performs world reads and git work on whatever thread it
 * chooses. No Minecraft dependencies.
 */
public final class MineGitRepo implements Closeable {

    /** Fixed committer name for every MineGit commit. */
    public static final String COMMITTER_NAME = "MineGit";

    /** Fixed committer email for every MineGit commit. */
    public static final String COMMITTER_EMAIL = "minegit@local";

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String LEVEL_DAT_PLACEHOLDER = "{}\n";

    private final Git git;
    private final Repository repository;
    private final RepoLayout layout;
    private final WorldAdapter adapter;
    private final Clock clock;

    private boolean firstChunkCommitDone;

    private MineGitRepo(Git git, RepoLayout layout, WorldAdapter adapter, Clock clock) {
        this.git = git;
        this.repository = git.getRepository();
        this.layout = layout;
        this.adapter = adapter;
        this.clock = clock;
    }

    /** Initializes a new MineGit repository in {@code repoDir}, using the system UTC clock. */
    public static MineGitRepo init(Path repoDir, WorldAdapter adapter) {
        return init(repoDir, adapter, Clock.systemUTC());
    }

    /**
     * Initializes a new MineGit repository in {@code repoDir}: creates the (non-bare) git repo,
     * writes the initial MGRF metadata ({@code minegit.json}, {@code level/level.dat.snbt}), and
     * commits them under the {@code MineGit <minegit@local>} identity. Returns an open handle.
     */
    public static MineGitRepo init(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        try {
            Files.createDirectories(repoDir);
            Git git = Git.init().setDirectory(repoDir.toFile()).call();
            RepoLayout layout = new RepoLayout(repoDir);
            MineGitRepo repo = new MineGitRepo(git, layout, adapter, clock);
            repo.writeInitialMetadata();
            repo.stageAll();
            PersonIdent committer = repo.committerIdent();
            git.commit()
                .setMessage("Initialize MineGit repository")
                .setAuthor(committer)
                .setCommitter(committer)
                .call();
            return repo;
        } catch (GitAPIException e) {
            throw new IllegalStateException("git init failed for " + repoDir, e);
        } catch (IOException e) {
            throw new UncheckedIOException("init failed for " + repoDir, e);
        }
    }

    /**
     * Clones the MineGit repository at {@code url} into {@code dir} and materializes a playable world
     * from it. After JGit fetches the remote and checks out its {@code HEAD} (the {@code .mgc} working
     * tree), every chunk in {@code HEAD} is decoded and handed to {@link WorldAdapter#writeChunk} so the
     * live world is rebuilt from scratch. Returns an open handle bound to {@code world}.
     *
     * <p>Authenticates with {@code cred} (a {@link DefaultGitCredential} suffices for an unauthenticated
     * {@code file://} remote). No Minecraft dependencies.
     */
    public static MineGitRepo clone(String url, Path dir, Credential cred, WorldAdapter world) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(cred, "cred");
        Objects.requireNonNull(world, "world");
        try {
            Files.createDirectories(dir);
            org.eclipse.jgit.api.CloneCommand clone =
                org.eclipse.jgit.api.Git.cloneRepository().setURI(url).setDirectory(dir.toFile());
            cred.applyTo(clone);
            Git git = clone.call();
            MineGitRepo repo = new MineGitRepo(git, new RepoLayout(dir), world, Clock.systemUTC());
            repo.firstChunkCommitDone = repo.headHasChunks();
            repo.materialize();
            return repo;
        } catch (GitAPIException e) {
            throw new IllegalStateException("clone failed for " + url, e);
        } catch (IOException e) {
            throw new UncheckedIOException("clone failed for " + url, e);
        }
    }

    /** Opens an already-initialized MineGit repository, using the system UTC clock. */
    public static MineGitRepo open(Path repoDir, WorldAdapter adapter) {
        return open(repoDir, adapter, Clock.systemUTC());
    }

    /** Opens an already-initialized MineGit repository in {@code repoDir}. */
    public static MineGitRepo open(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        try {
            Git git = Git.open(repoDir.toFile());
            MineGitRepo repo = new MineGitRepo(git, new RepoLayout(repoDir), adapter, clock);
            repo.firstChunkCommitDone = repo.headHasChunks();
            return repo;
        } catch (IOException e) {
            throw new UncheckedIOException("open failed for " + repoDir, e);
        }
    }

    /**
     * Snapshots the world into a commit. The chunk set is {@link WorldAdapter#allChunks()} on the
     * first content commit and {@link WorldAdapter#drainDirty()} thereafter; each chunk is read,
     * serialized to its {@code .mgc} path, and staged. The commit's author is {@code author} and its
     * committer is {@code MineGit <minegit@local>}.
     *
     * @return the created {@link CommitInfo}, or {@code null} if nothing actually changed (a chunk
     *     re-serialized to byte-identical content yields no git delta, so no commit is created).
     */
    public CommitInfo commit(String message, Author author) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(author, "author");

        Set<ChunkRef> refs;
        if (firstChunkCommitDone) {
            refs = adapter.drainDirty();
        } else {
            refs = adapter.allChunks();
            adapter.drainDirty(); // consume the dirty set so later drains start clean
            firstChunkCommitDone = true;
        }

        try {
            for (ChunkRef ref : refs) {
                writeChunk(ref.getDimension(), ref.getPos());
            }
            stageAll();
            if (!git.status().call().hasUncommittedChanges()) {
                return null;
            }
            Date when = Date.from(clock.instant());
            PersonIdent authorIdent = new PersonIdent(author.getName(), author.getEmail(), when, UTC);
            PersonIdent committerIdent =
                new PersonIdent(COMMITTER_NAME, COMMITTER_EMAIL, when, UTC);
            RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(authorIdent)
                .setCommitter(committerIdent)
                .call();
            return toCommitInfo(commit);
        } catch (GitAPIException e) {
            throw new IllegalStateException("commit failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException("commit failed", e);
        }
    }

    /** Commits in the conventional layout (author email synthesized from the player name). */
    public CommitInfo commit(String message, String authorName) {
        return commit(message, Author.of(authorName));
    }

    /** All commits, newest first. Empty if the repository has no commits yet. */
    public List<CommitInfo> log() {
        try {
            ObjectId head = repository.resolve("HEAD");
            if (head == null) {
                return Collections.emptyList();
            }
            List<CommitInfo> out = new ArrayList<CommitInfo>();
            try (RevWalk walk = new RevWalk(repository)) {
                walk.sort(RevSort.COMMIT_TIME_DESC);
                walk.markStart(walk.parseCommit(head));
                for (RevCommit c : walk) {
                    out.add(toCommitInfo(c));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("log failed", e);
        }
    }

    /**
     * Decodes the chunk at {@code (dimension, pos)} from the tree of revision {@code rev} (e.g.
     * {@code "HEAD"}, a branch name, or a commit SHA) by reading its {@code .mgc} blob through a
     * {@link TreeWalk}. No working-tree checkout occurs — the bytes come straight from the object
     * database — so the diff engine can build a tree-backed chunk source.
     *
     * @return the decoded chunk, or {@code null} if {@code rev} resolves to nothing or holds no
     *     {@code .mgc} for that chunk.
     */
    public NormalizedChunk readChunk(String rev, DimensionId dimension, ChunkPos pos) {
        Objects.requireNonNull(rev, "rev");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos, "pos");
        try {
            ObjectId commitId = repository.resolve(rev);
            if (commitId == null) {
                return null;
            }
            String path = relativeChunkPath(dimension, pos);
            try (RevWalk walk = new RevWalk(repository)) {
                RevTree tree = walk.parseCommit(commitId).getTree();
                try (TreeWalk tw = TreeWalk.forPath(repository, path, tree)) {
                    if (tw == null) {
                        return null;
                    }
                    byte[] bytes = repository.open(tw.getObjectId(0)).getBytes();
                    return MgcCodec.deserialize(bytes);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("readChunk failed", e);
        }
    }

    /**
     * Lists every chunk present in the tree of revision {@code rev} by walking it for {@code .mgc}
     * blobs and recovering each {@code (dimension, pos)} from its MGRF path. Returns an empty set if
     * {@code rev} resolves to nothing. Drives the diff engine's tree-backed {@link ChunkSource},
     * which needs the position set to take the union with another source.
     */
    public Set<ChunkRef> listChunks(String rev) {
        Objects.requireNonNull(rev, "rev");
        try {
            ObjectId commitId = repository.resolve(rev);
            if (commitId == null) {
                return Collections.emptySet();
            }
            java.util.Set<ChunkRef> out = new java.util.HashSet<ChunkRef>();
            try (RevWalk walk = new RevWalk(repository)) {
                RevTree tree = walk.parseCommit(commitId).getTree();
                try (TreeWalk tw = new TreeWalk(repository)) {
                    tw.addTree(tree);
                    tw.setRecursive(true);
                    while (tw.next()) {
                        String path = tw.getPathString();
                        if (path.endsWith(".mgc")) {
                            RepoLayout.ChunkRef parsed =
                                layout.parseChunkPath(java.nio.file.Paths.get(path));
                            out.add(new ChunkRef(parsed.getDimension(), parsed.getPos()));
                        }
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("listChunks failed", e);
        }
    }

    /**
     * Creates a local branch named {@code name} at the current {@code HEAD}.
     *
     * @return a local {@link BranchRef} for the new branch.
     */
    public BranchRef branch(String name) {
        Objects.requireNonNull(name, "name");
        try {
            git.branchCreate().setName(name).call();
            return BranchRef.local(name);
        } catch (GitAPIException e) {
            throw new IllegalStateException("branch create failed for " + name, e);
        }
    }

    /**
     * Lists every branch ref: local branches ({@code refs/heads/*}) and remote-tracking branches
     * ({@code refs/remotes/*}), tagged <strong>distinctly</strong> via {@link BranchRef#isRemote()}.
     * A remote-tracking ref keeps its remote-qualified name (e.g. {@code "origin/main"}) so it never
     * collides with a like-named local branch.
     */
    public List<BranchRef> branches() {
        try {
            List<BranchRef> out = new ArrayList<BranchRef>();
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String full = ref.getName();
                if (full.startsWith("refs/heads/")) {
                    out.add(BranchRef.local(full.substring("refs/heads/".length())));
                } else if (full.startsWith("refs/remotes/")) {
                    out.add(BranchRef.remote(full.substring("refs/remotes/".length())));
                }
            }
            return out;
        } catch (GitAPIException e) {
            throw new IllegalStateException("branch list failed", e);
        }
    }

    /**
     * Reverts the live world to {@code target} (a branch name, commit SHA, or relative rev like
     * {@code "HEAD~1"}), refusing to clobber an uncommitted working tree.
     *
     * @throws WorkingTreeDirtyException if the live world differs from {@code HEAD}
     * @see #checkout(String, boolean)
     */
    public WorldDiff checkout(String target) {
        return checkout(target, false);
    }

    /**
     * Reverts the live world to {@code target}. Computes {@code HEAD → target} as a {@link WorldDiff}
     * via {@link WorldDiffer}, replays each chunk's {@link com.minegit.core.model.BlockChange}s onto
     * the {@link WorldAdapter} via {@link WorldAdapter#apply}, then moves the local ref to
     * {@code target} (a hard reset that also syncs the {@code .mgc} working tree). Returns the applied
     * {@code WorldDiff} so a frontend can resend the affected chunks.
     *
     * <p>Unless {@code force} is set, a non-empty working tree (live world ≠ {@code HEAD}) raises a
     * {@link WorkingTreeDirtyException}, mirroring git's refusal to clobber uncommitted work. No
     * Minecraft dependencies.
     */
    public WorldDiff checkout(String target, boolean force) {
        Objects.requireNonNull(target, "target");
        if (!force) {
            WorldDiff dirty = WorldDiffer.diffWorkingTree(this, adapter);
            if (!dirty.getDimensions().isEmpty()) {
                throw new WorkingTreeDirtyException(
                    "working tree has uncommitted changes (" + dirty + "); "
                        + "commit or checkout with force");
            }
        }
        WorldDiff applied = WorldDiffer.diffRefs(this, "HEAD", target);
        for (java.util.Map.Entry<DimensionId, List<ChunkDiff>> e
                : applied.getDimensions().entrySet()) {
            for (ChunkDiff chunkDiff : e.getValue()) {
                adapter.apply(e.getKey(), chunkDiff.getPos(), chunkDiff.getChanges());
            }
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(target).call();
        } catch (GitAPIException ex) {
            throw new IllegalStateException("checkout failed to move ref to " + target, ex);
        }
        return applied;
    }

    /**
     * Configures the {@code origin} remote to point at {@code url}, installing the standard
     * {@code +refs/heads/*:refs/remotes/origin/*} fetch refspec so a later {@link #fetch(Credential)}
     * updates the {@code origin/*} remote-tracking branches. Overwrites any existing {@code origin}.
     */
    public void remoteSet(String url) {
        Objects.requireNonNull(url, "url");
        try {
            org.eclipse.jgit.lib.StoredConfig config = repository.getConfig();
            config.setString("remote", "origin", "url", url);
            config.setString(
                "remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
        } catch (IOException e) {
            throw new UncheckedIOException("remoteSet failed for " + url, e);
        }
    }

    /**
     * Fetches from {@code origin}, updating the {@code origin/*} remote-tracking refs only. The
     * working tree and local branches are untouched — no checkout, no world change — so the diff
     * engine can compare against {@code origin/*} without disturbing the live world.
     */
    public void fetch(Credential cred) {
        Objects.requireNonNull(cred, "cred");
        try {
            org.eclipse.jgit.api.FetchCommand fetch = git.fetch().setRemote("origin");
            cred.applyTo(fetch);
            fetch.call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("fetch failed", e);
        }
    }

    /**
     * Fetches from {@code origin} and reverts the live world to the matching remote-tracking branch
     * ({@code origin/<current-branch>}) — {@code pull = fetch + checkout(origin/<branch>)}. Reuses the
     * {@link #checkout(String)} apply path: it computes {@code HEAD → origin/<branch>} as a
     * {@link WorldDiff}, replays each chunk's {@link com.minegit.core.model.BlockChange}s onto the
     * {@link WorldAdapter}, then fast-forwards the local ref. Returns the applied {@code WorldDiff} so a
     * frontend can resend the affected chunks.
     *
     * @throws WorkingTreeDirtyException if the live world differs from {@code HEAD} (the dirty-guard,
     *     mirroring git's refusal to clobber uncommitted work)
     */
    public WorldDiff pull(Credential cred) {
        Objects.requireNonNull(cred, "cred");
        fetch(cred);
        String branch;
        try {
            branch = repository.getBranch();
        } catch (IOException e) {
            throw new UncheckedIOException("pull failed to resolve current branch", e);
        }
        return checkout("origin/" + branch);
    }

    /**
     * Pushes the current branch to {@code origin} and reports the <strong>per-ref</strong> outcome.
     * Each attempted ref maps to {@link PushResult.Status#OK OK} (advanced),
     * {@link PushResult.Status#UP_TO_DATE UP_TO_DATE} (already current), or
     * {@link PushResult.Status#REJECTED REJECTED} (refused, e.g. a non-fast-forward) — the remote is
     * never force-updated.
     */
    public PushResult push(Credential cred) {
        Objects.requireNonNull(cred, "cred");
        try {
            String branch = repository.getBranch();
            org.eclipse.jgit.transport.RefSpec spec =
                new org.eclipse.jgit.transport.RefSpec(
                    "refs/heads/" + branch + ":refs/heads/" + branch);
            org.eclipse.jgit.api.PushCommand push =
                git.push().setRemote("origin").setRefSpecs(spec);
            cred.applyTo(push);
            List<PushResult.RefUpdate> updates = new ArrayList<PushResult.RefUpdate>();
            for (org.eclipse.jgit.transport.PushResult result : push.call()) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate u : result.getRemoteUpdates()) {
                    updates.add(
                        new PushResult.RefUpdate(
                            u.getRemoteName(), mapStatus(u.getStatus()), u.getMessage()));
                }
            }
            return new PushResult(updates);
        } catch (IOException e) {
            throw new UncheckedIOException("push failed", e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("push failed", e);
        }
    }

    private static PushResult.Status mapStatus(
            org.eclipse.jgit.transport.RemoteRefUpdate.Status status) {
        switch (status) {
            case OK:
                return PushResult.Status.OK;
            case UP_TO_DATE:
                return PushResult.Status.UP_TO_DATE;
            default:
                return PushResult.Status.REJECTED;
        }
    }

    /** The MGRF layout this repository writes to. */
    public RepoLayout getLayout() {
        return layout;
    }

    @Override
    public void close() {
        git.close();
    }

    // ---- internals ----------------------------------------------------------------------------

    private void writeInitialMetadata() throws IOException {
        List<String> dims = new ArrayList<String>();
        for (DimensionId d : adapter.dimensions()) {
            dims.add(d.getId());
        }
        MineGitMeta meta = new MineGitMeta(1, Collections.<String>emptyList(), dims);
        Files.createDirectories(layout.minegitJsonPath().getParent());
        meta.writeTo(layout.minegitJsonPath());
        Path levelDat = layout.levelDatPath();
        Files.createDirectories(levelDat.getParent());
        Files.write(levelDat, LEVEL_DAT_PLACEHOLDER.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes every chunk in {@code HEAD} and writes it into the world adapter (clone materialize). */
    private void materialize() {
        for (ChunkRef ref : listChunks("HEAD")) {
            NormalizedChunk chunk = readChunk("HEAD", ref.getDimension(), ref.getPos());
            if (chunk != null) {
                adapter.writeChunk(ref.getDimension(), chunk);
            }
        }
    }

    /** Serializes the live chunk to its {@code .mgc} path, or removes the file if the chunk is gone. */
    private void writeChunk(DimensionId dimension, ChunkPos pos) throws IOException {
        Path target = layout.chunkPath(dimension, pos);
        NormalizedChunk chunk = adapter.read(dimension, pos);
        if (chunk == null) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.write(target, MgcCodec.serialize(chunk));
    }

    /** Stages all additions, modifications, and deletions in the working tree. */
    private void stageAll() throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
    }

    private boolean headHasChunks() throws IOException {
        ObjectId head = repository.resolve("HEAD");
        if (head == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            RevTree tree = walk.parseCommit(head).getTree();
            try (TreeWalk tw = new TreeWalk(repository)) {
                tw.addTree(tree);
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getPathString().endsWith(".mgc")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String relativeChunkPath(DimensionId dimension, ChunkPos pos) {
        Path rel = layout.getRepoDir().relativize(layout.chunkPath(dimension, pos));
        return rel.toString().replace('\\', '/');
    }

    private PersonIdent committerIdent() {
        return new PersonIdent(COMMITTER_NAME, COMMITTER_EMAIL, Date.from(clock.instant()), UTC);
    }

    private static CommitInfo toCommitInfo(RevCommit c) {
        return new CommitInfo(
            c.getName(),
            c.getAuthorIdent().getName(),
            c.getFullMessage(),
            c.getCommitTime());
    }
}
