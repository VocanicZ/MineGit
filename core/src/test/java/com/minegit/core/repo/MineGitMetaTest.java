package com.minegit.core.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineGitMetaTest {

    @Test
    void writeThenReadFromDiskRoundTrips(@TempDir Path dir) {
        MineGitMeta meta =
            new MineGitMeta(1, Arrays.asList("1.8.8"), Arrays.asList("overworld", "the_nether"));
        RepoLayout layout = new RepoLayout(dir);
        Path file = layout.minegitJsonPath();
        meta.writeTo(file);
        assertEquals(meta, MineGitMeta.readFrom(file));
    }

    @Test
    void parserToleratesAnyFieldOrderAndWhitespace() {
        String json =
            "{ \"dimensions\":[\"overworld\"] ,\n"
                + "  \"formatVersion\" : 7,\n"
                + "  \"mcVersionsSeen\": [ \"1.8.8\" ] }";
        MineGitMeta meta = MineGitMeta.fromJson(json);
        assertEquals(7, meta.getFormatVersion());
        assertEquals(Arrays.asList("1.8.8"), meta.getMcVersionsSeen());
        assertEquals(Arrays.asList("overworld"), meta.getDimensions());
    }

    @Test
    void toJsonIsDeterministicAndSorted() {
        MineGitMeta meta =
            new MineGitMeta(
                1,
                Arrays.asList("1.20.4", "1.8.8", "1.8.8"),
                Arrays.asList("the_nether", "overworld"));
        String json = meta.toJson();
        String expected =
            "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"mcVersionsSeen\": [\n"
                + "    \"1.20.4\",\n"
                + "    \"1.8.8\"\n"
                + "  ],\n"
                + "  \"dimensions\": [\n"
                + "    \"overworld\",\n"
                + "    \"the_nether\"\n"
                + "  ]\n"
                + "}\n";
        assertEquals(expected, json);
    }

    @Test
    void roundTripsThroughJson() {
        MineGitMeta meta =
            new MineGitMeta(
                2,
                Arrays.asList("1.8.8", "1.20.4"),
                Arrays.asList("overworld", "the_end"));
        MineGitMeta parsed = MineGitMeta.fromJson(meta.toJson());
        assertEquals(meta, parsed);
    }

    @Test
    void emptyArrays() {
        MineGitMeta meta = new MineGitMeta(1, Arrays.asList(), Arrays.asList());
        String json = meta.toJson();
        String expected =
            "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"mcVersionsSeen\": [],\n"
                + "  \"dimensions\": []\n"
                + "}\n";
        assertEquals(expected, json);
        assertEquals(meta, MineGitMeta.fromJson(json));
    }
}
