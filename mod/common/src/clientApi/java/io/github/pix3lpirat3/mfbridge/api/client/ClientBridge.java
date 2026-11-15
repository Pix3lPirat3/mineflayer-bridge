package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The running bridge as a {@link ClientExtension} sees it: the bots, a way to message their Node
 * halves, the player's own objects, what the player is looking at, and the bridge's feedback.
 *
 * <p>The bridge's endpoint can be off (the player switched it off, or its port was taken). While it
 * is, {@link #running()} is false, the lists are empty and the calls that act do nothing.
 *
 * <p>Client thread only. Nothing here sends a packet to the game server.
 */
public interface ClientBridge {

    /**
     * Returns whether the bridge's endpoint is running.
     *
     * @return false before it starts, after it stops, or when it failed to start
     */
    boolean running();

    /**
     * Returns the bots and controllers that have a session, attached or not.
     *
     * @return the sessions, ordered by owner key
     */
    List<SessionView> sessions();

    /**
     * Returns a bot's display name, such as its username.
     *
     * @param botOwner the owner key, {@code bot:...}
     * @return the name, or {@code botOwner} when the bot is unknown
     */
    String botName(String botOwner);

    /**
     * Returns the session of an owner, to send the extension's messages to its Node half. Sending
     * throws when that session's Node side did not load the extension; {@link
     * MessageContext#session()} gives a handle that can be kept instead.
     *
     * @param owner the owner key, with or without a trailing slash
     * @return the session, or null while no session writes for that owner
     */
    @Nullable MessageContext session(String owner);

    /**
     * Tells the bridge that the player asked this owner for something through the extension, such
     * as an order or a typed line. For a short while the bot may then answer with a form or a
     * screen; without it, a surface the player did not ask for can be refused.
     *
     * @param owner the owner key
     */
    void playerAsked(String owner);

    /**
     * Returns the world the player is in.
     *
     * @return the world, or null on the title screen or while the bridge is not running
     */
    @Nullable WorldKey playerWorld();

    /**
     * Writes an object of the player's own, for example a waypoint the extension marks. It is in the
     * player's current world when there is one, and global otherwise; writing the same key again
     * replaces it.
     *
     * @param key the object key, under {@code player/}
     * @param kind the kind of {@code data}; core kinds are the constants of {@code CoreKinds}
     * @param layer the layer name within the key's extension, or null for none
     * @param data the object's data
     * @param <T> the data type of the kind
     * @throws IllegalArgumentException when the key is not under {@code player/}
     */
    <T> void putPlayerObject(String key, KindType<T> kind, @Nullable String layer, T data);

    /**
     * Removes an object written with {@link #putPlayerObject}.
     *
     * @param key the object key
     * @return whether it existed
     */
    boolean removePlayerObject(String key);

    /**
     * Returns the objects the player currently sees whose data is of a type, for example the objects
     * of a kind the extension registered.
     *
     * @param dataType the class of the decoded data
     * @param <T> the data type
     * @return the objects in key order
     */
    <T> List<ObjectView<T>> objects(Class<T> dataType);

    /**
     * Returns the zones, points and routes the player currently sees.
     *
     * @return the places in key order
     */
    List<Place> places();

    /**
     * Opens the bridge's action menu for something the player picked: an entity, a point or a zone.
     * The menu lists the bot actions that fit the target, and running one tells the bot which bots
     * the player had selected.
     *
     * @param entity the entity picked, or null
     * @param zoneKey the key of the zone picked ({@link Place#key()}), or null
     * @param pointKey the key of the point picked, or null
     * @param title shown in the menu's centre
     * @param bots names of the bots the player selected
     * @param parent the screen to return to
     * @return whether a menu opened; false when no action fits, so the caller can treat the pick itself
     */
    boolean openActionMenu(@Nullable Entity entity, @Nullable String zoneKey, @Nullable String pointKey, String title,
            List<String> bots, Screen parent);

    /**
     * Returns what the player's crosshair is on.
     *
     * @param range how far to look, in blocks
     * @return the aim, or null when the crosshair is on nothing within range
     */
    @Nullable Aim aim(double range);

    /**
     * Returns the prefix that addresses bots in chat, such as {@code @}.
     *
     * @return the prefix the player set
     */
    String chatPrefix();

    /**
     * Runs a line as if the player had typed it in chat to the bots: the prefix and who it is for,
     * then a command or free text. The line never goes to the game server.
     *
     * @param line the line, starting with {@link #chatPrefix()}
     * @return false when the line addresses nobody the bridge knows, so nothing ran; true when it
     *     ran, and also while the bridge is not running, when the line is dropped
     */
    boolean runChatLine(String line);

    /**
     * Returns how long an extension should keep what it buffered for a bot after the bot's session
     * ended, so that a bot that reconnects finds its state and one that is gone does not hold memory.
     *
     * @return the time in milliseconds
     */
    long bufferMs();

    /**
     * Returns whether the player is in the game with no screen open, which is when keys act.
     *
     * @return true while in play
     */
    boolean inPlay();

    /**
     * Shows an information message the way the player chose: a toast or a chat line.
     *
     * @param title a short title, such as the extension's name
     * @param message the message
     */
    void toast(String title, String message);

    /**
     * Shows an error message.
     *
     * @param title a short title
     * @param message the message
     */
    void toastError(String title, String message);

    /**
     * Plays a short interface sound.
     *
     * @param soundId a sound event id, such as {@code block.note_block.bell}
     */
    void playSound(String soundId);
}
