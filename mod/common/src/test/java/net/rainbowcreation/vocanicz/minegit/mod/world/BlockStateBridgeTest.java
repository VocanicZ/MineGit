package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for {@link BlockStateBridge}: MC {@code BlockState} ↔ core {@code BlockState}.
 * Modern-only (MC 1.21.11, already flattened) — no {@code LegacyBlockMapper}, no reflection.
 */
class BlockStateBridgeTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void plainBlockReadsIdWithNoProps() {
        net.rainbowcreation.vocanicz.minegit.core.model.BlockState core =
                BlockStateBridge.toCore(Blocks.STONE.defaultBlockState());
        assertEquals("minecraft:stone", core.getId());
        assertTrue(core.getProps().isEmpty());
    }

    @Test
    void blockWithPropertiesReadsSortedPropMap() {
        net.minecraft.world.level.block.state.BlockState mc =
                Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                        .setValue(BlockStateProperties.HALF, Half.TOP);
        net.rainbowcreation.vocanicz.minegit.core.model.BlockState core = BlockStateBridge.toCore(mc);
        assertEquals("minecraft:oak_stairs", core.getId());
        assertEquals("east", core.getProps().get("facing"));
        assertEquals("top", core.getProps().get("half"));
    }

    @Test
    void roundTripPlainBlockIsIdentity() {
        net.minecraft.world.level.block.state.BlockState mc = Blocks.STONE.defaultBlockState();
        assertEquals(mc, BlockStateBridge.toMinecraft(BlockStateBridge.toCore(mc)));
    }

    @Test
    void roundTripStairsWithPropertiesIsIdentity() {
        net.minecraft.world.level.block.state.BlockState mc =
                Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                        .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        assertEquals(mc, BlockStateBridge.toMinecraft(BlockStateBridge.toCore(mc)));
    }

    @Test
    void roundTripSlabWithTypeIsIdentity() {
        net.minecraft.world.level.block.state.BlockState mc =
                Blocks.STONE_SLAB
                        .defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        assertEquals(mc, BlockStateBridge.toMinecraft(BlockStateBridge.toCore(mc)));
    }

    @Test
    void roundTripWaterloggedBlockIsIdentity() {
        net.minecraft.world.level.block.state.BlockState mc =
                Blocks.OAK_STAIRS
                        .defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);
        assertEquals(mc, BlockStateBridge.toMinecraft(BlockStateBridge.toCore(mc)));
    }

    @Test
    void toMinecraftRejectsUnknownBlock() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        BlockStateBridge.toMinecraft(
                                new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minegit:not_a_real_block")));
    }
}
