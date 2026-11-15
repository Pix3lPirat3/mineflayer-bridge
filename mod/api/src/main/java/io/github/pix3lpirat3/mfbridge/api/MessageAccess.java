package io.github.pix3lpirat3.mfbridge.api;

import org.jspecify.annotations.Nullable;

/**
 * What a session needs to send an extension message.
 *
 * @param permission wire name of a device permission the session must hold, such as {@code chat} or
 *     {@code render.hud}, or null for none
 * @param attached whether the player must have the session attached; controllers always are
 */
public record MessageAccess(@Nullable String permission, boolean attached) {
    /** Any session may send the message. */
    public static final MessageAccess OPEN = new MessageAccess(null, false);

    /** Only sessions the player has attached may send the message. */
    public static final MessageAccess ATTACHED = new MessageAccess(null, true);

    /** Returns a copy that also needs a device permission, by wire name. */
    public MessageAccess withPermission(String name) {
        return new MessageAccess(name, attached);
    }
}
