package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The {@code +N -M ~K} diff HUD (issue #80), drawn from {@code ClientGuiEvent.RENDER_HUD}. Reads the
 * held {@link OverlayState} for the aggregate counts and {@link OverlayRenderStats} for the cap's
 * dropped count ({@code (+J more)}), and anchors the line to the configured {@link OverlayConfig#getHudCorner}
 * corner. No overlay / hidden overlay → nothing drawn.
 *
 * <p>Client-only. The text content (the counts) mirrors the unit-tested overlay core; only the pixel
 * placement lives here.
 */
@Environment(EnvType.CLIENT)
public final class OverlayHud {

    private static final int MARGIN = 4;
    private static final int TEXT_ARGB = 0xFFFFFFFF;

    private OverlayHud() {
    }

    /** Renders the HUD line for the held overlay, if one is visible. */
    public static void render(GuiGraphics graphics, OverlayConfig config) {
        OverlayClientState holder = OverlayClientState.CLIENT;
        if (!holder.isVisible()) {
            return;
        }
        OverlayState state = holder.current();
        if (state == null) {
            return;
        }

        String text = "+" + state.getAdded() + " -" + state.getRemoved() + " ~" + state.getChanged();
        int dropped = OverlayRenderStats.lastDropped();
        if (dropped > 0) {
            text = text + " (+" + dropped + " more)";
        }

        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        int textHeight = font.lineHeight;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();

        int x;
        int y;
        switch (config.getHudCorner()) {
            case TOP_RIGHT:
                x = screenWidth - MARGIN - textWidth;
                y = MARGIN;
                break;
            case BOTTOM_LEFT:
                x = MARGIN;
                y = screenHeight - MARGIN - textHeight;
                break;
            case BOTTOM_RIGHT:
                x = screenWidth - MARGIN - textWidth;
                y = screenHeight - MARGIN - textHeight;
                break;
            case TOP_LEFT:
            default:
                x = MARGIN;
                y = MARGIN;
                break;
        }

        graphics.drawString(font, text, x, y, TEXT_ARGB);
    }
}
