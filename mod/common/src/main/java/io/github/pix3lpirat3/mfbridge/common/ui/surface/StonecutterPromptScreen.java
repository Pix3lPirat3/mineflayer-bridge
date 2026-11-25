package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The {@code stonecutter} surface: the prompt's {@code options} in
 * the scrollable four-column grid of 26.3 {@code StonecutterScreen}. A click selects an option and a
 * click on the selected one, the result slot or Enter answers its id. There is no {@code
 * StonecutterMenu}, so no container button packet is built. Client thread only.
 */
public final class StonecutterPromptScreen extends MenuSurfaceScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/stonecutter.png");
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/stonecutter/scroller");
    private static final Identifier SCROLLER_DISABLED = Identifier.withDefaultNamespace("container/stonecutter/scroller_disabled");
    private static final Identifier RECIPE = Identifier.withDefaultNamespace("container/stonecutter/recipe");
    private static final Identifier RECIPE_SELECTED = Identifier.withDefaultNamespace("container/stonecutter/recipe_selected");
    private static final Identifier RECIPE_HIGHLIGHTED = Identifier.withDefaultNamespace("container/stonecutter/recipe_highlighted");
    private static final int COLUMNS = 4;
    private static final int ROWS = 3;

    private final List<WorldMenuEntry> options;
    private final List<SlotVisual> visuals = new ArrayList<>();
    private final @Nullable SlotVisual input;
    private int selected = -1;
    private int startRow;

    /**
     * Creates the screen.
     *
     * @param prompt a prompt with {@code options}
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public StonecutterPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(prompt, controller, "stonecutter", Component.translatable("container.stonecutter"), BACKGROUND, 176, 166, 256, 256);
        final List<WorldMenuEntry> list = prompt.request().options();
        this.options = list == null ? List.of() : list;
        for (final WorldMenuEntry e : options) {
            visuals.add(optionVisual(e));
        }
        this.input = requestItem();
        final Object def = prompt.request().defaultValue();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(def)) {
                selected = i;
            }
        }
    }

    private int maxStartRow() {
        return Math.max(0, (options.size() + COLUMNS - 1) / COLUMNS - ROWS);
    }

    private int optionAt(final double mx, final double my) {
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            final int index = startRow * COLUMNS + i;
            if (index >= options.size()) {
                break;
            }
            if (in(mx, my, 52 + (i % COLUMNS) * 16, 14 + (i / COLUMNS) * 18 + 1, 16, 18)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final int index = optionAt(event.x(), event.y());
        if (index >= 0) {
            if (index == selected || doubleClick) {
                answer(options.get(index).id());
            } else {
                selected = index;
            }
            return true;
        }
        if (selected >= 0 && in(event.x(), event.y(), 142, 32, 18, 18)) {
            answer(options.get(selected).id());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        startRow = Mth.clamp(startRow - (int) Math.signum(scrollY), 0, maxStartRow());
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isConfirmation() && selected >= 0) {
            answer(options.get(selected).id());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        final int max = maxStartRow();
        final int offset = max == 0 ? 0 : (int) (41f * startRow / max);
        Game.blitSprite(graphics, max == 0 ? SCROLLER_DISABLED : SCROLLER, left + 119, top + 15 + offset, 12, 15);
        final int hovered = optionAt(mouseX, mouseY);
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            final int index = startRow * COLUMNS + i;
            if (index >= options.size()) {
                break;
            }
            final Identifier sprite = index == selected ? RECIPE_SELECTED : index == hovered ? RECIPE_HIGHLIGHTED : RECIPE;
            Game.blitSprite(graphics, sprite, left + 52 + (i % COLUMNS) * 16, top + 14 + (i / COLUMNS) * 18 + 1, 16,
                    18);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final long now = Util.getMillis();
        drawContainerLabel(graphics, title, left + 8, top + 4);
        drawContainerLabel(graphics, INVENTORY_TITLE, left + 8, top + imageHeight - 94);
        for (int i = 0; i < COLUMNS * ROWS; i++) {
            final int index = startRow * COLUMNS + i;
            if (index >= options.size()) {
                break;
            }
            visuals.get(index).draw(graphics, font, left + 52 + (i % COLUMNS) * 16, top + 14 + (i / COLUMNS) * 18 + 2, index, now);
        }
        if (input != null) {
            input.draw(graphics, font, left + 20, top + 33, 0, now);
        }
        if (selected >= 0) {
            visuals.get(selected).draw(graphics, font, left + 143, top + 33, 0, now);
        }
        drawInventory(graphics, left + 8, top + 84);
        final int hovered = optionAt(mouseX, mouseY);
        if (hovered >= 0) {
            final WorldMenuEntry e = options.get(hovered);
            if (e.item() != null) {
                visuals.get(hovered).tooltip(graphics, font, mouseX, mouseY, now);
            } else {
                graphics.setTooltipForNextFrame(font, Component.literal(optionLabel(e)), mouseX, mouseY);
            }
        }
    }
}
