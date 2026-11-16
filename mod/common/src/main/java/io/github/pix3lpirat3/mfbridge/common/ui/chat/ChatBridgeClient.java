package io.github.pix3lpirat3.mfbridge.common.ui.chat;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.pix3lpirat3.mfbridge.api.event.Subscription;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PacketGuard;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Notifier;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.command.Completion;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The client half of the chat bridge.
 *
 * <p>Outgoing: {@link #filterOutgoing} runs from the {@code ClientPacketListener.sendChat} mixin on
 * both loaders, before the chat packet (and its signature and last-seen update) is built. Vanilla's
 * {@code ChatScreen} has already put the line in the local chat history by then, so captured lines
 * stay in history but never reach the server.
 *
 * <p>Incoming: {@code chat.print} lines are added with {@code ChatComponent.addClientSystemMessage},
 * which only displays them. Clickable spans use vanilla suggest and copy click events, which act on
 * the client; {@code run_action} and {@code run_command} use custom click events in the mod's
 * namespace that {@link #onClick} handles before vanilla would send them to the server (the packet
 * guard drops them as well). Client thread only.
 */
public final class ChatBridgeClient {

    private static final String CLICK_PREFIX = "chat/";
    private static final int MAX_CLICKS = 512;
    private static final int TAG_ARGB = 0x55FFFF;

    private final UiContext ui;
    private final PacketGuard guard;
    private final Int2ObjectLinkedOpenHashMap<Runnable> clicks = new Int2ObjectLinkedOpenHashMap<>();
    private @Nullable Subscription printSubscription;
    private int nextClick;

    /**
     * Creates the chat bridge client.
     *
     * @param ui the UI services
     * @param guard the packet guard told about captured lines
     */
    public ChatBridgeClient(final UiContext ui, final PacketGuard guard) {
        this.ui = ui;
        this.guard = guard;
    }

    /**
     * Connects to a newly started core and listens for printed lines. Chat commands parse with the
     * core's command engine and the context {@code BridgeUi} installs.
     *
     * @param core the core, or null when it stopped
     */
    public void coreChanged(final @Nullable BridgeCore core) {
        final Subscription old = printSubscription;
        printSubscription = null;
        if (old != null) {
            old.close();
        }
        clicks.clear();
        if (core == null) {
            return;
        }
        printSubscription = core.events().subscribe(ChatPrint.TYPE, this::print);
    }

    /**
     * Offers a chat line the player is sending to the chat bridge.
     *
     * @param line the line, as passed to {@code sendChat}
     * @return null to drop the line, the line itself to send it, or another line to send instead
     */
    public @Nullable String filterOutgoing(final String line) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return line;
        }
        final ChatRoute route = core.chat().route(line);
        if (route instanceof ChatRoute.PassThrough(final String forServer)) {
            guard.released(forServer);
            return forServer;
        }
        guard.captured(line, System.nanoTime() / 1_000_000L);
        switch (route) {
            case ChatRoute.CapturedCommand c -> {
                for (final ChatRoute.Run run : c.runs()) {
                    ui.trackInChat(c.command(), run.ownerName(), run.result());
                }
            }
            case ChatRoute.CapturedMessage m -> {
                final String to = String.join(", ", m.recipients());
                local(Component.translatable("mineflayer_bridge.chat.to", to).withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
                if (m.commandError() != null) {
                    local(Component.translatable("mineflayer_bridge.chat.not_command", m.commandError()).withStyle(ChatFormatting.DARK_GRAY));
                }
            }
            case ChatRoute.CapturedHelp help -> {
                for (final String text : help.lines()) {
                    local(Component.literal(text).withStyle(ChatFormatting.GRAY));
                }
            }
            case ChatRoute.CapturedPrompt p -> {
                if (p.error() != null) {
                    local(Component.literal(p.error()).withStyle(ChatFormatting.RED));
                }
            }
            case ChatRoute.Unknown u -> local(Component.literal(u.message()).withStyle(ChatFormatting.RED));
            case ChatRoute.Mistyped m -> local(Component.translatable("mineflayer_bridge.chat.mistyped", m.name(),
                    suggestion(Component.translatable("mineflayer_bridge.chat.mistyped.fix", m.closest()), m.corrected()),
                    suggestion(Component.translatable("mineflayer_bridge.chat.mistyped.send"), m.asChat())).withStyle(ChatFormatting.GRAY));
            case ChatRoute.PassThrough pass -> {
            }
        }
        return null;
    }

    /**
     * Completes a chat box line addressed to bots, for the {@code CommandSuggestions} mixin.
     *
     * @param value the chat box text, without a leading slash
     * @param cursor the cursor offset
     * @return Brigadier suggestions for the token at the cursor, or null to keep vanilla's
     */
    public @Nullable CompletableFuture<Suggestions> suggestions(final String value, final int cursor) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        final String typed = value.substring(0, Math.min(cursor, value.length()));
        final CompletableFuture<Completion> completion = core.chat().complete(typed, typed.length());
        if (completion == null) {
            return null;
        }
        return completion.thenApply(c -> {
            final SuggestionsBuilder b = new SuggestionsBuilder(typed, Math.min(c.start(), typed.length()));
            for (final Suggestion s : c.suggestions()) {
                final Component tooltip = BridgeCommands.tooltip(s);
                if (tooltip == null) {
                    b.suggest(s.text());
                } else {
                    b.suggest(s.text(), tooltip);
                }
            }
            return b.build();
        });
    }

    /**
     * Validates a chat box line addressed to a bot command while it is typed.
     *
     * @param value the chat box text
     * @return why the command does not parse, or null when it parses or is not a bot command
     */
    public @Nullable String validationError(final String value) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        return core.chat().validate(value) instanceof ParseResult.Failure f ? f.message() : null;
    }

    /**
     * A command's reply as a client-only chat line, tagged with who answered; any thread.
     *
     * @param owner the bot or controller name
     * @param text the reply
     * @param error whether the command failed
     */
    public void reply(final String owner, final String text, final boolean error) {
        // Each line of a multi-line reply is its own chat line; the first carries the tag.
        final String[] lines = text.split("\r?\n");
        final List<Component> out = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            final MutableComponent line = i == 0 ? Component.literal("[" + owner + "] ").withColor(TAG_ARGB) : Component.literal("  ");
            out.add(line.append(Component.literal(lines[i]).withStyle(error ? ChatFormatting.RED : ChatFormatting.WHITE)));
        }
        Minecraft.getInstance().execute(() -> out.forEach(ChatBridgeClient::local));
    }

    /**
     * A notification or the mod's own message as a client-only chat line, tagged with who sent it,
     * with its buttons after the text; any thread.
     *
     * @param source who it is from
     * @param text the text
     * @param level {@code info}, {@code success}, {@code warn} or {@code error}
     * @param buttons buttons that run the sender's actions when clicked
     */
    public void notice(final String source, final String text, final String level, final List<Notifier.Button> buttons) {
        final ChatFormatting color = switch (level) {
            case "error" -> ChatFormatting.RED;
            case "warn", "warning" -> ChatFormatting.YELLOW;
            case "success" -> ChatFormatting.GREEN;
            default -> ChatFormatting.WHITE;
        };
        final String[] lines = text.split("\r?\n");
        Minecraft.getInstance().execute(() -> {
            for (int i = 0; i < lines.length; i++) {
                final MutableComponent line = i == 0 ? Component.literal("[" + source + "] ").withColor(TAG_ARGB) : Component.literal("  ");
                line.append(Component.literal(lines[i]).withStyle(color));
                if (i == lines.length - 1) {
                    for (final Notifier.Button b : buttons) {
                        line.append(Component.literal(" "));
                        line.append(Component.literal("[" + b.label() + "]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                .withClickEvent(register(() -> runAction(source, b.action(), "notification")))));
                    }
                }
                local(line);
            }
        });
    }

    private static void local(final Component line) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(line);
    }

    private void print(final ChatPrint p) {
        final MutableComponent line = Component.literal("[" + p.ownerName() + "] ").withColor(TAG_ARGB);
        for (final ChatSpan span : p.spans()) {
            line.append(Component.literal(span.text()).withStyle(style(p, span)));
        }
        local(line);
    }

    private Style style(final ChatPrint p, final ChatSpan span) {
        Style s = Style.EMPTY.withBold(span.bold()).withItalic(span.italic()).withUnderlined(span.underlined())
                .withStrikethrough(span.strikethrough()).withObfuscated(span.obfuscated());
        if (span.color() != null) {
            s = s.withColor(span.color() & 0xFFFFFF);
        }
        if (span.hover() != null) {
            s = s.withHoverEvent(new HoverEvent.ShowText(Component.literal(span.hover())));
        }
        final ChatClick click = span.click();
        if (click != null) {
            s = s.withClickEvent(clickEvent(p, click));
        }
        return s;
    }

    private @Nullable ClickEvent clickEvent(final ChatPrint p, final ChatClick click) {
        return switch (click.action()) {
            case "suggest" -> new ClickEvent.SuggestCommand(click.value());
            case "copy" -> new ClickEvent.CopyToClipboard(click.value());
            case "open_url" -> openUrl(click.value());
            case "run_action" -> register(() -> runAction(p.ownerName(), click.value(), "air"));
            case "run_command" -> register(() -> runCommand(p, click.value()));
            default -> null;
        };
    }

    /** Only http and https links; vanilla asks the player before it opens one. */
    private static @Nullable ClickEvent openUrl(final String value) {
        try {
            final java.net.URI uri = new java.net.URI(value);
            final String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return new ClickEvent.OpenUrl(uri);
            }
        } catch (final java.net.URISyntaxException e) {
            Constants.LOG.debug("Ignoring a bad chat link {}", value);
        }
        return null;
    }

    /** A clickable part that puts a line into the chat box for the player to send. */
    private static Component suggestion(final MutableComponent text, final String line) {
        return text.withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(line))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(line))));
    }

    /**
     * A chat click that runs code on this client and never reaches the server, for the mod's own lines.
     *
     * @param action what the click does, on the client thread
     * @return the click event to put on a component's style
     */
    public ClickEvent localClick(final Runnable action) {
        return register(action);
    }

    private ClickEvent register(final Runnable action) {
        final int id = nextClick++;
        clicks.put(id, action);
        if (clicks.size() > MAX_CLICKS) {
            clicks.removeFirst();
        }
        return new ClickEvent.Custom(Constants.id(CLICK_PREFIX + Integer.toUnsignedString(id)), Optional.empty());
    }

    /**
     * Runs a clicked action. A chat line's action gets the air at the player as its target; a
     * notification button's gets a {@code notification} target, which nothing else offers.
     */
    private void runAction(final String ownerName, final String actionKey, final String targetType) {
        final BridgeCore core = ui.core();
        final Minecraft minecraft = Minecraft.getInstance();
        if (core == null || minecraft.player == null) {
            return;
        }
        final Target target = new Target(targetType, null, null, null, Crosshair.vec(minecraft.player.position()), null, null);
        ui.track("action", ownerName, core.invokeAction(actionKey, target, "chat"));
    }

    /** {@code run_command}: the value is a command line for the printing bot. */
    private void runCommand(final ChatPrint p, final String line) {
        final BridgeCore core = ui.core();
        if (core != null) {
            final String name = line.strip().split("\\s+", 2)[0];
            ui.track(name, p.ownerName(), core.chat().runCommandLine(p.owner(), line));
        }
    }

    /**
     * Handles a chat click before vanilla does.
     *
     * @param event the clicked event
     * @return true when it was a bridge click, which must not reach vanilla (and so the server)
     */
    public boolean onClick(final ClickEvent event) {
        if (!(event instanceof ClickEvent.Custom custom) || !Constants.MOD_ID.equals(custom.id().getNamespace())) {
            return false;
        }
        final Identifier id = custom.id();
        if (id.getPath().startsWith(CLICK_PREFIX)) {
            try {
                final Runnable action = clicks.get(Integer.parseUnsignedInt(id.getPath().substring(CLICK_PREFIX.length())));
                if (action != null) {
                    action.run();
                } else {
                    ui.toastError(Constants.MOD_NAME, I18n.get("mineflayer_bridge.chat.expired"));
                }
            } catch (final NumberFormatException e) {
                Constants.LOG.debug("Ignoring malformed bridge click {}", id);
            }
        }
        return true;
    }

    /**
     * Returns the chat bridge of the running UI, for mixins.
     *
     * @return the chat client, or null before the mod initialised
     */
    public static @Nullable ChatBridgeClient current() {
        final BridgeUi ui = BridgeUi.current();
        return ui == null ? null : ui.chat();
    }
}
