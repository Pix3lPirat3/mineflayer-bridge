package io.github.pix3lpirat3.mfbridge.core.effect;

import io.github.pix3lpirat3.mfbridge.api.event.EventType;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectBody;

/**
 * Posted on the client thread when an attached bot plays a client-only effect ({@code effect.play}).
 * The client plays it locally and never tells the server. Positional effects (a sound with {@code
 * at}, particles) are only posted while the bot is in the player's world. Each session may play at
 * most {@link #MAX_PER_SECOND} effects per second; the rest are dropped without an error, since
 * {@code effect.play} is unreliable.
 *
 * @param ownerName display name of the bot, for labelling titles and action-bar lines
 * @param effect the effect to play
 */
public record EffectRequested(String ownerName, EffectBody effect) {
    /** Effects one session may play per second. */
    public static final int MAX_PER_SECOND = 40;

    /** The event type to subscribe to on {@code BridgeCore.events()}. */
    public static final EventType<EffectRequested> TYPE = EventType.create("core:effect.requested", EffectRequested.class);
}
