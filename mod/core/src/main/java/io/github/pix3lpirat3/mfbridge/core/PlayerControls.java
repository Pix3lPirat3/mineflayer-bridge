package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import org.jspecify.annotations.Nullable;

/** What the client reports about the player; sessions hear of it in {@code player.context}. */
@ClientThread
public interface PlayerControls {
    /**
     * Sets what the player is doing.
     *
     * @param alive false while the death screen shows
     * @param away no input for the time the player set
     * @param windowFocused the game window has focus
     */
    void setState(boolean alive, boolean away, boolean windowFocused);

    /**
     * Sets whether the player's world runs in their own game, and the port it is open to LAN on.
     *
     * @param singleplayer the player is in their own singleplayer world
     * @param lanPort the port it is open to LAN on, or 0 while it is not
     */
    void setSingleplayer(boolean singleplayer, int lanPort);

    /**
     * Sets the player's profile name and id, so bots know who gives orders. Null when unknown.
     *
     * @param uuid the profile id as a dashed lowercase UUID
     */
    void setProfile(@Nullable String name, @Nullable String uuid);

    /**
     * Reports the player's position for level of detail: bots far from it get {@code
     * interest.detail = reduced}. NaN coordinates mean unknown.
     */
    void setPosition(double x, double y, double z);

    /** Returns the player's world, or null on the title screen. */
    @Nullable WorldKey world();
}
