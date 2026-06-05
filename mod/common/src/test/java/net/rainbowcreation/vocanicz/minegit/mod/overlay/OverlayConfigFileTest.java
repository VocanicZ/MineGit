package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;

/**
 * Headless tests for the on-disk client config (issue #81, Spec C §3.1): the loader reads a plain
 * {@code key=value} properties file on init, falls back to {@link OverlayConfig#defaults()} for any
 * missing/unreadable input, and writes a commented default template the first time the file is
 * absent so a player has something to edit. The parse/clamp rules themselves live in (and are
 * tested by) {@link OverlayConfig}; this class proves the file ↔ config glue.
 */
class OverlayConfigFileTest {

    @TempDir
    Path dir;

    @Test
    void missingFileWritesTemplateAndReturnsDefaults() throws IOException {
        Path path = dir.resolve("overlay.properties");
        assertFalse(Files.exists(path), "precondition: file absent");

        OverlayConfig c = OverlayConfigFile.load(path);

        assertEquals(OverlayConfig.DEFAULT_RENDER_CAP, c.getRenderCap());
        assertEquals(OverlayConfig.DEFAULT_AUTO_EXPIRE_SECONDS, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.DEFAULT_HUD_CORNER, c.getHudCorner());
        assertTrue(Files.exists(path), "a default template is written when the file is missing");

        // The written template must itself parse cleanly back to the defaults (no drift).
        OverlayConfig reloaded = OverlayConfigFile.load(path);
        assertEquals(OverlayConfig.DEFAULT_RENDER_CAP, reloaded.getRenderCap());
        assertEquals(OverlayConfig.DEFAULT_AUTO_EXPIRE_SECONDS, reloaded.getAutoExpireSeconds());
        assertEquals(OverlayConfig.DEFAULT_MAX_RENDER_DISTANCE, reloaded.getMaxRenderDistance());
        assertEquals(OverlayConfig.DEFAULT_KEYBIND, reloaded.getKeybind());
        assertEquals(OverlayConfig.DEFAULT_HUD_CORNER, reloaded.getHudCorner());
    }

    @Test
    void existingFileParsesValues() throws IOException {
        Path path = dir.resolve("overlay.properties");
        Files.write(path, "renderCap=256\nautoExpireSeconds=5\nhudCorner=bottom-right\n"
                .getBytes(StandardCharsets.UTF_8));

        OverlayConfig c = OverlayConfigFile.load(path);

        assertEquals(256, c.getRenderCap());
        assertEquals(5, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.HudCorner.BOTTOM_RIGHT, c.getHudCorner());
        // Unspecified keys still fall back to their defaults.
        assertEquals(OverlayConfig.DEFAULT_MAX_RENDER_DISTANCE, c.getMaxRenderDistance());
        assertEquals(OverlayConfig.DEFAULT_KEYBIND, c.getKeybind());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() throws IOException {
        Path path = dir.resolve("overlay.properties");
        Files.write(path, ("# MineGit overlay config\n"
                + "\n"
                + "   # autoExpireSeconds below\n"
                + "autoExpireSeconds=10\n"
                + "\n").getBytes(StandardCharsets.UTF_8));

        OverlayConfig c = OverlayConfigFile.load(path);

        assertEquals(10, c.getAutoExpireSeconds());
        assertEquals(OverlayConfig.DEFAULT_RENDER_CAP, c.getRenderCap());
    }

    /**
     * Acceptance (#81): config values loaded from the file actually change overlay behavior — proven
     * end-to-end for {@code renderCap} (caps {@link OverlayState#visibleBoxes}) and
     * {@code autoExpireSeconds} (drives {@link OverlayState#isExpired} via {@link OverlayConfig#lifetimeTicks}).
     */
    @Test
    void loadedRenderCapAndAutoExpireChangeOverlayBehavior() throws IOException {
        Path path = dir.resolve("overlay.properties");
        Files.write(path, "renderCap=2\nautoExpireSeconds=1\nmaxRenderDistance=1000\n"
                .getBytes(StandardCharsets.UTF_8));
        OverlayConfig c = OverlayConfigFile.load(path);

        // Three in-range boxes; the loaded cap of 2 keeps the two nearest and reports one dropped.
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        BlockState stone = new BlockState("minecraft:stone");
        dims.put(DimensionId.OVERWORLD, Arrays.asList(new ChunkDiff(new ChunkPos(0, 0), Arrays.asList(
                BlockChange.add(1, 64, 0, stone),
                BlockChange.add(2, 64, 0, stone),
                BlockChange.add(3, 64, 0, stone)))));
        WorldDiff diff = new WorldDiff(dims, 3, 0, 0);
        OverlayState state = new OverlayState(diff, "main", "HEAD", 100L);

        OverlayState.VisibleBoxes visible = state.visibleBoxes(
                DimensionId.OVERWORLD, 0, 64, 0, c.getMaxRenderDistance(), c.getRenderCap());
        assertEquals(2, visible.getBoxes().size(), "renderCap=2 caps the drawn boxes");
        assertEquals(1, visible.getDropped(), "the third in-range box is dropped");

        // autoExpireSeconds=1 → 20-tick lifetime: not yet expired at +19, expired at +20.
        assertEquals(20L, c.lifetimeTicks());
        assertFalse(state.isExpired(119L, c.lifetimeTicks()));
        assertTrue(state.isExpired(120L, c.lifetimeTicks()));
    }

    @Test
    void unreadableSourceFallsBackToDefaults() {
        // A directory at the config path cannot be read as a properties file → defaults, no throw.
        Path notAFile = dir; // the temp directory itself exists but is not a regular file

        OverlayConfig c = OverlayConfigFile.load(notAFile);

        assertEquals(OverlayConfig.DEFAULT_RENDER_CAP, c.getRenderCap());
        assertEquals(OverlayConfig.DEFAULT_AUTO_EXPIRE_SECONDS, c.getAutoExpireSeconds());
    }
}
