package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.common.seam.WorldRenderListener;
import java.util.function.BiConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link ClientExtension} can add to the game. Every hook runs inside
 * a guard: one that throws is logged and switched off for the rest of the session, never crashing the game.
 */
public interface ClientRegistrar {

    /**
     * Returns the view of the running bridge. Valid for the whole session, not only during
     * registration.
     *
     * @return the bridge view
     */
    ClientBridge bridge();

    /**
     * Returns the mod's config folder, {@code config/mineflayer_bridge}, for files an extension keeps.
     * It may not exist yet.
     *
     * @return the folder
     */
    java.nio.file.Path configDir();

    /**
     * Adds a Bridge home tab after the built-in ones.
     *
     * @param label the tab label, resolved each time the tab shows (a translatable one follows the language)
     * @param tab its content
     */
    void homeTab(Component label, HomeTab tab);

    /**
     * Adds a button to bot rows on Bridge home.
     *
     * @param label the label, resolved each time the row shows
     * @param icon an item id shown before the label, or null
     * @param button when it shows and what it does
     */
    void botButton(Component label, @Nullable String icon, BotButton button);

    /**
     * Adds a key in the bridge's section of the Controls screen.
     *
     * @param name the mapping name; the translation key is {@code key.mineflayer_bridge.<name>}
     * @param defaultKey the default GLFW key code, or {@code InputConstants.UNKNOWN.getValue()} to
     *     leave it unbound until the player sets it
     * @param onPress runs on the client thread for each press in game
     * @return the mapping, for showing the key the player bound
     */
    KeyMapping keybind(String name, int defaultKey, Runnable onPress);

    /**
     * Adds a key mapping the extension made itself, for example in a category of its own; the bridge
     * registers the mapping and any category it uses.
     *
     * @param mapping the mapping
     * @param onPress runs on the client thread for each press in game
     */
    void keybind(KeyMapping mapping, Runnable onPress);

    /**
     * Adds a stage to the in-game input chain.
     *
     * @param priority where it runs, relative to the bridge's stages in {@link InputOrder}
     * @param stage the stage
     */
    void inputStage(int priority, InputStage stage);

    /**
     * Runs a hook every client tick, after the bridge's own tick.
     *
     * @param hook the hook
     */
    void tick(Runnable hook);

    /**
     * Draws into the bridge's world pass, on the render thread.
     *
     * @param listener the drawing
     */
    void worldRender(WorldRenderListener listener);

    /**
     * Draws on the HUD after the bridge's own HUD.
     *
     * @param hud the drawing
     */
    void hud(BiConsumer<GuiGraphicsExtractor, DeltaTracker> hud);
}
