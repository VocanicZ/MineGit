package net.rainbowcreation.vocanicz.minegit.mod.mixin;

import net.rainbowcreation.vocanicz.minegit.mod.world.ServerLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a chunk dirty in MineGit's {@link DirtyTracking} bridge whenever a block changes on a
 * server level, so {@code /mg commit}/{@code status}/{@code diff} can read only the chunks that moved
 * (Spec E task 4). Injects at the RETURN of {@code LevelChunk.setBlockState(BlockPos, BlockState,
 * int)} — the single funnel every server-side block change passes through (the verified 1.21.11
 * Mojang-mapped signature returns {@link BlockState}; there is no boolean/4th-arg overload).
 *
 * <p>Client and non-{@link ServerLevel} chunks are ignored: MineGit only versions the server world.
 * The {@code ServerLevel}→(levelKey, DimensionId) extraction is delegated to
 * {@link ServerLevelAccess#markDirty} (the MC-aware common side) so this platform mixin never touches
 * the core {@code DimensionId} type, which is not on the platform compile classpath.
 *
 * <p>Identical to the Fabric copy — NeoForge cannot load mixins from the {@code common} subproject,
 * so each platform ships its own.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void minegit$markDirty(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!(level instanceof ServerLevel)) {
            return; // client / non-server level — MineGit only versions the server world
        }
        ServerLevelAccess.markDirty((ServerLevel) level, pos.getX(), pos.getZ());
    }
}
