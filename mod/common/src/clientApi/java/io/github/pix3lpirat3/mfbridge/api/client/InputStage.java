package io.github.pix3lpirat3.mfbridge.api.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/**
 * One stage of the in-game input chain. Stages see presses while the
 * player is in game with no screen open, in priority order; the first that consumes one wins.
 */
public interface InputStage {

    /** What a stage did with an input. */
    enum Outcome {
        /** Not mine; later stages may take it. */
        PASS,
        /** Taken; nothing else sees it, and its release is swallowed too. */
        CONSUMED,
        /** Not taken, but later stages must not see it while this stage is active; keybinds still do. */
        OWNED
    }

    /**
     * A key press.
     *
     * @param event the key
     * @param name its vanilla key name, such as {@code key.keyboard.g}
     * @return what happened
     */
    default Outcome key(KeyEvent event, String name) {
        return Outcome.PASS;
    }

    /**
     * A mouse button press.
     *
     * @param key the button
     * @param name its vanilla key name, such as {@code key.mouse.left}
     * @return what happened
     */
    default Outcome button(InputConstants.Key key, String name) {
        return Outcome.PASS;
    }

    /**
     * A scroll.
     *
     * @param amount vertical scroll, positive up
     * @return what happened
     */
    default Outcome scroll(double amount) {
        return Outcome.PASS;
    }
}
