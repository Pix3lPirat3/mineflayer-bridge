package io.github.pix3lpirat3.mfbridge.common.ui;

import io.github.pix3lpirat3.mfbridge.common.seam.Notification;
import io.github.pix3lpirat3.mfbridge.common.sound.BotSounds;
import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import io.github.pix3lpirat3.mfbridge.core.Notifier;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.resources.language.I18n;

/**
 * What the player is told: the mod's own messages and bots' notifications, as toasts or chat lines
 * by the "Bot messages" setting, and the answers of the actions and commands still running, which
 * the HUD lists while they wait. Client thread only.
 */
public final class PlayerFeedback {

    /** Longest text a toast shows, about its six lines; a longer one is cut there and printed whole in chat. */
    private static final int TOAST_TEXT_MAX = 160;

    private final Consumer<Notification> notifier;
    private final ChatBridgeClient chat;
    private final BotSounds sounds;
    private final LongSupplier clock;
    private final List<Pending> pending = new ArrayList<>();
    private boolean messagesInChat = true;

    /**
     * An action or command waiting for the bot's answer.
     *
     * @param label what runs
     * @param owner the bot that runs it
     * @param startedMs when it started, bridge clock milliseconds
     */
    public record Pending(String label, String owner, long startedMs) {
    }

    /**
     * Creates the tracker.
     *
     * @param notifier shows toasts
     * @param chat prints chat lines
     * @param sounds plays a notification's sound
     * @param clock bridge clock milliseconds
     */
    public PlayerFeedback(final Consumer<Notification> notifier, final ChatBridgeClient chat, final BotSounds sounds, final LongSupplier clock) {
        this.notifier = notifier;
        this.chat = chat;
        this.sounds = sounds;
        this.clock = clock;
    }

    /**
     * Applies the "Bot messages" setting.
     *
     * @param inChat whether notifications and the mod's own messages go to chat rather than toasts
     */
    public void setMessagesInChat(final boolean inChat) {
        messagesInChat = inChat;
    }

    /**
     * Returns the actions and commands waiting for an answer.
     *
     * @return the pending invocations, oldest first; a live view
     */
    public List<Pending> pending() {
        return pending;
    }

    /** Forgets what was waiting, for a core that stopped or changed. */
    public void clear() {
        pending.clear();
    }

    /**
     * Shows a bot's notification: in chat when the player chose chat or when it has buttons to
     * click, otherwise as a toast. Plays its sound either way.
     *
     * @param n the notification
     */
    public void notice(final Notifier.Notification n) {
        if (n.sound() != null) {
            sounds.ui(n.sound());
        }
        if (messagesInChat || !n.buttons().isEmpty()) {
            chat.notice(n.source(), n.text(), n.level(), n.buttons());
            return;
        }
        final Notification.Severity severity = switch (n.level()) {
            case "error" -> Notification.Severity.ERROR;
            case "warn", "warning" -> Notification.Severity.WARNING;
            default -> Notification.Severity.INFO;
        };
        toast(n.source(), n.text(), n.level(), severity);
    }

    /** A toast, with a text too long for one also printed whole in chat. */
    private void toast(final String title, final String text, final String level, final Notification.Severity severity) {
        if (text.length() > TOAST_TEXT_MAX) {
            chat.notice(title, text, level, List.of());
        }
        notifier.accept(new Notification(title, cut(text), severity));
    }

    private static String cut(final String text) {
        return text.length() > TOAST_TEXT_MAX ? text.substring(0, TOAST_TEXT_MAX - 3) + "..." : text;
    }

    /**
     * Shows an informational message.
     *
     * @param title the title, usually a bot name
     * @param message the text
     */
    public void info(final String title, final String message) {
        if (messagesInChat) {
            chat.notice(title, message, "info", List.of());
        } else {
            toast(title, message, "info", Notification.Severity.INFO);
        }
    }

    /**
     * Shows an error message.
     *
     * @param title the title, usually a bot name
     * @param message the text
     */
    public void error(final String title, final String message) {
        if (messagesInChat) {
            chat.notice(title, message, "error", List.of());
        } else {
            toast(title, message, "error", Notification.Severity.ERROR);
        }
    }

    /**
     * Lists a running action or command as pending, then shows its answer.
     *
     * @param label what is running
     * @param owner the bot or controller that runs it
     * @param result the invocation's result
     * @param inChat whether the answer is always a chat line, as for a command typed in chat
     */
    public void track(final String label, final String owner, final CompletableFuture<InvocationResult> result, final boolean inChat) {
        final Pending p = new Pending(label, owner, clock.getAsLong());
        pending.add(p);
        result.whenComplete((r, error) -> {
            pending.remove(p);
            if (error == null && r.quiet()) {
                return;
            }
            final boolean ok = error == null && r.ok();
            final String text;
            if (error != null) {
                text = label + ": " + BridgeUi.describe(error);
            } else if (r.message() != null && !r.message().isEmpty()) {
                text = r.message();
            } else {
                text = I18n.get(r.ok() ? "mineflayer_bridge.action.done" : "mineflayer_bridge.action.failed", label);
            }
            if (inChat || messagesInChat || text.length() > TOAST_TEXT_MAX) {
                chat.reply(owner, text, !ok);
            }
            if (!inChat && !messagesInChat) {
                final String shown = cut(text);
                if (ok) {
                    info(owner, shown);
                } else {
                    error(owner, shown);
                }
            }
        });
    }
}
