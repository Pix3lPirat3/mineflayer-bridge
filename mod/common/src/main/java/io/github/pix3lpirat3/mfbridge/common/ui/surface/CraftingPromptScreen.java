package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.SlotGestures;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The {@code crafting} surface: a crafting-table-styled 3x3 grid
 * the player fills with virtual items. The palette is the prompt's {@code options} items, shown where
 * the inventory would be; without a palette the item types in the player's inventory serve as
 * templates. Nothing moves in the real inventory. A click on a palette item picks it up, a left click
 * or drag on the grid paints it, a right click clears a cell. A click on the result slot or Enter
 * answers the pattern as nine item ids or nulls, row by row, or null for an empty grid. There is no
 * {@code CraftingMenu}, so no place-recipe or container packet is built. Client thread only.
 */
public final class CraftingPromptScreen extends MenuSurfaceScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");
    private static final int GRID = 9;
    private static final int PALETTE = 36;

    private final List<SlotVisual> palette = new ArrayList<>();
    private final @Nullable SlotVisual[] cells = new SlotVisual[GRID];
    private final @Nullable SlotVisual result;
    private @Nullable SlotVisual cursor;
    private boolean painting;

    /**
     * Creates the screen.
     *
     * @param prompt a crafting prompt; {@code options} with items form the palette
     * @param controller where the answer goes: the prompt controller or a local editor
     * @param inventory the player's inventory, whose item types are the palette when the prompt has none
     */
    public CraftingPromptScreen(final PromptInput prompt, final PromptAnswers controller, final @Nullable Inventory inventory) {
        super(prompt, controller, "crafting", Component.translatable("container.crafting"), BACKGROUND, 176, 166, 256, 256);
        final List<WorldMenuEntry> options = prompt.request().options();
        if (options != null) {
            for (final WorldMenuEntry e : options) {
                final SlotItem item = e.item();
                if (item != null && palette.size() < PALETTE) {
                    palette.add(SlotVisual.of(item));
                }
            }
        }
        if (palette.isEmpty() && inventory != null) {
            final Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < PALETTE; i++) {
                final ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    ids.add(SlotItems.id(stack));
                }
            }
            for (final String id : ids) {
                palette.add(SlotVisual.of(new SlotItem(id)));
            }
        }
        final String item = prompt.request().item();
        this.result = item == null ? null : SlotVisual.of(new SlotItem(item));
    }

    private int cellAt(final double mx, final double my) {
        for (int i = 0; i < GRID; i++) {
            if (in(mx, my, 29 + (i % 3) * 18, 16 + (i / 3) * 18, 18, 18)) {
                return i;
            }
        }
        return -1;
    }

    /** Palette slots sit where vanilla puts the inventory: three rows, then the hotbar row. */
    private int paletteAt(final double mx, final double my) {
        for (int i = 0; i < palette.size(); i++) {
            if (in(mx, my, paletteX(i) - 1, paletteY(i) - 1, 18, 18)) {
                return i;
            }
        }
        return -1;
    }

    private static int paletteX(final int i) {
        return 8 + (i % 9) * 18;
    }

    private static int paletteY(final int i) {
        return i < 27 ? 84 + (i / 9) * 18 : 142;
    }

    private @Nullable List<@Nullable String> pattern() {
        final String[] ids = new String[GRID];
        boolean any = false;
        for (int i = 0; i < GRID; i++) {
            final SlotVisual v = cells[i];
            if (v != null) {
                ids[i] = v.item().id();
                any = true;
            }
        }
        return any ? Collections.unmodifiableList(Arrays.asList(ids)) : null;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final int p = paletteAt(event.x(), event.y());
        if (p >= 0) {
            cursor = palette.get(p);
            return true;
        }
        final int cell = cellAt(event.x(), event.y());
        if (cell >= 0) {
            if (event.button() == SlotGestures.BUTTON_RIGHT) {
                cells[cell] = null;
            } else if (cursor != null) {
                cells[cell] = cursor;
                painting = true;
            } else {
                cursor = cells[cell];
                cells[cell] = null;
            }
            return true;
        }
        if (in(event.x(), event.y(), 119, 30, 26, 26)) {
            answer(pattern());
            return true;
        }
        if (event.button() == SlotGestures.BUTTON_RIGHT) {
            cursor = null;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (painting && cursor != null) {
            final int cell = cellAt(event.x(), event.y());
            if (cell >= 0) {
                cells[cell] = cursor;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        painting = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isConfirmation()) {
            answer(pattern());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final long now = Util.getMillis();
        drawContainerLabel(graphics, title, left + 29, top + 6);
        for (int i = 0; i < GRID; i++) {
            final SlotVisual v = cells[i];
            if (v != null) {
                v.draw(graphics, font, left + 30 + (i % 3) * 18, top + 17 + (i / 3) * 18, i, now);
            }
        }
        if (result != null) {
            result.draw(graphics, font, left + 124, top + 35, 0, now);
        }
        for (int i = 0; i < palette.size(); i++) {
            palette.get(i).draw(graphics, font, left + paletteX(i), top + paletteY(i), GRID + i, now);
        }
        final SlotVisual held = cursor;
        if (held != null) {
            held.draw(graphics, font, mouseX - 8, mouseY - 8, 0, now);
            return;
        }
        final int p = paletteAt(mouseX, mouseY);
        final int cell = cellAt(mouseX, mouseY);
        if (p >= 0) {
            palette.get(p).tooltip(graphics, font, mouseX, mouseY, now);
        } else if (cell >= 0 && cells[cell] != null) {
            cells[cell].tooltip(graphics, font, mouseX, mouseY, now);
        } else if (result != null && in(mouseX, mouseY, 119, 30, 26, 26)) {
            result.tooltip(graphics, font, mouseX, mouseY, now);
        }
    }
}
