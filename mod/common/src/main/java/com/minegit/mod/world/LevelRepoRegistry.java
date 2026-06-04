package com.minegit.mod.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binds each {@code ServerLevel} to a MineGit repo and persists the binding (Spec D §3).
 *
 * <p>There is <strong>one repo per level (dimension save)</strong>, laid out under the world's save
 * folder at {@code <saveRoot>/minegit/<safeLevelKey>}, where the level key is its namespaced id
 * (e.g. {@code minecraft:overworld}) with path-unsafe characters folded to {@code _}. {@link
 * #bind(String)} records the binding and creates the repo directory; the bound set is written to
 * {@code <saveRoot>/minegit/levels.properties} so it survives a restart.
 *
 * <p>The registry is intentionally Minecraft-free so it is unit-testable on a plain JVM — the live
 * mod feeds it {@code server.getWorldPath(LevelResource.ROOT)} and
 * {@code level.dimension().identifier().toString()}.
 */
public final class LevelRepoRegistry {

    /** Sub-directory under the world save folder holding every per-level repo and the bindings. */
    static final String MINEGIT_DIR = "minegit";

    /** File under {@code minegit/} recording which levels are bound. */
    static final String BINDINGS_FILE = "levels.properties";

    private final Path minegitDir;
    private final Path bindingsFile;
    private final Set<String> bound = new TreeSet<String>();

    /**
     * @param saveRoot the world's save folder ({@code server.getWorldPath(LevelResource.ROOT)});
     *     its {@code minegit/} sub-directory is scanned for an existing {@code levels.properties} so
     *     prior bindings are restored
     */
    public LevelRepoRegistry(Path saveRoot) {
        Objects.requireNonNull(saveRoot, "saveRoot");
        this.minegitDir = saveRoot.resolve(MINEGIT_DIR);
        this.bindingsFile = minegitDir.resolve(BINDINGS_FILE);
        load();
    }

    /** The repo directory for {@code levelKey}: {@code <saveRoot>/minegit/<safeLevelKey>}. */
    public Path repoPath(String levelKey) {
        Objects.requireNonNull(levelKey, "levelKey");
        return minegitDir.resolve(safe(levelKey));
    }

    /** Whether {@code levelKey} has an existing MineGit binding. */
    public boolean isBound(String levelKey) {
        Objects.requireNonNull(levelKey, "levelKey");
        return bound.contains(levelKey);
    }

    /** An unmodifiable, sorted snapshot of every bound level key. */
    public Set<String> boundLevels() {
        return Collections.unmodifiableSet(new TreeSet<String>(bound));
    }

    /**
     * Binds {@code levelKey}: creates its repo directory and persists the binding. Idempotent — a
     * second bind of the same level just returns the path. Returns the repo path.
     */
    public Path bind(String levelKey) {
        Objects.requireNonNull(levelKey, "levelKey");
        Path repo = repoPath(levelKey);
        try {
            Files.createDirectories(repo);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create repo dir for level '" + levelKey + "'", e);
        }
        if (bound.add(levelKey)) {
            save();
        }
        return repo;
    }

    /** Folds a namespaced level key into a filesystem-safe directory name. */
    private static String safe(String levelKey) {
        return levelKey.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void load() {
        if (!Files.isRegularFile(bindingsFile)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(bindingsFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + bindingsFile, e);
        }
        for (String key : props.stringPropertyNames()) {
            bound.add(key);
        }
    }

    private void save() {
        Properties props = new Properties();
        for (String key : bound) {
            // Value records the relative repo dir for human readers; the key set is the binding.
            props.setProperty(key, MINEGIT_DIR + "/" + safe(key));
        }
        try {
            Files.createDirectories(minegitDir);
            try (OutputStream out = Files.newOutputStream(bindingsFile)) {
                props.store(out, "MineGit level↔repo bindings");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + bindingsFile, e);
        }
    }
}
