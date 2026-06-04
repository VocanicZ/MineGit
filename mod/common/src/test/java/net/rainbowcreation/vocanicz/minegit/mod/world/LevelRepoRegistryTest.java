package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LevelRepoRegistry}: one MineGit repo per level, laid out under the world save
 * folder and bound by the level's namespaced key (Spec D §3). Pure path/IO logic — no Minecraft.
 */
class LevelRepoRegistryTest {

    @Test
    void repoPathIsUnderMinegitDirOfSaveFolder(@TempDir Path saveRoot) {
        LevelRepoRegistry registry = new LevelRepoRegistry(saveRoot);
        Path repo = registry.repoPath("minecraft:overworld");
        assertEquals(saveRoot.resolve("minegit").resolve("minecraft_overworld"), repo);
    }

    @Test
    void distinctLevelsGetDistinctRepos(@TempDir Path saveRoot) {
        LevelRepoRegistry registry = new LevelRepoRegistry(saveRoot);
        assertFalse(
                registry.repoPath("minecraft:overworld").equals(
                        registry.repoPath("minecraft:the_nether")));
    }

    @Test
    void bindCreatesRepoDirAndMarksBound(@TempDir Path saveRoot) {
        LevelRepoRegistry registry = new LevelRepoRegistry(saveRoot);
        assertFalse(registry.isBound("minecraft:overworld"));
        Path repo = registry.bind("minecraft:overworld");
        assertTrue(Files.isDirectory(repo));
        assertTrue(registry.isBound("minecraft:overworld"));
    }

    @Test
    void bindIsIdempotent(@TempDir Path saveRoot) {
        LevelRepoRegistry registry = new LevelRepoRegistry(saveRoot);
        Path first = registry.bind("minecraft:overworld");
        Path second = registry.bind("minecraft:overworld");
        assertEquals(first, second);
        assertEquals(1, registry.boundLevels().size());
    }

    @Test
    void bindingsSurviveReload(@TempDir Path saveRoot) {
        new LevelRepoRegistry(saveRoot).bind("minecraft:the_end");
        LevelRepoRegistry reloaded = new LevelRepoRegistry(saveRoot);
        assertTrue(reloaded.isBound("minecraft:the_end"));
    }
}
