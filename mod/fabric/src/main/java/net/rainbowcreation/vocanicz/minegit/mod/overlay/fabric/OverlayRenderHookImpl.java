package net.rainbowcreation.vocanicz.minegit.mod.overlay.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayBoxRenderer;

/**
 * Fabric implementation of the {@code OverlayRenderHook} {@code @ExpectPlatform} seam (issue #80).
 * Draws the held overlay during the translucent world-render pass via Fabric's
 * {@code WorldRenderEvents}, handing the frame's pose + buffer source to the common renderer.
 */
@Environment(EnvType.CLIENT)
public final class OverlayRenderHookImpl {

    private OverlayRenderHookImpl() {
    }

    /** Registers the Fabric world-render callback. Mirrors {@code OverlayRenderHook.register}. */
    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(
                context -> OverlayBoxRenderer.renderFrame(context.matrices(), context.consumers()));
    }
}
