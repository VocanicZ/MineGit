package com.minegit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.DimensionId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeWorldStoreTest {

    @Test
    void persistsBlocksAcrossLoadSave(@TempDir Path dir) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        store.set("overworld", 0, 64, 0, "minecraft:stone");
        store.set("overworld", 1, 64, -3, "minecraft:dirt");
        store.save(dir);

        assertTrue(Files.exists(dir.resolve("world.json")), "world.json written");

        FakeWorldStore reloaded = FakeWorldStore.load(dir);
        FakeWorldAdapter adapter = reloaded.toAdapter();
        assertEquals(
                "minecraft:stone",
                adapter.getBlock(DimensionId.OVERWORLD, 0, 64, 0).getId());
        assertEquals(
                "minecraft:dirt",
                adapter.getBlock(DimensionId.OVERWORLD, 1, 64, -3).getId());
    }

    @Test
    void overwritingACoordinateKeepsTheLastValue(@TempDir Path dir) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        store.set("overworld", 5, 70, 5, "minecraft:stone");
        store.set("overworld", 5, 70, 5, "minecraft:gold_block");
        store.save(dir);

        FakeWorldAdapter adapter = FakeWorldStore.load(dir).toAdapter();
        assertEquals(
                "minecraft:gold_block",
                adapter.getBlock(DimensionId.OVERWORLD, 5, 70, 5).getId());
    }

    @Test
    void settingAirClearsTheBlock(@TempDir Path dir) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        store.set("overworld", 2, 64, 2, "minecraft:stone");
        store.set("overworld", 2, 64, 2, "minecraft:air");
        store.save(dir);

        FakeWorldAdapter adapter = FakeWorldStore.load(dir).toAdapter();
        assertTrue(
                adapter.getBlock(DimensionId.OVERWORLD, 2, 64, 2).equals(BlockState.AIR),
                "air block reads back as AIR");
    }

    @Test
    void loadOnEmptyDirReturnsNoBlocks(@TempDir Path dir) {
        FakeWorldStore store = FakeWorldStore.load(dir);
        assertFalse(store.toAdapter().dimensions().contains(DimensionId.OVERWORLD));
    }
}
