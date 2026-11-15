package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * A bot or controller that has a session with the bridge.
 *
 * @param owner the owner key, {@code bot:<identity>} for a bot; what the other calls take
 * @param name the display name, such as the bot's username
 * @param controller true for a controller, which runs bots but is not in the world itself
 * @param connected whether the session has a connection right now; false while it waits to resume
 * @param inPlayerWorld whether a bot reported the world the player is in; false for controllers
 * @param position where a bot last reported to be, or null when it has not said
 */
public record SessionView(String owner, String name, boolean controller, boolean connected, boolean inPlayerWorld,
        @Nullable Vec3d position) {
}
