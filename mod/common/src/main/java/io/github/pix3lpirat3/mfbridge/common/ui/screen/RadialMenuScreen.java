package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.RadialHold;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A radial menu, for context actions and the tool wheel. Entries sit on a ring around
 * the screen centre and the one in the mouse's direction is highlighted; releasing the hold key or
 * clicking chooses it. More than 12 entries are shown as a vertical list instead.
 */
public final class RadialMenuScreen extends ChildScreen {

    private static final int MAX_RADIAL = 12;
    private static final int DEADZONE = 14;
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_ROW = 22;
    private static final int LIST_HALF_WIDTH = 160;
    // Drawn like the game's own buttons, so the menu looks like every other Bridge screen.
    private static final net.minecraft.resources.Identifier BUTTON = net.minecraft.resources.Identifier.withDefaultNamespace("widget/button");
    private static final net.minecraft.resources.Identifier BUTTON_HOVER =
            net.minecraft.resources.Identifier.withDefaultNamespace("widget/button_highlighted");

    /**
     * One menu entry.
     *
     * @param label the main text
     * @param owner the bot it belongs to, shown under the label, or empty
     * @param run what choosing it does; called after the menu closed
     * @param current whether to mark it as the current choice, for example the active tool
     */
    public record Entry(String label, String owner, Runnable run, boolean current) {
    }

    private final List<Entry> entries;
    private final String emptyText;
    private final @Nullable KeyMapping holdKey;
    /** A tap keeps the menu open for a click; a hold chooses on release. */
    private final RadialHold hold = new RadialHold(System.currentTimeMillis());
    private int hovered = -1;

    /**
     * Creates a menu.
     *
     * @param title shown in the centre
     * @param emptyText shown when there are no entries
     * @param entries the entries, grouped by owner
     * @param holdKey the key whose release chooses the hovered entry, or null
     */
    public RadialMenuScreen(final Component title, final String emptyText, final List<Entry> entries, final @Nullable KeyMapping holdKey) {
        this(title, emptyText, entries, holdKey, null);
    }

    /**
     * Creates a menu that returns to {@code parent} when closed or chosen from.
     *
     * @param title shown in the centre
     * @param emptyText shown when there are no entries
     * @param entries the entries, grouped by owner
     * @param holdKey the key whose release chooses the hovered entry, or null
     * @param parent the screen to go back to, or null for the game
     */
    public RadialMenuScreen(final Component title, final String emptyText, final List<Entry> entries, final @Nullable KeyMapping holdKey,
            final net.minecraft.client.gui.screens.@Nullable Screen parent) {
        super(title, parent);
        this.entries = List.copyOf(entries);
        this.emptyText = emptyText;
        this.holdKey = holdKey;
    }

    private boolean radial() {
        return entries.size() <= MAX_RADIAL && !overlapping();
    }

    /** Whether any two entries of the ring would cover each other, with long labels; then it is a list. */
    private boolean overlapping() {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                final int w = (entryWidth(entries.get(i)) + entryWidth(entries.get(j))) / 2;
                if (Math.abs(centerX(i) - centerX(j)) < w + 2 && Math.abs(centerY(i) - centerY(j)) < ENTRY_HEIGHT + 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private int radius() {
        return Math.clamp(30 + entries.size() * 9, 50, Math.min(110, Math.min(width, height) / 2 - 24));
    }

    /** Across, the ring widens so buttons beside the centre clear the title, within the screen. */
    private int radiusX() {
        int widest = 0;
        for (final Entry e : entries) {
            widest = Math.max(widest, entryWidth(e));
        }
        final int clear = widest / 2 + font.width(title) / 2 + 8;
        return Math.max(radius(), Math.min(clear, width / 2 - widest / 2 - 4));
    }

    private int centerX(final int i) {
        return width / 2 + (int) Math.round(radiusX() * Math.cos(angle(i)));
    }

    private int centerY(final int i) {
        return height / 2 + (int) Math.round(radius() * Math.sin(angle(i)));
    }

    private double angle(final int i) {
        return -Math.PI / 2 + 2 * Math.PI * i / entries.size();
    }

    private int entryWidth(final Entry e) {
        return Math.max(font.width(e.label()), font.width(e.owner())) + 16;
    }

    private int listTop() {
        return height / 2 - entries.size() * LIST_ROW / 2;
    }

    private int hoveredAt(final double mx, final double my) {
        if (entries.isEmpty()) {
            return -1;
        }
        if (!radial()) {
            final int index = (int) Math.floor((my - listTop()) / LIST_ROW);
            return index >= 0 && index < entries.size() && Math.abs(mx - width / 2d) < LIST_HALF_WIDTH && my >= listTop() ? index : -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            final int w = entryWidth(entries.get(i));
            if (Math.abs(mx - centerX(i)) <= w / 2d && Math.abs(my - centerY(i)) <= ENTRY_HEIGHT / 2d) {
                return i;
            }
        }
        final double dx = mx - width / 2d;
        final double dy = my - height / 2d;
        if (dx * dx + dy * dy < DEADZONE * DEADZONE) {
            return -1;
        }
        final double step = 2 * Math.PI / entries.size();
        final double a = Math.atan2(dy, dx) + Math.PI / 2;
        return Math.floorMod((int) Math.round(a / step), entries.size());
    }

    /** The hold key of a menu chosen from by clicking while it was still held; ignored until let go. */
    private static @Nullable KeyMapping heldAfterChoice;

    /**
     * Whether {@code key} is still held since its menu was chosen from with a click. The key's
     * auto-repeat would otherwise open the menu again straight away.
     *
     * @param key the menu's hold key
     * @return whether presses of the key should be ignored for now
     */
    public static boolean heldSinceChoice(final KeyMapping key) {
        if (heldAfterChoice != key) {
            return false;
        }
        if (physicallyDown(key)) {
            return true;
        }
        heldAfterChoice = null;
        return false;
    }

    /** Whether the key bound to a mapping is down right now; screens hide that from the mapping itself. */
    private static boolean physicallyDown(final KeyMapping key) {
        final InputConstants.Key bound = InputConstants.getKey(key.saveString());
        return bound.getType() == InputConstants.Type.KEYBOARD && bound.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(bound.getValue());
    }

    private void choose(final int index) {
        final KeyMapping key = holdKey;
        if (key != null && physicallyDown(key)) {
            heldAfterChoice = key;
        }
        minecraft.gui.setScreen(parent());
        if (index >= 0 && index < entries.size()) {
            entries.get(index).run().run();
        }
    }

    @Override
    public boolean keyReleased(final KeyEvent event) {
        final KeyMapping key = holdKey;
        if (key != null && key.matches(event)) {
            if (hold.released(System.currentTimeMillis()) == RadialHold.Release.CHOOSE) {
                choose(hovered);
            }
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final KeyMapping key = holdKey;
        // Tapped open, the key closes it again, choosing nothing.
        if (key != null && key.matches(event) && hold.sticky()) {
            minecraft.gui.setScreen(parent());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        choose(hoveredAt(event.x(), event.y()));
        return true;
    }


    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        hovered = hoveredAt(mouseX, mouseY);
        final int cx = width / 2;
        final int cy = height / 2;
        if (entries.isEmpty()) {
            graphics.centeredText(font, title, cx, cy - 10, UiColors.TEXT);
            graphics.centeredText(font, emptyText, cx, cy + 2, UiColors.LABEL);
            return;
        }
        if (!radial()) {
            graphics.centeredText(font, title, cx, listTop() - 14, UiColors.HEADER);
            for (int i = 0; i < entries.size(); i++) {
                final Entry e = entries.get(i);
                final int y = listTop() + i * LIST_ROW;
                button(graphics, cx - LIST_HALF_WIDTH, y, 2 * LIST_HALF_WIDTH, LIST_ROW - 2, i == hovered, e.current());
                graphics.text(font, e.label(), cx - LIST_HALF_WIDTH + 8, y + 6, e.current() ? UiColors.GOOD : UiColors.TEXT);
                if (!e.owner().isEmpty()) {
                    graphics.text(font, e.owner(), cx + LIST_HALF_WIDTH - 8 - font.width(e.owner()), y + 6, UiColors.LABEL);
                }
            }
            return;
        }
        graphics.centeredText(font, title, cx, cy - 4, UiColors.HEADER);
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            final int w = entryWidth(e);
            final int x0 = centerX(i) - w / 2;
            final int y0 = centerY(i) - ENTRY_HEIGHT / 2;
            button(graphics, x0, y0, w, ENTRY_HEIGHT, i == hovered, e.current());
            final boolean owned = !e.owner().isEmpty();
            graphics.centeredText(font, e.label(), centerX(i), y0 + (owned ? 4 : 8), e.current() ? UiColors.GOOD : UiColors.TEXT);
            if (owned) {
                graphics.centeredText(font, e.owner(), centerX(i), y0 + 14, UiColors.LABEL);
            }
        }
        if (hovered >= 0) {
            final double dx = centerX(hovered) - cx;
            final double dy = centerY(hovered) - cy;
            final double len = Math.sqrt(dx * dx + dy * dy);
            if (len > 0) {
                final int px = cx + (int) Math.round(dx / len * DEADZONE);
                final int py = cy + (int) Math.round(dy / len * DEADZONE);
                graphics.fill(px - 2, py - 2, px + 2, py + 2, UiColors.HEADER);
            }
        }
    }

    /** A vanilla button face; the current choice gets a green frame. */
    private static void button(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h, final boolean hover,
            final boolean current) {
        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, hover ? BUTTON_HOVER : BUTTON, x, y, w, h);
        if (current) {
            graphics.outline(x, y, w, h, UiColors.GOOD);
        }
    }
}
