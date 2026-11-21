package io.github.pix3lpirat3.mfbridge.common.ui.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.BridgeStatus;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.command.Addressee;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandEngine;
import io.github.pix3lpirat3.mfbridge.core.command.CommandUsage;
import io.github.pix3lpirat3.mfbridge.core.command.Completion;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * The client command {@code /bridge}, registered with each
 * loader's client command API so it never reaches the game server.
 *
 * <p>{@code /bridge <bot|all> <command> [subcommands...] [args...]} is one Brigadier argument parsed
 * by the core's {@link CommandEngine}, the engine behind the palette and {@code @bot} chat commands.
 * While the player types, a parse error stops the argument just before the offending token and a
 * second argument node rethrows the engine's message there, so vanilla's chat box colours the text
 * from that token on red and shows the reason; an error in the first token is thrown directly.
 * Suggestions come from the engine as a {@link CompletableFuture}, so dynamic completion never
 * blocks typing; display labels and tooltips travel in the Brigadier tooltip {@code Message}.
 *
 * <p>Every level does something: {@code /bridge} prints the endpoint status and connected bots,
 * {@code /bridge menu} opens Bridge home, {@code /bridge help} the usage, {@code /bridge <bot|all>} the command trees, and a command node
 * without a handler (or with subcommands and missing arguments) its subcommands with usage. {@code
 * all} runs the command on every attached bot that has it and gathers the results into one toast.
 *
 * <p>Generic over the loader's command source type; the source itself is never used. Client thread only.
 */
public final class BridgeCommands {

    /** The command literal. */
    public static final String LITERAL = "bridge";

    /** The name the player chose in the settings; used the next time commands are registered. */
    private static volatile String wanted = LITERAL;

    /** The name registered with the loader, which the line parser strips. */
    private static volatile String active = LITERAL;

    /** Server commands seen in the last command tree, by root name. */
    private static volatile java.util.Set<String> serverRoots = java.util.Set.of();

    /** Where a clash was told, with the command name it was told for: each told once a game. */
    private static final java.util.Set<String> clashesTold = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Sets the command name from the settings. It applies when the loader next registers client
     * commands, which both loaders do on joining a world.
     *
     * @param name a valid command name
     */
    public static void setName(final String name) {
        wanted = name;
    }

    /** Returns the registered command name, without the slash. */
    public static String name() {
        return active;
    }

    /**
     * Records the root commands of a command tree the server sent, before client commands join it,
     * and returns a warning when the server has a command with this command's name,
     * which the client command would hide. Servers resend the tree often, and a network of servers
     * sends trees with and without the command as the player moves between them, each move a new
     * connection: so a clash is told once for each address joined and command name, for as long as
     * the game runs.
     *
     * @param roots the server's root command names
     * @param where the address joined (a string), or the connection when there is none
     * @return the warning, or null when there is no clash or it was told already
     */
    public static @Nullable String serverCommands(final java.util.Set<String> roots, final Object where) {
        serverRoots = java.util.Set.copyOf(roots);
        final String name = wanted;
        final String key = (where instanceof String address ? address : "connection " + System.identityHashCode(where)) + " /" + name;
        if (!roots.contains(name) || !clashesTold.add(key)) {
            return null;
        }
        return I18n.get("mineflayer_bridge.command.server_clash", name);
    }

    /** Returns whether the server sent a root command with this name, as of the last command tree. */
    public static boolean serverHas(final String name) {
        return serverRoots.contains(name);
    }

    /** Our texts name the command {@code /bridge}; this shows the name in use. */
    private static String text(final String key, final Object... args) {
        final String s = I18n.get(key, args);
        return active.equals(LITERAL) ? s : s.replace("/" + LITERAL, "/" + active);
    }

    /** The literal that selects every bot. */
    public static final String ALL = CommandEngine.ALL;

    /** The literal that prints usage. */
    public static final String HELP = "help";

    /** The literal that opens Bridge home. */
    public static final String MENU = "menu";

    /** Picks a spatial tool, as the tool wheel does: {@code tool zone|point|path|ruler|off}. */
    public static final String TOOL = "tool";

    private static final String LINE = "invocation";
    private static final String REST = "arguments";
    private static final int INFO_ARGB = 0xFFAAAAAA;

    /**
     * The failure of the last line parsed, keyed by the whole input, so the error node can rethrow
     * it. Parsing and suggestions run on the client thread only.
     */
    private static @Nullable String failedInput;
    private static @Nullable String failedMessage;

    private BridgeCommands() {
    }

    /**
     * Builds the command tree.
     *
     * @param ui the UI layer that owns the core
     * @param <S> the loader's command source type
     * @return the {@code bridge} literal, ready to register
     */
    public static <S> LiteralArgumentBuilder<S> create(final BridgeUi ui) {
        active = wanted;
        return LiteralArgumentBuilder.<S>literal(active)
                .executes(ctx -> status(ui))
                .then(LiteralArgumentBuilder.<S>literal(HELP).executes(ctx -> print(BridgeStatus.usage(I18n::get))))
                .then(LiteralArgumentBuilder.<S>literal(MENU).executes(ctx -> {
                    ui.requestHome();
                    return 1;
                }))
                .then(toolCommand(ui))
                .then(RequiredArgumentBuilder.<S, String>argument(LINE, new LineArgument(ui))
                        .suggests((ctx, builder) -> suggest(ui, builder))
                        .executes(ctx -> run(ui, line(ctx)))
                        .then(RequiredArgumentBuilder.<S, String>argument(REST, new ErrorArgument())
                                .suggests((ctx, builder) -> suggest(ui, builder))));
    }

    /** {@code tool <zone|point|path|ruler|off>}: the tool wheel's tools by command. */
    private static <S> LiteralArgumentBuilder<S> toolCommand(final BridgeUi ui) {
        final LiteralArgumentBuilder<S> tool = LiteralArgumentBuilder.<S>literal(TOOL);
        for (final io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools.Tool t
                : io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools.Tool.values()) {
            tool.then(LiteralArgumentBuilder.<S>literal(t.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> {
                ui.spatial().select(t);
                return print(List.of(text("mineflayer_bridge.command.tool.on", t.label())));
            }));
        }
        return tool.then(LiteralArgumentBuilder.<S>literal("off").executes(ctx -> {
            ui.spatial().select(null);
            return print(List.of(text("mineflayer_bridge.command.tool.off")));
        }));
    }

    private static <S> String line(final CommandContext<S> ctx) {
        return ctx.getArgument(LINE, String.class);
    }

    private static List<Addressee> book(final BridgeUi ui) {
        final BridgeCore core = ui.core();
        return core == null ? List.of() : core.commands().addressees();
    }

    /** Where the bridge line starts in a command input: after the optional slash, the literal and spaces. */
    static int lineStart(final String input) {
        int i = input.startsWith("/") ? 1 : 0;
        final String name = active;
        if (input.startsWith(name, i)) {
            i += name.length();
        }
        while (i < input.length() && Character.isWhitespace(input.charAt(i))) {
            i++;
        }
        return i;
    }

    private static CompletableFuture<Suggestions> suggest(final BridgeUi ui, final SuggestionsBuilder builder) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return builder.buildFuture();
        }
        final String input = builder.getInput();
        final int start = Math.min(lineStart(input), input.length());
        final String line = input.substring(start);
        return core.commands().engine().completeAddressed(core.commands().addressees(), line, line.length(), core.commands().context())
                .thenApply(c -> toBrigadier(builder, start, c));
    }

    private static Suggestions toBrigadier(final SuggestionsBuilder builder, final int offset, final Completion c) {
        final SuggestionsBuilder b = builder.createOffset(offset + c.start());
        for (final Suggestion s : c.suggestions()) {
            final Component tooltip = tooltip(s);
            if (tooltip == null) {
                b.suggest(s.text());
            } else {
                b.suggest(s.text(), tooltip);
            }
        }
        return b.build();
    }

    /**
     * Returns a suggestion's display label and tooltip as one Brigadier tooltip, since the vanilla
     * list shows only the inserted text.
     *
     * @param s the suggestion
     * @return the tooltip, or null when it has neither
     */
    public static @Nullable Component tooltip(final Suggestion s) {
        final String display = s.display();
        final String tooltip = s.tooltip();
        if (display == null && tooltip == null) {
            return null;
        }
        final MutableComponent out = Component.empty();
        if (display != null) {
            out.append(Component.literal(display).withColor(0xFFFFFF55));
        }
        if (tooltip != null) {
            if (display != null) {
                out.append("\n");
            }
            out.append(Component.literal(tooltip));
        }
        return out;
    }

    /**
     * Validates a bridge line the way {@link #run} would parse it.
     *
     * @return null when the line is fine (or only misses something at its end), else the failure
     *     in line coordinates
     */
    static ParseResult.@Nullable Failure check(final BridgeUi ui, final String line) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        final CommandEngine.Address a = CommandEngine.address(core.commands().addressees(), line);
        if (a == null) {
            return null;
        }
        if (a.to() == null) {
            if (connected(ui, a.target().text())) {
                return null;
            }
            return new ParseResult.Failure(a.target().start(), a.target().end(), unknownName(ui, a.target().text()), null);
        }
        if (a.restStart() >= line.length()) {
            return null;
        }
        final String rest = line.substring(a.restStart());
        final CommandEngine.Parsed parsed = CommandEngine.parseAll(a.sets(), rest, core.commands().context());
        final ParseResult.Failure first = parsed.failure();
        if (first == null) {
            return null;
        }
        final String message = first.command() == null && a.sets().size() > 1
                ? text("mineflayer_bridge.command.unknown", rest.strip().split("\\s+", 2)[0]) : first.message();
        return new ParseResult.Failure(first.start() + a.restStart(), first.end() + a.restStart(), message, first.command());
    }

    /** The whole bridge line as one argument; see the class comment for how errors are placed. */
    private record LineArgument(BridgeUi ui) implements ArgumentType<String> {
        @Override
        public String parse(final StringReader reader) throws CommandSyntaxException {
            final int start = reader.getCursor();
            final String line = reader.getRemaining();
            final ParseResult.Failure f = check(ui, line);
            failedInput = null;
            failedMessage = null;
            if (f == null || (f.start() >= line.length() && f.end() >= line.length())) {
                reader.setCursor(reader.getTotalLength());
                return line;
            }
            int tokenStart = Math.min(f.start(), line.length());
            while (tokenStart > 0 && !Character.isWhitespace(line.charAt(tokenStart - 1))) {
                tokenStart--;
            }
            if (tokenStart == 0) {
                reader.setCursor(start);
                throw error(f.message()).createWithContext(reader);
            }
            failedInput = reader.getString();
            failedMessage = f.message();
            reader.setCursor(start + tokenStart - 1);
            return line.substring(0, tokenStart - 1);
        }
    }

    /** Rethrows the failure {@link LineArgument} stopped at, so Brigadier reports it at that token. */
    private record ErrorArgument() implements ArgumentType<String> {
        @Override
        public String parse(final StringReader reader) throws CommandSyntaxException {
            final String message = reader.getString().equals(failedInput) && failedMessage != null ? failedMessage : text("mineflayer_bridge.command.invalid_argument");
            throw error(message).createWithContext(reader);
        }
    }

    private static SimpleCommandExceptionType error(final String message) {
        return new SimpleCommandExceptionType(Component.literal(message));
    }

    private static int run(final BridgeUi ui, final String line) throws CommandSyntaxException {
        final BridgeCore core = ui.core();
        if (core == null) {
            return fail(text("mineflayer_bridge.endpoint.not_running.message"));
        }
        final CommandEngine.Address a = CommandEngine.address(core.commands().addressees(), line);
        if (a == null) {
            return status(ui);
        }
        final Addressee to = a.to();
        if (a.restStart() >= line.length()) {
            return list(ui, a.target().text(), to);
        }
        if (to == null) {
            return fail(unknownName(ui, a.target().text()));
        }
        final String rest = line.substring(a.restStart());
        final CommandEngine.Parsed parsed = CommandEngine.parseAll(to.commands(), rest, core.commands().context());
        final ParseResult.Help help = parsed.help();
        if (help != null) {
            final List<String> words = new ArrayList<>();
            words.add(help.command().data().name());
            words.addAll(help.path());
            return print(CommandUsage.help(words, help.node()));
        }
        final ParseResult.Failure f = parsed.failure();
        if (parsed.runs().isEmpty()) {
            if (f == null) {
                return fail(text("mineflayer_bridge.command.unknown", rest.strip().split("\\s+", 2)[0]));
            }
            if (f.command() == null) {
                return fail(to.kind() == Addressee.Kind.ALL ? text("mineflayer_bridge.command.unknown.all", rest.strip().split("\\s+", 2)[0])
                        : text("mineflayer_bridge.command.unknown.bot", f.message(), a.target().text()));
            }
            throw error(to.commands().size() > 1 ? parsed.failedIn() + ": " + f.message() : f.message()).create();
        }
        final List<CommandEngine.Run> runs = parsed.runs();
        if (runs.size() == 1) {
            final ParseResult.Invocation inv = runs.getFirst().invocation();
            ui.trackInChat(inv.command().data().name(), inv.command().ownerName(), core.commands().invoke(inv, "command"));
            return 1;
        }
        // A line that runs in several command sets reports in one toast.
        final List<CompletableFuture<String>> parts = new ArrayList<>(runs.size());
        final boolean[] allOk = {true};
        for (final CommandEngine.Run r : runs) {
            final String owner = r.set().name();
            parts.add(core.commands().invoke(r.invocation(), "command").handle((res, ex) -> {
                if (ex != null || !res.ok()) {
                    allOk[0] = false;
                }
                final String said = ex != null ? BridgeUi.describe(ex)
                        : res.message() != null && !res.message().isEmpty() ? res.message() : text(res.ok() ? "mineflayer_bridge.command.done"
                                : "mineflayer_bridge.command.failed");
                return owner + ": " + said;
            }));
        }
        final CompletableFuture<InvocationResult> combined = CompletableFuture.allOf(parts.toArray(new CompletableFuture<?>[0]))
                .thenApply(v -> {
                    final List<String> lines = new ArrayList<>(parts.size());
                    for (final CompletableFuture<String> part : parts) {
                        lines.add(part.join());
                    }
                    return new InvocationResult(allOk[0], String.join("; ", lines));
                });
        ui.trackInChat(runs.getFirst().invocation().command().data().name(), text("mineflayer_bridge.command.bots", runs.size()), combined);
        return 1;
    }

    /** {@code /bridge}: endpoint state and every connected bot. */
    private static int status(final BridgeUi ui) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return print(BridgeStatus.status(BridgeCommands::text, false, 0, List.of()));
        }
        final List<BridgeStatus.Bot> bots = new ArrayList<>();
        for (final Presence p : core.presence().all()) {
            if (!p.connected()) {
                continue;
            }
            int commands = 0;
            for (final CommandDecl c : ui.declarations().commands()) {
                if (c.ownerName().equals(p.displayName())) {
                    commands++;
                }
            }
            bots.add(new BridgeStatus.Bot(p.displayName(), core.attach().isAttached(p.identity()), core.attach().worldMatched(p.identity()),
                    commands));
        }
        return print(BridgeStatus.status(BridgeCommands::text, true, core.boundPort(), bots));
    }

    /** {@code /bridge <name>}: the command trees an address reaches, a session's own and the orders it takes. */
    private static int list(final BridgeUi ui, final String typed, final @Nullable Addressee to) {
        if (ui.core() == null) {
            return fail(text("mineflayer_bridge.endpoint.not_running.message"));
        }
        if (to == null) {
            return connected(ui, typed) ? print(List.of(text("mineflayer_bridge.status.no_commands.bot", typed))) : fail(unknownName(ui, typed));
        }
        final List<BridgeStatus.Section> sections = new ArrayList<>();
        for (final BotCommands set : to.commands()) {
            if (set.commands().isEmpty()) {
                continue;
            }
            final List<CommandData> data = new ArrayList<>(set.commands().size());
            for (final CommandDecl c : set.commands()) {
                data.add(c.data());
            }
            final String heading = set.bots().isEmpty() ? text("mineflayer_bridge.status.bot_heading", set.name())
                    : text("mineflayer_bridge.status.orders_heading", set.commands().getFirst().ownerName(), String.join(", ", set.bots()));
            sections.add(new BridgeStatus.Section(heading, data));
        }
        return print(BridgeStatus.commandList(BridgeCommands::text, to.name(), sections));
    }

    /** Names that reach at least one command, for error messages. */
    private static Set<String> addressNames(final BridgeUi ui) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Addressee a : book(ui)) {
            for (final BotCommands b : a.commands()) {
                if (!b.commands().isEmpty()) {
                    names.add(a.name());
                    break;
                }
            }
        }
        return names;
    }

    private static boolean connected(final BridgeUi ui, final String bot) {
        final BridgeCore core = ui.core();
        if (core != null) {
            for (final Presence p : core.presence().all()) {
                if (p.connected() && p.displayName().equalsIgnoreCase(bot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String unknownName(final BridgeUi ui, final String typed) {
        final Set<String> names = addressNames(ui);
        return names.isEmpty() ? text("mineflayer_bridge.command.unknown_bot.none", typed)
                : text("mineflayer_bridge.command.unknown_bot", typed, String.join(", ", names));
    }

    private static int print(final List<String> lines) {
        for (final String line : lines) {
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(line).withColor(INFO_ARGB));
        }
        return 1;
    }

    /** Problems print as a readable red client line instead of a Brigadier syntax error. */
    private static int fail(final String message) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(message).withColor(UiColors.ERROR));
        return 0;
    }
}
