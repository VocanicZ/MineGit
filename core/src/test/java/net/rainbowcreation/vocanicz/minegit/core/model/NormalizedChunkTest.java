package net.rainbowcreation.vocanicz.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizedChunkTest {

    private static NormalizedSection section(int fill) {
        int[] idx = new int[NormalizedSection.VOLUME];
        Arrays.fill(idx, fill);
        return new NormalizedSection(
                Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone")), idx);
    }

    private static NormalizedSection[] sections() {
        return new NormalizedSection[] {section(0), null, section(1)};
    }

    private static int[] biomes() {
        int[] b = new int[16];
        Arrays.fill(b, 7);
        return b;
    }

    private static List<BlockEntity> blockEntities() {
        return Collections.singletonList(new BlockEntity(1, 2, 3, "{id:\"x\"}"));
    }

    private static NormalizedChunk chunk() {
        return new NormalizedChunk(3, -7, -4, sections(), biomes(), blockEntities());
    }

    @Test
    void equalByValue() {
        assertEquals(chunk(), chunk());
        assertEquals(chunk().hashCode(), chunk().hashCode());
    }

    @Test
    void exposesFields() {
        NormalizedChunk c = chunk();
        assertEquals(3, c.getCx());
        assertEquals(-7, c.getCz());
        assertEquals(-4, c.getMinSection());
        assertArrayEquals(biomes(), c.getBiomes());
        assertEquals(blockEntities(), c.getBlockEntities());
        assertEquals(3, c.getSections().length);
    }

    @Test
    void nullSectionMeansEmpty() {
        assertNull(chunk().getSections()[1]);
    }

    @Test
    void unequalWhenPositionDiffers() {
        assertNotEquals(chunk(),
                new NormalizedChunk(4, -7, -4, sections(), biomes(), blockEntities()));
    }

    @Test
    void unequalWhenSectionsDiffer() {
        NormalizedSection[] other = new NormalizedSection[] {section(1), null, section(1)};
        assertNotEquals(chunk(),
                new NormalizedChunk(3, -7, -4, other, biomes(), blockEntities()));
    }

    @Test
    void unequalWhenNullnessOfSectionDiffers() {
        NormalizedSection[] other = new NormalizedSection[] {section(0), section(0), section(1)};
        assertNotEquals(chunk(),
                new NormalizedChunk(3, -7, -4, other, biomes(), blockEntities()));
    }

    @Test
    void unequalWhenBiomesDiffer() {
        int[] other = new int[16];
        Arrays.fill(other, 9);
        assertNotEquals(chunk(),
                new NormalizedChunk(3, -7, -4, sections(), other, blockEntities()));
    }

    @Test
    void unequalWhenBlockEntitiesDiffer() {
        List<BlockEntity> other = Collections.singletonList(new BlockEntity(9, 9, 9, "{id:\"y\"}"));
        assertNotEquals(chunk(),
                new NormalizedChunk(3, -7, -4, sections(), biomes(), other));
    }

    @Test
    void sectionsArrayIsDefensivelyCopied() {
        NormalizedSection[] src = sections();
        NormalizedChunk c = new NormalizedChunk(3, -7, -4, src, biomes(), blockEntities());
        src[0] = section(1);
        assertEquals(chunk(), c);
    }

    @Test
    void biomesArrayIsDefensivelyCopied() {
        int[] src = biomes();
        NormalizedChunk c = new NormalizedChunk(3, -7, -4, sections(), src, blockEntities());
        src[0] = 99;
        assertEquals(7, c.getBiomes()[0]);
    }

    @Test
    void blockEntitiesAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> chunk().getBlockEntities().add(new BlockEntity(0, 0, 0, "{}")));
    }

    @Test
    void nullSectionsArrayRejected() {
        assertThrows(NullPointerException.class,
                () -> new NormalizedChunk(0, 0, 0, null, biomes(), blockEntities()));
    }
}
