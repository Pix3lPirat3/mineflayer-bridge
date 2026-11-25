package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldEditors;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Colour picker for colour fields in Shift+click edits: the sixteen dye colours
 * as presets, a hex field and a preview. Done or Enter hands the colour on as {@code 0xAARRGGBB}; Esc
 * closes without a change. Client thread only.
 */
public final class ColorPickerScreen extends ChildScreen {

    /** The dye colours (26.3 {@code DyeColor} texture diffuse colours), opaque. */
    private static final int[] PRESETS = {
        0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA, 0xFFFED83D, 0xFF80C71F, 0xFFF38BAA, 0xFF474F52,
        0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA, 0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21
    };
    private static final int SWATCH = 20;
    private static final int GAP = 4;
    private static final int COLUMNS = 8;

    private final IntConsumer done;
    private int argb;
    private String text;
    private Component error = CommonComponents.EMPTY;
    private boolean valid = true;
    private int gridLeft;
    private int gridTop;

    /**
     * Creates the picker.
     *
     * @param title what is being edited
     * @param initial the current colour, {@code 0xAARRGGBB}
     * @param done receives the chosen colour once, then the screen closes to the game
     */
    public ColorPickerScreen(final Component title, final int initial, final IntConsumer done) {
        super(title, null);
        this.done = done;
        this.argb = initial;
        this.text = FieldEditors.colorText(initial);
    }

    @Override
    protected void init() {
        final int gridWidth = COLUMNS * (SWATCH + GAP) - GAP;
        gridLeft = width / 2 - gridWidth / 2;
        gridTop = height / 2 - 50;
        final int y = gridTop + 2 * (SWATCH + GAP) + 8;
        final EditBox hex = new EditBox(font, gridLeft, y, gridWidth - SWATCH - GAP, 20, title);
        hex.setMaxLength(9);
        hex.setValue(text);
        hex.setResponder(v -> {
            text = v;
            try {
                argb = FieldEditors.parseColor(I18n::get, v);
                valid = true;
                error = CommonComponents.EMPTY;
            } catch (final IllegalArgumentException e) {
                valid = false;
                error = Component.literal(String.valueOf(e.getMessage()));
            }
        });
        addRenderableWidget(hex);
        setInitialFocus(hex);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 102, y + 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, y + 30, 100, 20).build());
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        for (int i = 0; i < PRESETS.length; i++) {
            final int x = gridLeft + (i % COLUMNS) * (SWATCH + GAP);
            final int y = gridTop + (i / COLUMNS) * (SWATCH + GAP);
            if (event.x() >= x && event.x() < x + SWATCH && event.y() >= y && event.y() < y + SWATCH) {
                // Presets keep the current alpha, so a translucent fill stays translucent.
                argb = (argb & 0xFF000000) == 0 ? PRESETS[i] : (argb & 0xFF000000) | (PRESETS[i] & 0xFFFFFF);
                text = FieldEditors.colorText(argb);
                valid = true;
                error = CommonComponents.EMPTY;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
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
        if (!valid) {
            return;
        }
        Game.setScreen(null);
        done.accept(argb);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, gridTop - 16, UiColors.TEXT);
        for (int i = 0; i < PRESETS.length; i++) {
            final int x = gridLeft + (i % COLUMNS) * (SWATCH + GAP);
            final int y = gridTop + (i / COLUMNS) * (SWATCH + GAP);
            graphics.fill(x - 1, y - 1, x + SWATCH + 1, y + SWATCH + 1, (argb & 0xFFFFFF) == (PRESETS[i] & 0xFFFFFF) ? UiColors.TEXT : 0xFF000000);
            graphics.fill(x, y, x + SWATCH, y + SWATCH, PRESETS[i]);
        }
        final int gridWidth = COLUMNS * (SWATCH + GAP) - GAP;
        final int py = gridTop + 2 * (SWATCH + GAP) + 8;
        final int px = gridLeft + gridWidth - SWATCH;
        graphics.fill(px - 1, py - 1, px + SWATCH + 1, py + SWATCH + 1, 0xFF000000);
        graphics.fill(px, py, px + SWATCH, py + SWATCH, argb);
        graphics.centeredText(font, error, width / 2, py + 56, UiColors.ERROR);
    }
}
