package net.rainbowcreation.vocanicz.minegit.core.fake;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

/**
 * A throwaway local <strong>bare</strong> git repository for remote-operation tests. Created with
 * {@code git init --bare} via JGit and served over a {@code file://} URL, it stands in for a real
 * remote so {@code clone} / {@code fetch} / {@code push} can be exercised headless — no network, no
 * GitHub, and no {@link net.rainbowcreation.vocanicz.minegit.core.git.Credential} that actually authenticates required.
 *
 * <p>Use it as the remote for any transport test:
 *
 * <pre>{@code
 * try (BareRepo remote = BareRepo.create(tmp.resolve("remote.git"))) {
 *     Git.cloneRepository().setURI(remote.fileUrl()).setDirectory(work).call();
 *     // ... commit and push back to remote.fileUrl() ...
 * }
 * }</pre>
 *
 * <p>{@link #close()} releases the JGit handle; the on-disk directory is the caller's (typically a
 * JUnit {@code @TempDir}), so it is not deleted here. No Minecraft dependencies.
 */
public final class BareRepo implements Closeable {

    private final Path dir;
    private final Git git;

    private BareRepo(Path dir, Git git) {
        this.dir = dir;
        this.git = git;
    }

    /**
     * Initializes a bare repository at {@code dir} (created if absent) and returns an open handle.
     *
     * @throws NullPointerException if {@code dir} is {@code null}
     * @throws IllegalStateException if JGit fails to initialize the repository
     */
    public static BareRepo create(Path dir) {
        Objects.requireNonNull(dir, "dir");
        try {
            Files.createDirectories(dir);
            Git git = Git.init().setBare(true).setDirectory(dir.toFile()).call();
            return new BareRepo(dir, git);
        } catch (GitAPIException e) {
            throw new IllegalStateException("git init --bare failed for " + dir, e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create bare repo dir " + dir, e);
        }
    }

    /** The on-disk directory backing this bare repository. */
    public Path path() {
        return dir;
    }

    /**
     * The {@code file://} URL clients clone / fetch / push against. Derived from {@link #path()}, so
     * it is absolute and platform-correct.
     */
    public String fileUrl() {
        return dir.toUri().toString();
    }

    /** The underlying JGit {@link Repository}; {@link Repository#isBare()} is {@code true}. */
    public Repository repository() {
        return git.getRepository();
    }

    @Override
    public void close() {
        git.close();
    }
}
