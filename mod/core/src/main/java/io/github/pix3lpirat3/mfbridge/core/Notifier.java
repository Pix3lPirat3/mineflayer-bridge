package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Sink for toasts that bots request with {@code notify}. The host labels them with the source. */
@FunctionalInterface
public interface Notifier {
    /** Notifier that drops everything. */
    Notifier NONE = n -> {};

    /**
     * A button on a notification.
     *
     * @param label the label, untrusted
     * @param action full key of one of the sender's actions, run when clicked
     */
    record Button(String label, String action) {}

    /**
     * A notification request.
     *
     * @param source display name of the requesting session, shown so bots cannot spoof the game
     * @param owner the requesting session's owner key
     * @param text the text, untrusted
     * @param level severity such as {@code info}, {@code warn} or {@code error}
     * @param icon optional icon id, or null
     * @param buttons buttons, each naming an action under {@code owner}; empty for none
     * @param sound optional sound event id to play with it, or null
     */
    record Notification(String source, String owner, String text, String level, @Nullable String icon, List<Button> buttons,
            @Nullable String sound) {

        /** Copies the list. */
        public Notification {
            buttons = List.copyOf(buttons);
        }

        /**
         * A notification without buttons or sound.
         *
         * @param source display name of the requesting session
         * @param text the text
         * @param level severity
         * @param icon optional icon id
         */
        public Notification(String source, String text, String level, @Nullable String icon) {
            this(source, "", text, level, icon, List.of(), null);
        }
    }

    /** Shows or queues a notification. Called on the client thread; must not block. */
    @ClientThread
    void notify(Notification notification);
}
