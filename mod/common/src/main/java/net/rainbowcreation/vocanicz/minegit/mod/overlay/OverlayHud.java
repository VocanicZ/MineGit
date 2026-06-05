package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * <p>The text + color of each count is factored into the pure, headless-unit-tested
 * {@link #segments(OverlayState, int)} helper (Spec C §3): {@code +N} green, {@code -M} red,
 * {@code ~K} yellow, {@code (+J more)} gray. Only the pixel placement lives in the client-gated
 * {@link #render}.
 */
public final class OverlayHud {

    private static final int MARGIN = 4;
    /** Gap, in spaces, drawn between adjacent colored segments. */
    private static final String SEGMENT_GAP = " ";

    /** Opaque gray for the {@code (+J more)} cap segment (no {@link OverlayColor} for gray). */
    public static final int GRAY_ARGB = 0xFF000000 | 0x9E9E9E;

    private OverlayHud() {
    }

    /**
     * The colored HUD line for {@code state}, as an ordered list of {@link Segment}s drawn
     * left-to-right: {@code +N} (green), {@code -M} (red), {@code ~K} (yellow), and — iff
     * {@code dropped > 0} — a trailing {@code (+J more)} (gray). Pure: no client/render imports, so
     * the legend is exercised headless.
     *
     * @param state the held overlay (aggregate added/removed/changed counts)
     * @param dropped the cap's dropped count; appends {@code (+J more)} when positive
     */
    public static List<Segment> segments(OverlayState state, int dropped) {
        List<Segment> segments = new ArrayList<Segment>(4);
        segments.add(new Segment("+" + state.getAdded(), 0xFF000000 | OverlayColor.GREEN.rgb()));
        segments.add(new Segment("-" + state.getRemoved(), 0xFF000000 | OverlayColor.RED.rgb()));
        segments.add(new Segment("~" + state.getChanged(), 0xFF000000 | OverlayColor.YELLOW.rgb()));
        if (dropped > 0) {
            segments.add(new Segment("(+" + dropped + " more)", GRAY_ARGB));
        }
        return Collections.unmodifiableList(segments);
    }

    /** Renders the HUD line for the held overlay, if one is visible. */
    @Environment(EnvType.CLIENT)
    public static void render(GuiGraphics graphics, OverlayConfig config) {
        OverlayClientState holder = OverlayClientState.CLIENT;
        if (!holder.isVisible()) {
            return;
        }
        OverlayState state = holder.current();
        if (state == null) {
            return;
        }

        List<Segment> segments = segments(state, OverlayRenderStats.lastDropped());

        Font font = Minecraft.getInstance().font;
        int gapWidth = font.width(SEGMENT_GAP);
        int totalWidth = 0;
        for (int i = 0; i < segments.size(); i++) {
            totalWidth += font.width(segments.get(i).getText());
            if (i > 0) {
                totalWidth += gapWidth;
            }
        }
        int textHeight = font.lineHeight;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();

        int x;
        int y;
        switch (config.getHudCorner()) {
            case TOP_RIGHT:
                x = screenWidth - MARGIN - totalWidth;
                y = MARGIN;
                break;
            case BOTTOM_LEFT:
                x = MARGIN;
                y = screenHeight - MARGIN - textHeight;
                break;
            case BOTTOM_RIGHT:
                x = screenWidth - MARGIN - totalWidth;
                y = screenHeight - MARGIN - textHeight;
                break;
            case TOP_LEFT:
            default:
                x = MARGIN;
                y = MARGIN;
                break;
        }

        for (Segment segment : segments) {
            graphics.drawString(font, segment.getText(), x, y, segment.getArgb());
            x += font.width(segment.getText()) + gapWidth;
        }
    }

    /** One colored run of HUD text: the literal {@code text} and its opaque {@code argb} color. */
    public static final class Segment {

        private final String text;
        private final int argb;

        Segment(String text, int argb) {
            this.text = text;
            this.argb = argb;
        }

        /** The literal text to draw (e.g. {@code "+2"}, {@code "(+7 more)"}). */
        public String getText() {
            return text;
        }

        /** The opaque {@code 0xAARRGGBB} color for this run. */
        public int getArgb() {
            return argb;
        }

        @Override
        public String toString() {
            return "Segment(" + text + ", #" + Integer.toHexString(argb) + ")";
        }
    }
}
