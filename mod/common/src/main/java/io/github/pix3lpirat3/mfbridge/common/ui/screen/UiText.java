package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.PanelLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Game-side helpers for bot text that has to fit: the font as a width function for {@link TextWrap},
 * wrapped tooltips for text that was cut, and a scrollbar drawn in the vanilla tones. Client thread only.
 */
public final class UiText {

    /** Widest a tooltip line gets, in GUI pixels. */
    public static final int TOOLTIP_WIDTH = 200;
    private static final int TRACK_ARGB = 0x80000000;
    private static final int THUMB_ARGB = 0xFFC0C0C0;
    private static final int THUMB_SHADE_ARGB = 0xFF808080;

    private UiText() {
    }

    /**
     * Returns the font as a width function.
     *
     * @param font the game font
     * @return measures a string in GUI pixels
     */
    public static ToIntFunction<String> width(final Font font) {
        return font::width;
    }

    /**
     * Returns a widget tooltip for text, or for text followed by more.
     *
     * @param text the full text
     * @param more a tooltip of the widget's own to show under it, or null
     * @return the tooltip, wrapped by vanilla
     */
    public static Tooltip tooltip(final String text, final @Nullable String more) {
        return Tooltip.create(Component.literal(more == null || more.isEmpty() ? text : text + "\n\n" + more));
    }

    /**
     * Shows a wrapped tooltip at the mouse this frame.
     *
     * @param graphics the frame's GUI extractor
     * @param font the game font
     * @param text the text
     */
    public static void showTooltip(final GuiGraphicsExtractor graphics, final Font font, final String text, final int mouseX, final int mouseY) {
        final List<FormattedCharSequence> lines = font.split(Component.literal(text), Math.min(TOOLTIP_WIDTH, Math.max(60, graphics.guiWidth() / 2)));
        graphics.setTooltipForNextFrame(lines, mouseX, mouseY);
    }

    /**
     * Draws one line of text cut to a width, with the whole text in a tooltip while the mouse is on it.
     *
     * @param graphics the frame's GUI extractor
     * @param font the game font
     * @param text the text
     * @param x left edge, or the centre when {@code centred}
     * @param y top edge
     * @param maxWidth the widest the line may be
     * @param argb the colour
     * @param shadow whether to draw a shadow
     * @param centred whether {@code x} is the centre
     */
    public static void line(final GuiGraphicsExtractor graphics, final Font font, final String text, final int x, final int y, final int maxWidth,
            final int argb, final boolean shadow, final boolean centred, final int mouseX, final int mouseY) {
        final boolean cut = TextWrap.overflows(text, maxWidth, font::width);
        final String shown = cut ? TextWrap.ellipsize(text, maxWidth, font::width) : text;
        final int w = font.width(shown);
        final int left = centred ? x - w / 2 : x;
        graphics.text(font, shown, left, y, argb, shadow);
        if (cut && inside(mouseX, mouseY, left, y, Math.max(w, 1), 9)) {
            showTooltip(graphics, font, text, mouseX, mouseY);
        }
    }

    /**
     * Draws a scrollbar: a dark track and a light thumb.
     *
     * @param graphics the frame's GUI extractor
     * @param x left of the bar
     * @param top top of the track
     * @param scroll the column's scrolling
     * @param offset the scroll offset
     */
    public static void scrollbar(final GuiGraphicsExtractor graphics, final int x, final int top, final PanelLayout.Scroll scroll, final int offset) {
        if (!scroll.scrolls()) {
            return;
        }
        final int[] thumb = scroll.thumb(offset);
        final int w = PanelLayout.SCROLLBAR_WIDTH;
        graphics.fill(x, top, x + w, top + scroll.viewport(), TRACK_ARGB);
        graphics.fill(x, top + thumb[0], x + w, top + thumb[0] + thumb[1], THUMB_SHADE_ARGB);
        graphics.fill(x, top + thumb[0], x + w - 1, top + thumb[0] + thumb[1] - 1, THUMB_ARGB);
    }

    /**
     * Returns whether a point is inside a rectangle.
     *
     * @return true when {@code x, y} is in {@code [left, left + w) x [top, top + h)}
     */
    public static boolean inside(final double x, final double y, final int left, final int top, final int w, final int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }
}
