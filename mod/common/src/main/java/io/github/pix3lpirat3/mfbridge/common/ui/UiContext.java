package io.github.pix3lpirat3.mfbridge.common.ui;
import io.github.pix3lpirat3.mfbridge.common.version.Game;

import io.github.pix3lpirat3.mfbridge.common.sound.BotSounds;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Services the UI controllers and screens share. Client thread only. */
public interface UiContext {

    /**
     * Returns whether the player is in a world with no screen or loading overlay open, so the bridge
     * may open a screen of its own.
     *
     * @return true while the game is in play
     */
    static boolean inPlay() {
        final Minecraft minecraft = Minecraft.getInstance();
        return Game.screen() == null && minecraft.player != null && Game.overlay() == null;
    }

    /**
     * Returns the running core.
     *
     * @return the core, or null while the endpoint is not running
     */
    @Nullable BridgeCore core();

    /**
     * Returns the current declarations.
     *
     * @return the snapshot, or {@link DeclarationSnapshot#EMPTY} while the core is down
     */
    default DeclarationSnapshot declarations() {
        final BridgeCore core = core();
        return core == null ? DeclarationSnapshot.EMPTY : core.declarations();
    }

    /**
     * Returns the bot sound player.
     *
     * @return the player
     */
    BotSounds sounds();

    /**
     * Returns the bridge clock time that input deadlines are measured in.
     *
     * @return monotonic milliseconds
     */
    long nowMs();

    /**
     * Records that the UI answered or cancelled an input request, so it is not shown again while the
     * core catches up.
     *
     * @param id the input queue id
     */
    void markHandled(long id);

    /**
     * Shows an informational toast.
     *
     * @param title the toast title, usually a bot name
     * @param message the text
     */
    void toastInfo(String title, String message);

    /**
     * Shows an error toast.
     *
     * @param title the toast title, usually a bot name
     * @param message the text
     */
    void toastError(String title, String message);

    /**
     * Shows progress for a running action or command in the HUD, then its result as a toast.
     *
     * @param label what is running, for example the action label
     * @param owner the bot that runs it
     * @param result the invocation's result
     */
    void track(String label, String owner, CompletableFuture<InvocationResult> result);

    /**
     * Like {@link #track}, for a command the player typed in chat: the reply comes back as a chat
     * line, as a server command's does, not as a toast.
     *
     * @param label what was run, for a reply without a message
     * @param owner the bot or controller that runs it
     * @param result the invocation's result
     */
    void trackInChat(String label, String owner, CompletableFuture<InvocationResult> result);

    /**
     * Opens a bot's container view or menu and reports it open to the core.
     *
     * @param viewKey full key of a declared {@code core:container-view}
     * @return whether the view opened
     */
    boolean openContainerView(String viewKey);

    /** Opens Bridge home over the game. */
    void openHome();

    /** Opens the mod's settings over the game. */
    void openSettings();
}
