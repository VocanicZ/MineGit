package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import dev.architectury.injectables.annotations.ExpectPlatform;

import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/**
 * Read-only {@link LevelAccess} over the current client world, or {@code null} if there is none
 * (SP2 task C1). The live-diff engine reads the player's world through this seam to compare the
 * frozen HEAD baseline against what the client currently has loaded; writes are unsupported
 * (overlay is purely a visualization). {@code @ExpectPlatform} stitches in the per-loader
 * {@code ClientLevelAccessImpl} at build time, since {@code ClientLevel} is loader-side only.
 *
 * <p>Client-only; the supplier {@link OverlayClientState#setLevelSupplier} holds is
 * {@code ClientLevelAccess::current}.
 */
public final class ClientLevelAccess {

    private ClientLevelAccess() {
    }

    /** A {@link LevelAccess} over {@code Minecraft.getInstance().level}, or {@code null} if none. */
    @ExpectPlatform
    public static LevelAccess current() {
        throw new AssertionError("@ExpectPlatform stub — replaced by ClientLevelAccessImpl at build time");
    }
}
