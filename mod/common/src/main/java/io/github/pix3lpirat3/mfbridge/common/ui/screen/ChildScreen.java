package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Base of every bridge screen built on vanilla {@link Screen}: Esc and {@link #onClose()} go back to the
 * parent, or to the game when there is none, and the game keeps running behind it. It is a {@link
 * BridgeScreen}, so it comes first in input arbitration. Client thread only.
 */
public abstract class ChildScreen extends Screen implements BridgeScreen {

    private final @Nullable Screen parent;

    /**
     * Creates the screen.
     *
     * @param title the screen title
     * @param parent the screen to return to, or null to close to the game
     */
    protected ChildScreen(final Component title, final @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    /**
     * Returns the screen this one returns to.
     *
     * @return the parent, or null when closing returns to the game
     */
    public final @Nullable Screen parent() {
        return parent;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
