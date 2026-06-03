package com.minegit.core.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.minegit.core.model.BlockEntity;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MgcCodecTest {

    private static int[] indices(int fill) {
        int[] idx = new int[NormalizedSection.VOLUME];
        Arrays.fill(idx, fill);
        return idx;
    }

    private static int[] biomes(int n, int fill) {
        int[] b = new int[n];
        Arrays.fill(b, fill);
        return b;
    }

    private static NormalizedSection section(List<BlockState> palette, int[] idx) {
        return new NormalizedSection(palette, idx);
    }

    private static BlockState stateWithProps() {
        Map<String, String> props = new LinkedHashMap<String, String>();
        // Deliberately out-of-key-order insertion to prove key-sorted serialization.
        props.put("waterlogged", "false");
        props.put("facing", "north");
        props.put("half", "top");
        return new BlockState("minecraft:oak_stairs", props);
    }

    @Test
    void roundTripsSimpleChunk() {
        List<BlockState> palette = Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
        int[] idx = indices(0);
        idx[0] = 1;
        idx[100] = 1;
        NormalizedChunk chunk =
                new NormalizedChunk(
                        3,
                        -7,
                        -4,
                        new NormalizedSection[] {section(palette, idx)},
                        biomes(64, 1),
                        Collections.<BlockEntity>emptyList());

        assertEquals(chunk, MgcCodec.deserialize(MgcCodec.serialize(chunk)));
    }

    @Test
    void roundTripsChunkWithPropsMultiSectionAndBlockEntities() {
        // Canonical id-order: minecraft:air < minecraft:oak_stairs < minecraft:stone.
        List<BlockState> p0 =
                Arrays.asList(BlockState.AIR, stateWithProps(), new BlockState("minecraft:stone"));
        int[] i0 = indices(0);
        i0[5] = 1;
        i0[6] = 2;
        List<BlockState> p1 =
                Arrays.asList(new BlockState("minecraft:dirt"), new BlockState("minecraft:grass_block"));
        int[] i1 = indices(0);
        i1[4095] = 1;

        // Canonical (y,z,x) order: (0,64,0) before (2,70,3).
        List<BlockEntity> bes =
                Arrays.asList(
                        new BlockEntity(0, 64, 0, "{id:\"sign\"}"),
                        new BlockEntity(2, 70, 3, "{id:\"chest\"}"));

        NormalizedChunk chunk =
                new NormalizedChunk(
                        -1,
                        2,
                        -4,
                        new NormalizedSection[] {section(p0, i0), section(p1, i1)},
                        biomes(64, 3),
                        bes);

        // Built in canonical form, so round-trip is exact.
        assertEquals(chunk, MgcCodec.deserialize(MgcCodec.serialize(chunk)));
    }

    @Test
    void serializingTwiceIsByteIdentical() {
        List<BlockState> palette = Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
        int[] idx = indices(1);
        NormalizedChunk chunk =
                new NormalizedChunk(
                        0, 0, 0, new NormalizedSection[] {section(palette, idx)}, biomes(0, 0),
                        Collections.<BlockEntity>emptyList());

        assertArrayEquals(MgcCodec.serialize(chunk), MgcCodec.serialize(chunk));
    }

    @Test
    void shuffledPaletteSerializesIdentically() {
        // A: palette [stone, air]; pos 0 = stone, rest = air.
        List<BlockState> pa = Arrays.asList(new BlockState("minecraft:stone"), BlockState.AIR);
        int[] ia = indices(1);
        ia[0] = 0;
        NormalizedChunk a =
                new NormalizedChunk(
                        5, 5, 0, new NormalizedSection[] {section(pa, ia)}, biomes(4, 0),
                        Collections.<BlockEntity>emptyList());

        // B: palette [air, stone] (shuffled); pos 0 = stone, rest = air — same content.
        List<BlockState> pb = Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
        int[] ib = indices(0);
        ib[0] = 1;
        NormalizedChunk b =
                new NormalizedChunk(
                        5, 5, 0, new NormalizedSection[] {section(pb, ib)}, biomes(4, 0),
                        Collections.<BlockEntity>emptyList());

        assertNotEquals(a, b); // different palette order => not value-equal at the model level
        assertArrayEquals(MgcCodec.serialize(a), MgcCodec.serialize(b)); // ...but canonically identical
        // Both decode to the same canonical chunk.
        assertEquals(MgcCodec.deserialize(MgcCodec.serialize(a)),
                MgcCodec.deserialize(MgcCodec.serialize(b)));
    }

    @Test
    void emptySectionsOmittedAndPreservedAsNullSlots() {
        List<BlockState> palette = Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
        NormalizedSection[] secs = new NormalizedSection[4];
        secs[2] = section(palette, indices(1)); // only index 2 present; 0,1,3 are null (empty)
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, -1, secs, biomes(0, 0),
                        Collections.<BlockEntity>emptyList());

        NormalizedChunk back = MgcCodec.deserialize(MgcCodec.serialize(chunk));
        assertEquals(chunk, back);
        assertArrayEquals(new NormalizedSection[] {null, null, secs[2], null}, back.getSections());
    }

    @Test
    void blockEntitiesEmittedInYZXOrder() {
        List<BlockState> palette = Collections.singletonList(BlockState.AIR);
        List<BlockEntity> shuffled =
                Arrays.asList(
                        new BlockEntity(3, 70, 1, "c"),
                        new BlockEntity(1, 70, 1, "b"),
                        new BlockEntity(0, 64, 5, "a"),
                        new BlockEntity(9, 64, 0, "z"));
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(0, 0), shuffled);

        List<BlockEntity> got = MgcCodec.deserialize(MgcCodec.serialize(chunk)).getBlockEntities();
        List<BlockEntity> expected =
                Arrays.asList(
                        new BlockEntity(9, 64, 0, "z"), // y=64,z=0
                        new BlockEntity(0, 64, 5, "a"), // y=64,z=5
                        new BlockEntity(1, 70, 1, "b"), // y=70,z=1,x=1
                        new BlockEntity(3, 70, 1, "c")); // y=70,z=1,x=3
        assertEquals(expected, got);
    }

    @Test
    void blockEntityInputOrderDoesNotAffectBytes() {
        List<BlockState> palette = Collections.singletonList(BlockState.AIR);
        int[] idx = indices(0);
        List<BlockEntity> orderA =
                Arrays.asList(new BlockEntity(1, 5, 2, "x"), new BlockEntity(0, 1, 0, "y"));
        List<BlockEntity> orderB =
                Arrays.asList(new BlockEntity(0, 1, 0, "y"), new BlockEntity(1, 5, 2, "x"));
        NormalizedChunk a =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, idx)},
                        biomes(0, 0), orderA);
        NormalizedChunk b =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, idx)},
                        biomes(0, 0), orderB);
        assertArrayEquals(MgcCodec.serialize(a), MgcCodec.serialize(b));
    }

    @Test
    void roundTripsLargePaletteCrossingLongBoundaries() {
        // 300 distinct states -> 9 bits per index, indices span long boundaries.
        List<BlockState> palette = new ArrayList<BlockState>();
        for (int i = 0; i < 300; i++) {
            palette.add(new BlockState("minecraft:block_" + i));
        }
        int[] idx = new int[4096];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = (i * 7) % 300;
        }
        // Palette built in canonical (sorted) order would differ from "block_10" lexical order,
        // so sort the palette ourselves and remap indices to keep the input canonical.
        java.util.List<BlockState> sorted = new ArrayList<BlockState>(palette);
        Collections.sort(sorted, java.util.Comparator.comparing(BlockState::getId));
        int[] remapped = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            remapped[i] = sorted.indexOf(palette.get(idx[i]));
        }
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(sorted, remapped)},
                        biomes(0, 0), Collections.<BlockEntity>emptyList());

        assertEquals(chunk, MgcCodec.deserialize(MgcCodec.serialize(chunk)));
    }

    @Test
    void singleEntryPaletteRoundTrips() {
        List<BlockState> palette = Collections.singletonList(new BlockState("minecraft:bedrock"));
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(0, 0), Collections.<BlockEntity>emptyList());
        assertEquals(chunk, MgcCodec.deserialize(MgcCodec.serialize(chunk)));
    }

    @Test
    void rejectsBadMagic() {
        byte[] junk = new byte[] {'N', 'O', 'P', 'E', 1, 0, 0};
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> MgcCodec.deserialize(junk));
    }

    @Test
    void rejectsUnsupportedVersion() {
        List<BlockState> palette = Collections.singletonList(BlockState.AIR);
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(0, 0), Collections.<BlockEntity>emptyList());
        byte[] bytes = MgcCodec.serialize(chunk);
        bytes[4] = 99; // corrupt formatVersion
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> MgcCodec.deserialize(bytes));
    }

    @Test
    void serializesAsCompressedVersion2() {
        List<BlockState> palette = Collections.singletonList(BlockState.AIR);
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(0, 0), Collections.<BlockEntity>emptyList());
        byte[] b = MgcCodec.serialize(chunk);
        assertEquals(2, b[4] & 0xFF); // formatVersion bumped: new chunks are DEFLATE-compressed
    }

    @Test
    void readsLegacyUncompressedV1Blob() {
        // A batch-1 blob: magic + version 1 + uncompressed body.
        List<BlockState> p0 =
                Arrays.asList(BlockState.AIR, stateWithProps(), new BlockState("minecraft:stone"));
        int[] i0 = indices(0);
        i0[5] = 1;
        i0[6] = 2;
        List<BlockEntity> bes =
                Arrays.asList(
                        new BlockEntity(0, 64, 0, "{id:\"sign\"}"),
                        new BlockEntity(2, 70, 3, "{id:\"chest\"}"));
        NormalizedChunk chunk =
                new NormalizedChunk(
                        -1, 2, -4, new NormalizedSection[] {section(p0, i0)}, biomes(64, 3), bes);

        byte[] legacy = MgcCodec.serializeLegacyV1(chunk);
        assertEquals(1, legacy[4] & 0xFF); // legacy is version 1, uncompressed
        assertEquals(chunk, MgcCodec.deserialize(legacy)); // reader auto-detects + decodes it
    }

    @Test
    void compressedDecodesEqualToLegacy() {
        List<BlockState> palette = Arrays.asList(BlockState.AIR, new BlockState("minecraft:stone"));
        int[] idx = indices(1);
        idx[0] = 0;
        NormalizedChunk chunk =
                new NormalizedChunk(
                        7, -3, -2, new NormalizedSection[] {section(palette, idx)}, biomes(8, 2),
                        Collections.<BlockEntity>emptyList());
        // Old and new encodings differ in bytes but decode to the same chunk.
        assertNotEquals(
                Arrays.toString(MgcCodec.serializeLegacyV1(chunk)),
                Arrays.toString(MgcCodec.serialize(chunk)));
        assertEquals(
                MgcCodec.deserialize(MgcCodec.serializeLegacyV1(chunk)),
                MgcCodec.deserialize(MgcCodec.serialize(chunk)));
    }

    @Test
    void compressedShrinksRepetitiveChunk() {
        // A wholly-uniform chunk compresses far below its uncompressed body.
        List<BlockState> palette = Collections.singletonList(new BlockState("minecraft:stone"));
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(64, 4), Collections.<BlockEntity>emptyList());
        org.junit.jupiter.api.Assertions.assertTrue(
                MgcCodec.serialize(chunk).length < MgcCodec.serializeLegacyV1(chunk).length);
    }

    @Test
    void startsWithMagic() {
        List<BlockState> palette = Collections.singletonList(BlockState.AIR);
        NormalizedChunk chunk =
                new NormalizedChunk(0, 0, 0, new NormalizedSection[] {section(palette, indices(0))},
                        biomes(0, 0), Collections.<BlockEntity>emptyList());
        byte[] b = MgcCodec.serialize(chunk);
        assertEquals('M', b[0]);
        assertEquals('G', b[1]);
        assertEquals('C', b[2]);
        assertEquals('1', b[3]);
    }
}
