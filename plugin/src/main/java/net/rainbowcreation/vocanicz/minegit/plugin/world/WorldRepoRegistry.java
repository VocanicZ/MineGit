package net.rainbowcreation.vocanicz.minegit.plugin.world;

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
 * Binds each Bukkit world to a MineGit repo and persists the binding (Spec B §4).
 *
 * <p>There is <strong>one repo per world</strong>, laid out at
 * {@code <dataFolder>/repos/<worldName>}. {@link #bind(String)} records the binding and creates the
 * repo directory; the set of bound worlds is written to {@code <dataFolder>/worlds.properties} so it
 * survives a server restart. The registry is intentionally Bukkit-free so it is unit-testable on a
 * plain JVM — the live plugin feeds it world names from {@code World.getName()}.
 */
public final class WorldRepoRegistry {

    /** Sub-directory under the plugin data folder holding every per-world repo. */
    static final String REPOS_DIR = "repos";

    /** File under the data folder recording which worlds are bound. */
    static final String BINDINGS_FILE = "worlds.properties";

    private final Path dataFolder;
    private final Path bindingsFile;
    private final Set<String> bound = new TreeSet<String>();

    /**
     * @param dataFolder the plugin's data folder ({@code plugins/MineGit}); created if absent and
     *     scanned for an existing {@code worlds.properties} so prior bindings are restored
     */
    public WorldRepoRegistry(Path dataFolder) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.bindingsFile = dataFolder.resolve(BINDINGS_FILE);
        load();
    }

    /** The repo directory for {@code worldName}: {@code <dataFolder>/repos/<worldName>}. */
    public Path repoPath(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        return dataFolder.resolve(REPOS_DIR).resolve(worldName);
    }

    /** Whether {@code worldName} has an existing MineGit binding. */
    public boolean isBound(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        return bound.contains(worldName);
    }

    /** An unmodifiable, sorted snapshot of every bound world name. */
    public Set<String> boundWorlds() {
        return Collections.unmodifiableSet(new TreeSet<String>(bound));
    }

    /**
     * Binds {@code worldName}: creates its repo directory and persists the binding. Idempotent — a
     * second bind of the same world just returns the path. Returns the repo path.
     */
    public Path bind(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        Path repo = repoPath(worldName);
        try {
            Files.createDirectories(repo);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create repo dir for world '" + worldName + "'", e);
        }
        if (bound.add(worldName)) {
            save();
        }
        return repo;
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
        for (String name : props.stringPropertyNames()) {
            bound.add(name);
        }
    }

    private void save() {
        Properties props = new Properties();
        for (String name : bound) {
            // Value is unused today; the key set is the binding. Record the relative repo path for
            // human readers of the file.
            props.setProperty(name, REPOS_DIR + "/" + name);
        }
        try {
            Files.createDirectories(dataFolder);
            try (OutputStream out = Files.newOutputStream(bindingsFile)) {
                props.store(out, "MineGit world↔repo bindings");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + bindingsFile, e);
        }
    }
}
