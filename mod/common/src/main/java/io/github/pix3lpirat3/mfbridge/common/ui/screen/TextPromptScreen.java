package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Input screen for text and number prompts. Enter submits; Esc returns to
 * the game and leaves the prompt card waiting.
 */
public final class TextPromptScreen extends PromptScreen {

    private static final int WIDTH = 220;

    private String value;
    private Component error = CommonComponents.EMPTY;
    private List<FormattedCharSequence> lines = List.of();

    /**
     * Creates the screen.
     *
     * @param prompt the text or number prompt
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public TextPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(Component.literal(prompt.ownerName()), prompt, controller);
        final Object def = prompt.request().defaultValue();
        this.value = def == null ? "" : def instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                ? Long.toString(n.longValue()) : def.toString();
    }

    @Override
    protected void init() {
        lines = font.split(Component.literal(prompt.request().text()), WIDTH);
        final int left = width / 2 - WIDTH / 2;
        final int y = height / 2 - 10;
        final EditBox box = new EditBox(font, left, y, WIDTH, 20, Component.literal(prompt.request().text()));
        box.setMaxLength(1024);
        box.setValue(value);
        box.setResponder(v -> value = v);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 102, y + 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 2, y + 30, 100, 20).build());
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isConfirmation()) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        if (prompt.request().kind() == PromptKind.NUMBER) {
            final double number;
            try {
                number = Double.parseDouble(value.strip());
            } catch (final NumberFormatException e) {
                error = Component.translatable("screen.mineflayer_bridge.prompt.enter_number");
                return;
            }
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                error = Component.translatable("screen.mineflayer_bridge.prompt.enter_number");
                return;
            }
            controller.answer(number == Math.rint(number) && Math.abs(number) < 1e15 ? (Object) (long) number : (Object) number);
        } else if (prompt.request().kind() == PromptKind.LINES || prompt.request().kind() == PromptKind.PAGES) {
            controller.answer(List.of(value));
        } else {
            controller.answer(value);
        }
        minecraft.gui.setScreen(null);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, height / 2 - 30 - lines.size() * 10, UiColors.TEXT);
        int y = height / 2 - 24 - lines.size() * 10;
        for (final FormattedCharSequence line : lines) {
            graphics.centeredText(font, line, width / 2, y, UiColors.TEXT);
            y += 10;
        }
        graphics.centeredText(font, error, width / 2, height / 2 + 56, UiColors.ERROR);
        final long seconds = (controller.remainingMs() + 999L) / 1000L;
        graphics.centeredText(font, Component.translatable("screen.mineflayer_bridge.prompt.time_left", seconds), width / 2, height / 2 + 68, UiColors.LABEL);
    }
}
