package io.github.pix3lpirat3.mfbridge.common.ui.home;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The "Bridge" icon button on the title screen and the pause menu, so Bridge home is
 * reachable everywhere. Both screens in 26.3 have a row of 20 by 20 icon buttons (friends, language and
 * accessibility on the title screen; bug report, feedback, friends and player reporting in the pause
 * menu); the bridge button joins the right end of that row. Loader glue calls {@link #create} after the
 * screen and every other mod initialised it, so a button another mod placed there is stepped over
 * instead of covered.
 */
public final class EntryButtons {

    /** The button's 15 by 15 sprite in the GUI atlas. */
    public static final Identifier SPRITE = Constants.id("icon/bridge");

    private static final int SIZE = 20;
    private static final int GAP = 4;
    private static final int SPRITE_SIZE = 15;

    private EntryButtons() {
    }

    /**
     * Creates the bridge button for a screen that should have one, positioned next to its icon row.
     *
     * @param screen a screen whose widgets were just initialised
     * @param open opens Bridge home with {@code screen} as its parent
     * @return the button to add to the screen, or null when the screen gets none: not the title screen
     *     or pause menu, a pause screen without its menu (F3+Esc), or no free spot in the row
     */
    public static @Nullable AbstractWidget create(final Screen screen, final Runnable open) {
        if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) {
            return null;
        }
        final List<AbstractWidget> widgets = new ArrayList<>();
        for (final GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget w && w.visible) {
                widgets.add(w);
            }
        }
        final Integer rowY = iconRow(widgets);
        if (rowY == null) {
            return null;
        }
        int x = Integer.MIN_VALUE;
        for (final AbstractWidget w : widgets) {
            if (w instanceof SpriteIconButton && w.getY() == rowY) {
                x = Math.max(x, w.getRight() + GAP);
            }
        }
        while (overlaps(widgets, x, rowY)) {
            x += SIZE + GAP;
        }
        if (x + SIZE > screen.width) {
            return null;
        }
        final Component name = Component.literal(Constants.MOD_NAME);
        final SpriteIconButton button = SpriteIconButton.builder(name, b -> open.run(), true)
                .width(SIZE)
                .sprite(SPRITE, SPRITE_SIZE, SPRITE_SIZE)
                .withTootip()
                .build();
        button.setPosition(x, rowY);
        return button;
    }

    /** The y of the row with the most 20 by 20 icon buttons, or null when there is none. */
    private static @Nullable Integer iconRow(final List<AbstractWidget> widgets) {
        final Map<Integer, Integer> counts = new HashMap<>();
        Integer best = null;
        for (final AbstractWidget w : widgets) {
            if (w instanceof SpriteIconButton && w.getWidth() == SIZE && w.getHeight() == SIZE) {
                final int count = counts.merge(w.getY(), 1, Integer::sum);
                if (best == null || count > counts.get(best)) {
                    best = w.getY();
                }
            }
        }
        return best;
    }

    private static boolean overlaps(final List<AbstractWidget> widgets, final int x, final int y) {
        for (final AbstractWidget w : widgets) {
            if (x < w.getRight() && w.getX() < x + SIZE && y < w.getBottom() && w.getY() < y + SIZE) {
                return true;
            }
        }
        return false;
    }
}
