package io.github.pix3lpirat3.mfbridge.core.chat;

import org.jspecify.annotations.Nullable;

/**
 * What a {@code bridge} chat click did, or asks the game to show. Such a click acts on the mod
 * itself, never on the bot: the player's click is the consent, and the mod names what it will do
 * in the tooltip.
 *
 * @param kind what happened
 * @param bot display name of the bot attached or detached, or null for the screens
 */
public record BridgeClick(Kind kind, @Nullable String bot) {

    /** The things a {@code bridge} click can do. */
    public enum Kind {
        /** The bot is attached by the player's choice, in any world. */
        ATTACHED,
        /** The bot is detached by the player's choice. */
        DETACHED,
        /** Open Bridge home. */
        MENU,
        /** Open the mod's settings. */
        SETTINGS
    }
}
