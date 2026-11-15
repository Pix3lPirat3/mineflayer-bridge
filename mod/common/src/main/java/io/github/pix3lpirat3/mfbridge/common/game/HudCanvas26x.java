package io.github.pix3lpirat3.mfbridge.common.game;

import io.github.pix3lpirat3.mfbridge.common.seam.HudCanvas;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/** {@link HudCanvas} over the 26.x GUI extractor. One instance is reused for every frame. */
final class HudCanvas26x implements HudCanvas {

    private final Font font;
    private @Nullable GuiGraphicsExtractor graphics;

    HudCanvas26x(final Font font) {
        this.font = font;
    }

    void begin(final GuiGraphicsExtractor newGraphics) {
        this.graphics = newGraphics;
    }

    void end() {
        this.graphics = null;
    }

    private GuiGraphicsExtractor graphics() {
        return Objects.requireNonNull(graphics, "HUD canvas used outside its render callback");
    }

    @Override
    public int width() {
        return graphics().guiWidth();
    }

    @Override
    public int height() {
        return graphics().guiHeight();
    }

    @Override
    public void fill(final int x0, final int y0, final int x1, final int y1, final int rgba) {
        graphics().fill(x0, y0, x1, y1, Colors.rgbaToArgb(rgba));
    }

    @Override
    public void text(final String text, final int x, final int y, final int rgba) {
        graphics().text(font, text, x, y, Colors.rgbaToArgb(rgba), true);
    }

    @Override
    public int textWidth(final String text) {
        return font.width(text);
    }
}
