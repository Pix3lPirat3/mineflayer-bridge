package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The {@code enchanting} surface: up to three {@code options} in
 * the slots of 26.3 {@code EnchantmentScreen}, each with its label and its cost line ({@code
 * WorldMenuEntry.text}) in green. A click answers the option id. There is no {@code EnchantmentMenu},
 * so no container button packet is built. Client thread only.
 */
public final class EnchantingPromptScreen extends MenuSurfaceScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/enchanting_table.png");
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/enchanting_table/enchantment_slot");
    private static final Identifier SLOT_HIGHLIGHTED = Identifier.withDefaultNamespace("container/enchanting_table/enchantment_slot_highlighted");
    private static final Identifier[] LEVELS = {
        Identifier.withDefaultNamespace("container/enchanting_table/level_1"),
        Identifier.withDefaultNamespace("container/enchanting_table/level_2"),
        Identifier.withDefaultNamespace("container/enchanting_table/level_3")
    };
    private static final int MAX_OPTIONS = 3;
    /** 26.3 EnchantmentScreen colours: label, highlighted label and cost. */
    private static final int LABEL = 0xFF685E4A;
    private static final int LABEL_HOVER = 0xFFFFFF80;
    private static final int COST = 0xFF80FF20;

    private final List<WorldMenuEntry> options;
    private final @Nullable SlotVisual input;

    /**
     * Creates the screen.
     *
     * @param prompt a prompt with up to three {@code options}
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public EnchantingPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(prompt, controller, "enchanting", Component.translatable("container.enchant"), BACKGROUND, 176, 166, 256, 256);
        final List<WorldMenuEntry> list = prompt.request().options();
        this.options = list == null ? List.of() : list.subList(0, Math.min(MAX_OPTIONS, list.size()));
        this.input = requestItem();
    }

    private int optionAt(final double mx, final double my) {
        for (int i = 0; i < options.size(); i++) {
            if (in(mx, my, 60, 14 + 19 * i, 108, 19)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final int index = optionAt(event.x(), event.y());
        if (index >= 0) {
            answer(options.get(index).id());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        final int hovered = optionAt(mouseX, mouseY);
        for (int i = 0; i < options.size(); i++) {
            Game.blitSprite(graphics, i == hovered ? SLOT_HIGHLIGHTED : SLOT, left + 60, top + 14 + 19 * i, 108, 19);
            Game.blitSprite(graphics, LEVELS[i], left + 61, top + 15 + 19 * i, 16, 16);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        drawContainerLabel(graphics, title, left + 12, top + 5);
        drawContainerLabel(graphics, INVENTORY_TITLE, left + 8, top + imageHeight - 94);
        final int hovered = optionAt(mouseX, mouseY);
        for (int i = 0; i < options.size(); i++) {
            final WorldMenuEntry e = options.get(i);
            final int y = top + 14 + 19 * i;
            final String label = e.label() != null ? e.label() : e.id();
            final List<FormattedCharSequence> lines = font.split(Component.literal(label), 86);
            if (!lines.isEmpty()) {
                graphics.text(font, lines.getFirst(), left + 80, y + 2, i == hovered ? LABEL_HOVER : LABEL, false);
            }
            final String cost = e.text();
            if (cost != null) {
                graphics.text(font, cost, left + 80 + 86 - font.width(cost), y + 9, COST, true);
            }
        }
        if (input != null) {
            input.draw(graphics, font, left + 15, top + 47, 0, Util.getMillis());
        }
        drawInventory(graphics, left + 8, top + 84);
        if (hovered >= 0) {
            final WorldMenuEntry e = options.get(hovered);
            final String cost = e.text();
            graphics.setTooltipForNextFrame(font, Component.literal(optionLabel(e) + (cost != null && e.label() != null ? " (" + cost + ")" : "")),
                    mouseX, mouseY);
        }
    }
}
