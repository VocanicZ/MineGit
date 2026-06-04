package com.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The world&harr;repo registry binds each Bukkit world to a MineGit repo under
 * {@code <dataFolder>/repos/<worldName>} and persists the binding across plugin restarts (Spec B §4).
 */
class WorldRepoRegistryTest {

    @Test
    void repoPathIsUnderReposDirectoryNamedAfterTheWorld(@TempDir Path dataFolder) {
        WorldRepoRegistry registry = new WorldRepoRegistry(dataFolder);

        assertEquals(dataFolder.resolve("repos").resolve("world"), registry.repoPath("world"));
    }

    @Test
    void unboundWorldIsNotBound(@TempDir Path dataFolder) {
        WorldRepoRegistry registry = new WorldRepoRegistry(dataFolder);

        assertFalse(registry.isBound("world"));
    }

    @Test
    void bindMarksTheWorldBoundAndCreatesTheRepoDirectory(@TempDir Path dataFolder) {
        WorldRepoRegistry registry = new WorldRepoRegistry(dataFolder);

        Path repo = registry.bind("world");

        assertTrue(registry.isBound("world"));
        assertEquals(dataFolder.resolve("repos").resolve("world"), repo);
        assertTrue(Files.isDirectory(repo));
    }

    @Test
    void bindingPersistsAcrossRegistryInstances(@TempDir Path dataFolder) throws IOException {
        new WorldRepoRegistry(dataFolder).bind("world");

        WorldRepoRegistry reloaded = new WorldRepoRegistry(dataFolder);

        assertTrue(reloaded.isBound("world"));
        assertEquals(dataFolder.resolve("repos").resolve("world"), reloaded.repoPath("world"));
    }
}
