package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import dev.architectury.injectables.annotations.ExpectPlatform;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The world-render seam (Spec C §2.3, issue #80). There is no Architectury-unified "draw after
 * translucent" event, so each loader registers its own — Fabric {@code WorldRenderEvents}, NeoForge
 * {@code RenderLevelStageEvent.AfterTranslucentBlocks} — and funnels the frame's pose + buffers +
 * camera into the loader-agnostic {@link OverlayBoxRenderer}. {@code @ExpectPlatform} stitches in the
 * per-loader {@code OverlayRenderHookImpl} at build time.
 *
 * <p>Client-only; called once from each loader's client init.
 */
@Environment(EnvType.CLIENT)
public final class OverlayRenderHook {

    private OverlayRenderHook() {
    }

    /** Registers the loader's world-render callback that draws the held overlay each frame. */
    @ExpectPlatform
    public static void register() {
        throw new AssertionError("@ExpectPlatform stub — replaced by OverlayRenderHookImpl at build time");
    }
}
