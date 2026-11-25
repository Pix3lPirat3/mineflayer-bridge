package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptScreen;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Base of the menu-styled prompt surfaces (stonecutter,
 * enchanting, merchant, crafting): a vanilla container texture, its title, the prompt text above it
 * and the player's inventory drawn read-only. These are plain screens with no vanilla menu behind
 * them, so none of the menu packets (select trade, container button, place recipe, container click)
 * can be built; the answer goes to the bot as {@code prompt.result}. Esc returns to the prompt card,
 * where Enter reopens the surface. Client thread only.
 */
abstract class MenuSurfaceScreen extends PromptScreen {

    private final String surface;
    private final Identifier texture;
    protected final int imageWidth;
    protected final int imageHeight;
    private final int textureWidth;
    private final int textureHeight;
    protected int left;
    protected int top;

    /**
     * Creates a surface screen.
     *
     * @param surface the wire name reported in {@code prompt.result.surface}
     * @param title the vanilla container title
     */
    MenuSurfaceScreen(final PromptInput prompt, final PromptAnswers controller, final String surface, final Component title,
            final Identifier texture, final int imageWidth, final int imageHeight, final int textureWidth, final int textureHeight) {
        super(title, prompt, controller);
        this.surface = surface;
        this.texture = texture;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    @Override
    protected void init() {
        left = (width - imageWidth) / 2;
        top = (height - imageHeight) / 2;
        initSurface();
    }

    /** Adds widgets once {@link #left} and {@link #top} are known. */
    protected void initSurface() {
    }

    /**
     * Answers the prompt from this surface and closes the screen.
     *
     * @param value the answer, or null for none
     */
    protected final void answer(final @Nullable Object value) {
        Game.setScreen(null);
        controller.answer(value, surface);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        Game.blit(graphics, texture, left, top, 0f, 0f, imageWidth, imageHeight, textureWidth, textureHeight);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        drawPromptLine(graphics, top - 12, mouseX, mouseY);
    }

    /**
     * Returns whether a point is inside a box relative to the background.
     *
     * @param mx point x on screen
     * @param my point y on screen
     */
    protected final boolean in(final double mx, final double my, final int x, final int y, final int w, final int h) {
        final double rx = mx - left;
        final double ry = my - top;
        return rx >= x && rx < x + w && ry >= y && ry < y + h;
    }

    /**
     * Returns the item an option shows: its item, else its block, else the spawn egg of its entity,
     * else a paper named after the option.
     *
     * @param e the option
     * @return the option's visual
     */
    protected static SlotVisual optionVisual(final WorldMenuEntry e) {
        final SlotItem item = e.item();
        if (item != null) {
            return SlotVisual.of(item);
        }
        if (e.block() != null) {
            return SlotVisual.of(new SlotItem(blockItem(e.block())));
        }
        if (e.entity() != null) {
            return SlotVisual.of(new SlotItem(e.entity().toLowerCase(Locale.ROOT) + "_spawn_egg"));
        }
        return SlotVisual.of(new SlotItem("minecraft:paper", 1, optionLabel(e), null, false, null, null, null, null, null, null, null,
                null, 500, false, null, null, null, null));
    }

    /** A block state such as {@code minecraft:chest[facing=north]} shows as the item of its block. */
    private static String blockItem(final String state) {
        final int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /**
     * Returns the text an option is known by.
     *
     * @param e the option
     * @return its label, else its text, else its id
     */
    protected static String optionLabel(final WorldMenuEntry e) {
        if (e.label() != null) {
            return e.label();
        }
        return e.text() != null ? e.text() : e.id();
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
