package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PanelLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Base of the screens that answer one bot prompt.
 * The screen closes itself when the prompt is answered elsewhere, times out or is cancelled by the
 * bot; Esc returns to the game and leaves the prompt card waiting. Client thread only.
 */
public abstract class PromptScreen extends ChildScreen {

    /** Vanilla's label above the player's inventory in container screens. */
    protected static final Component INVENTORY_TITLE = Component.translatable("container.inventory");

    /** The prompt this screen answers. */
    protected final PromptInput prompt;
    /** Where the answer goes. */
    protected final PromptAnswers controller;
    private final String promptLine;
    private int shownWidth = -1;
    private String shownLine = "";

    /**
     * Creates the screen.
     *
     * @param title the screen title
     * @param prompt the prompt to answer
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    protected PromptScreen(final Component title, final PromptInput prompt, final PromptAnswers controller) {
        super(title, null);
        this.prompt = prompt;
        this.controller = controller;
        this.promptLine = prompt.ownerName() + ": " + prompt.request().text();
    }

    @Override
    public void tick() {
        if (controller.active() != prompt) {
            Game.setScreen(null);
        }
    }

    /**
     * Draws "bot: prompt text" centred on a line, cut to the window with the whole text in a tooltip.
     *
     * @param y top of the line, in GUI pixels
     */
    protected final void drawPromptLine(final GuiGraphicsExtractor graphics, final int y, final int mouseX, final int mouseY) {
        final int room = width - 2 * PanelLayout.MARGIN;
        if (shownWidth != room) {
            shownWidth = room;
            shownLine = TextWrap.ellipsize(promptLine, room, UiText.width(font));
        }
        graphics.centeredText(font, shownLine, width / 2, y, UiColors.TEXT);
        if (!shownLine.equals(promptLine) && UiText.inside(mouseX, mouseY, PanelLayout.MARGIN, y, room, 9)) {
            UiText.showTooltip(graphics, font, promptLine, mouseX, mouseY);
        }
    }

    /**
     * Draws a label the way vanilla draws container titles: dark grey, without shadow.
     *
     * @param text the label
     * @param x left edge, in GUI pixels
     * @param y top edge, in GUI pixels
     */
    protected final void drawContainerLabel(final GuiGraphicsExtractor graphics, final Component text, final int x, final int y) {
        graphics.text(font, text, x, y, UiColors.CONTAINER_LABEL, false);
    }

    /**
     * Returns the prompt's {@code item} as a slot visual, for surfaces that show the item being worked on.
     *
     * @return the visual, or null when the prompt names no item
     */
    protected final @Nullable SlotVisual requestItem() {
        final String item = prompt.request().item();
        return item == null ? null : SlotVisual.of(new SlotItem(item));
    }

    /**
     * Draws the player's inventory read-only at vanilla container positions: three main rows, then the
     * hotbar 58 pixels below the first row.
     *
     * @param x left of the first main inventory slot, in GUI pixels
     * @param y top of the first main inventory row, in GUI pixels
     */
    protected final void drawInventory(final GuiGraphicsExtractor graphics, final int x, final int y) {
        final Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        if (inventory == null) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            final int sx = x + (i % 9) * 18;
            final int sy = i < 9 ? y + 58 : y + ((i - 9) / 9) * 18;
            final ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                graphics.item(stack, sx, sy);
                graphics.itemDecorations(font, stack, sx, sy);
            }
        }
    }
}
