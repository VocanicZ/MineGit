package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/**
 * The thin, GPU-touching reader over {@link OverlayState} (issue #80). It contains <b>no</b> overlay
 * logic — the should-render gate, cull, cap and order are all {@link OverlayClientState} /
 * {@link OverlayState#visibleBoxes} (unit-tested in #79/#80) — it only turns the kept boxes into
 * immediate-mode line cubes via vanilla's {@link ShapeRenderer}.
 *
 * <p>{@link #renderFrame} is the single entry the loader-specific {@code @ExpectPlatform} hooks call
 * with the frame's pose + buffer source; everything else (held state, active dimension, camera,
 * config) is read here so the loader code names no core types.
 *
 * <p>Client-only. Outlined translucent cubes (line render type) are the immediate-mode first cut; a
 * baked/GPU-buffered filled renderer is an explicit Spec C non-goal for this batch.
 */
@Environment(EnvType.CLIENT)
public final class OverlayBoxRenderer {

    /** Single block-sized cube reused for every box; offset is applied per draw. */
    private static final VoxelShape UNIT_BLOCK = Shapes.block();

    /** Opaque alpha for the box outline (0xAA channel of the ARGB color). */
    private static final int OUTLINE_ALPHA = 0xFF << 24;

    private OverlayBoxRenderer() {
    }

    /**
     * Draws the held overlay for this frame, or nothing if the holder says it should not render
     * (no overlay / hidden / expired / dimension mismatch). Publishes the cap's dropped count to
     * {@link OverlayRenderStats} for the HUD. Called from each loader's world-render hook with the
     * frame's camera-relative pose and buffer source.
     *
     * @param pose the frame's world-render pose stack (origin at the camera)
     * @param buffers the frame's buffer source for the line render type
     */
    public static void renderFrame(PoseStack pose, MultiBufferSource buffers) {
        OverlayClientState holder = OverlayClientState.CLIENT;
        long now = OverlayClientHooks.clientTick();
        OverlayConfig config = OverlayClientHooks.config();
        if (!holder.shouldRender(now, config.lifetimeTicks())) {
            OverlayRenderStats.setLastDropped(0);
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        draw(pose, buffers, camera, holder.current(), holder.activeDimension(), config);
    }

    /**
     * Emits the line cubes for {@code dim}'s visible boxes (distance-culled, nearest-first, capped),
     * recording the cap's dropped count. Coordinates are camera-relative.
     */
    static void draw(
            PoseStack pose,
            MultiBufferSource buffers,
            Vec3 camera,
            OverlayState state,
            DimensionId dim,
            OverlayConfig config) {
        OverlayState.VisibleBoxes visible = state.visibleBoxes(
                dim, camera.x, camera.y, camera.z, config.getMaxRenderDistance(), config.getRenderCap());
        OverlayRenderStats.setLastDropped(visible.getDropped());

        List<OverlayBox> boxes = visible.getBoxes();
        if (boxes.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderTypes.lines());
        for (OverlayBox box : boxes) {
            int color = OUTLINE_ALPHA | (box.getColor().rgb() & 0x00FFFFFF);
            ShapeRenderer.renderShape(
                    pose,
                    consumer,
                    UNIT_BLOCK,
                    box.getX() - camera.x,
                    box.getY() - camera.y,
                    box.getZ() - camera.z,
                    color,
                    1.0f);
        }
    }
}
