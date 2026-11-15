package io.github.pix3lpirat3.mfbridge.api.client;

/** A button an extension adds to bot rows on Bridge home. */
public interface BotButton {

    /**
     * Returns whether the button shows on this bot's row.
     *
     * @param botOwner {@code bot:<identity>}
     * @param online whether the bot is connected
     * @return true to show it
     */
    default boolean shows(String botOwner, boolean online) {
        return online;
    }

    /**
     * The player pressed the button.
     *
     * @param botOwner {@code bot:<identity>} of the row
     */
    void press(String botOwner);
}
