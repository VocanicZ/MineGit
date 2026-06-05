package net.rainbowcreation.vocanicz.minegit.mod.overlay.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayBoxRenderer;

/**
 * NeoForge implementation of the {@code OverlayRenderHook} {@code @ExpectPlatform} seam (issue #80).
 * Draws the held overlay during {@code RenderLevelStageEvent.AfterTranslucentBlocks} on the game bus,
 * handing the frame's pose to the common renderer and flushing the line buffer it filled.
 */
public final class OverlayRenderHookImpl {

    private OverlayRenderHookImpl() {
    }

    /** Registers the NeoForge world-render listener. Mirrors {@code OverlayRenderHook.register}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(
                RenderLevelStageEvent.AfterTranslucentBlocks.class, OverlayRenderHookImpl::onRender);
    }

    private static void onRender(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        OverlayBoxRenderer.renderFrame(event.getPoseStack(), buffers);
        buffers.endBatch();
    }
}
