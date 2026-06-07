package net.rainbowcreation.vocanicz.minegit.mod.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldEventBridge;

/**
 * Feeds MineGit's client live-diff engine whenever a block changes on the <em>client</em> world (SP2
 * task C2), so the overlay re-diffs only the sections that moved. Injects at the RETURN of {@code
 * LevelChunk.setBlockState(BlockPos, BlockState, int)} — the same single funnel the server-side
 * {@code LevelChunkMixin} uses — but gated on {@code ClientLevel} and funnelled to
 * {@link ClientWorldHooks#fireBlockChange}.
 *
 * <p>Identical to the Fabric copy — NeoForge cannot load mixins from {@code common}, so each platform
 * ships its own. Client-only: registered in the loader mixin JSON's {@code client} list (a mixin in
 * {@code server} is hidden in single-player / GameTest).
 */
@Mixin(LevelChunk.class)
public abstract class ClientLevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void minegit$markClientDirty(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!(level instanceof ClientLevel)) {
            return; // server / non-client level — the server mixin handles versioning
        }
        // Delegate the ClientLevel→core extraction to the MC-aware common bridge so this platform
        // mixin never names a core type (not on the loader compile classpath).
        ClientWorldEventBridge.blockChanged((ClientLevel) level, pos.getX(), pos.getY(), pos.getZ());
    }
}
