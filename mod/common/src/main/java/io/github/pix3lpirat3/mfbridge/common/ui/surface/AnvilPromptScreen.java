package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptScreen;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The {@code anvil} surface: an anvil-styled rename screen with the
 * vanilla anvil texture and name field (26.3 {@code AnvilScreen} geometry), showing the item the bot
 * chose. It is not {@code AnvilScreen} and has no {@code AnvilMenu}, so no rename packet is built.
 * Enter or a click on the result slot answers the text prompt; Esc returns to the prompt card.
 * Client thread only.
 */
public final class AnvilPromptScreen extends PromptScreen {

    private static final Identifier ANVIL = Identifier.withDefaultNamespace("textures/gui/container/anvil.png");
    private static final Identifier TEXT_FIELD = Identifier.withDefaultNamespace("container/anvil/text_field");
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 166;
    private static final int DEFAULT_LENGTH = 50;

    private final ItemStack input;
    private final Component anvilTitle = Component.translatable("container.repair");
    private EditBox name;
    private String value;
    private int left;
    private int top;

    /**
     * Creates the screen.
     *
     * @param prompt a text prompt
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public AnvilPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(Component.literal(prompt.request().text()), prompt, controller);
        final String item = prompt.request().item();
        this.input = SlotItems.stack(new SlotItem(item == null ? "minecraft:name_tag" : item));
        final Object def = prompt.request().defaultValue();
        this.value = def == null ? "" : def.toString();
        this.name = new EditBox(Minecraft.getInstance().font, 0, 0, 103, 12, anvilTitle);
    }

    @Override
    protected void init() {
        left = (width - IMAGE_WIDTH) / 2;
        top = (height - IMAGE_HEIGHT) / 2;
        final Integer maxLength = prompt.request().maxLength();
        name = new EditBox(font, left + 62, top + 24, 103, 12, anvilTitle);
        name.setCanLoseFocus(false);
        name.setTextColor(-1);
        name.setTextColorUneditable(-1);
        name.setInvertHighlightedTextColor(false);
        name.setBordered(false);
        name.setMaxLength(maxLength == null ? DEFAULT_LENGTH : Math.max(1, maxLength));
        name.setValue(value);
        name.setResponder(v -> value = v);
        addRenderableWidget(name);
        setInitialFocus(name);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (event.isConfirmation()) {
            submit();
            return true;
        }
        return name.keyPressed(event) || name.canConsumeInput() || super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final double x = event.x() - left;
        final double y = event.y() - top;
        if (x >= 133 && x < 151 && y >= 46 && y < 64) {
            submit();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void submit() {
        minecraft.gui.setScreen(null);
        controller.answer(value);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ANVIL, left, top, 0f, 0f, IMAGE_WIDTH, IMAGE_HEIGHT, 256, 256);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TEXT_FIELD, left + 59, top + 20, 110, 16);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        drawContainerLabel(graphics, anvilTitle, left + 60, top + 6);
        drawContainerLabel(graphics, INVENTORY_TITLE, left + 8, top + IMAGE_HEIGHT - 94);
        graphics.item(input, left + 27, top + 47);
        final ItemStack result = input.copy();
        if (!value.isBlank()) {
            result.set(DataComponents.CUSTOM_NAME, Component.literal(value));
        }
        graphics.item(result, left + 134, top + 47);
        drawInventory(graphics, left + 8, top + 84);
        drawPromptLine(graphics, top - 12, mouseX, mouseY);
        final double rx = mouseX - left;
        final double ry = mouseY - top;
        if (rx >= 133 && rx < 151 && ry >= 46 && ry < 64) {
            graphics.setTooltipForNextFrame(font, result, mouseX, mouseY);
        } else if (rx >= 26 && rx < 44 && ry >= 46 && ry < 64) {
            graphics.setTooltipForNextFrame(font, input, mouseX, mouseY);
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }
}
