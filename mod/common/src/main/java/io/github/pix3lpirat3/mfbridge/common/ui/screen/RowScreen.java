package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Base of the form-like bridge screens (settings, devices): a centred title, a scrolling column of
 * rows built from vanilla widgets and text labels, a status line and a fixed footer. Subclasses lay
 * out rows in {@link #rows} and the footer in {@link #footer}; {@link #rebuildWidgets()} lays
 * everything out again, keeping the scroll offset. Like vanilla option screens they pause a single
 * player game.
 */
public abstract class RowScreen extends ChildScreen {
    /** Default width of the content column, in GUI pixels. */
    protected static final int DEFAULT_COLUMN_WIDTH = 300;
    /** Height of one widget row, in GUI pixels. */
    protected static final int ROW_HEIGHT = 20;
    /** Vertical step between rows, in GUI pixels. */
    protected static final int ROW_STEP = 24;
    private static final int TOP = 32;

    private record Label(Component text, int x, int y, int argb) {
    }

    /** Width of the content column, in GUI pixels. */
    protected final int columnWidth;
    private final List<Label> labels = new ArrayList<>();
    private Component status = CommonComponents.EMPTY;
    private int statusArgb = UiColors.ERROR;
    private int scroll;
    private int contentHeight;

    /**
     * Creates the screen with the default column width.
     *
     * @param title the screen title
     * @param parent the screen to return to, or null to close to the game
     */
    protected RowScreen(final Component title, final @Nullable Screen parent) {
        this(title, parent, DEFAULT_COLUMN_WIDTH);
    }

    /**
     * Creates the screen.
     *
     * @param title the screen title
     * @param parent the screen to return to, or null to close to the game
     * @param columnWidth width of the content column, in GUI pixels
     */
    protected RowScreen(final Component title, final @Nullable Screen parent, final int columnWidth) {
        super(title, parent);
        this.columnWidth = columnWidth;
    }

    /**
     * Adds the scrolling rows.
     *
     * @param left x of the column
     * @param top y of the first row, already shifted by the scroll offset
     * @return y below the last row
     */
    protected abstract int rows(int left, int top);

    /**
     * Adds the footer buttons.
     *
     * @param y y of the footer row
     */
    protected abstract void footer(int y);

    /**
     * Returns the bottom of the scrolling area.
     *
     * @return y in GUI pixels
     */
    protected final int bottom() {
        return height - 40;
    }

    /**
     * Returns whether a row is inside the scrolling area; rows outside it add no widgets.
     *
     * @param y top of the row
     * @return true when the whole row is visible
     */
    protected final boolean visible(final int y) {
        return y >= TOP - 2 && y + ROW_HEIGHT <= bottom();
    }

    /**
     * Draws a line of text at a position for this layout pass.
     *
     * @param text the text
     * @param x left edge
     * @param y top edge
     * @param argb colour, usually one of {@link UiColors}
     */
    protected final void label(final Component text, final int x, final int y, final int argb) {
        labels.add(new Label(text, x, y, argb));
    }

    /**
     * Shows a message above the footer until the next one.
     *
     * @param text the message
     * @param argb colour, usually one of {@link UiColors}
     */
    protected final void status(final Component text, final int argb) {
        status = text;
        statusArgb = argb;
    }

    @Override
    protected void init() {
        labels.clear();
        final int left = width / 2 - columnWidth / 2;
        final int end = rows(left, TOP - scroll);
        contentHeight = end + scroll - TOP;
        footer(height - 28);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        final int max = Math.max(0, contentHeight - (bottom() - TOP));
        if (max == 0) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        final int next = Math.clamp(scroll - (int) Math.signum(scrollY) * ROW_STEP, 0, max);
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 12, UiColors.TEXT);
        for (final Label label : labels) {
            graphics.text(font, label.text(), label.x(), label.y(), label.argb());
        }
        graphics.centeredText(font, status, width / 2, height - 40, statusArgb);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
