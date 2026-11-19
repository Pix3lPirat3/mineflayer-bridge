package io.github.pix3lpirat3.mfbridge.common.ui.declared;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** An item icon with a caption to its right, for the {@code item} rows of declared screens. Not interactive. */
final class ItemRowWidget extends AbstractWidget {

    private static final int ICON = 16;
    private static final int HEIGHT = 18;
    /** Left of the caption, past the icon. */
    static final int TEXT_LEFT = ICON + 4;

    private final ItemStack icon;
    private final Font font;
    private @Nullable Component cutFrom;
    private int cutWidth;
    private String caption = "";

    ItemRowWidget(final int width, final @Nullable String itemId, final Component caption, final Font font) {
        super(0, 0, width, HEIGHT, caption);
        this.icon = ItemIconButton.stack(itemId);
        this.font = font;
    }

    @Override
    protected void extractWidgetRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        if (!icon.isEmpty()) {
            graphics.item(icon, getX(), getY() + (HEIGHT - ICON) / 2);
        }
        // A caption too long is cut here; the screen puts the whole of it in the tooltip.
        final Component message = getMessage();
        if (message != cutFrom || getWidth() != cutWidth) {
            cutFrom = message;
            cutWidth = getWidth();
            caption = TextWrap.ellipsize(message.getString(), cutWidth - TEXT_LEFT, UiText.width(font));
        }
        graphics.text(font, caption, getX() + TEXT_LEFT, getY() + (HEIGHT - font.lineHeight) / 2 + 1, UiColors.TEXT, true);
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
