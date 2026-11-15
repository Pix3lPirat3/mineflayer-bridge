package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CompletionSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The one command engine behind every entry point ({@code /bridge}, the
 * palette, {@code @bot} chat commands and form fields all use the same parser, validation and
 * completion).
 *
 * <p>A command line is {@code <command or alias> [subcommand or alias...] [args...]}: the root is
 * matched by name or alias, then child nodes by name or alias for as long as the next token names
 * one, then the node's typed positional arguments, the last of which may be {@code greedy}. Missing
 * optional arguments take their defaults. An addressed line, as typed after {@code /bridge} or the
 * chat prefix, starts with a bot name or {@code all}.
 *
 * <p>Completion offers node names and aliases, declared choices, built-in values (booleans,
 * selectors, {@code here}, {@code ~}), suggestions from the registered {@link CompletionSource}s,
 * and for {@code source: dynamic} the {@link DynamicCompleter}. Completion never blocks: the
 * returned future completes right away unless a dynamic source is involved.
 *
 * <p>Client thread only: parsing resolves selectors and completion calls client sources.
 */
public final class CommandEngine {
    /** The address word that selects every attached bot. */
    public static final String ALL = "all";

    /** The completion source for arguments that declare none and whose type has no default source. */
    public static final String NO_SOURCE = "none";

    private static final int MAX_SUGGESTIONS = 200;

    private final Map<String, CompletionSource> sources;
    private final DynamicCompleter dynamic;

    /**
     * Creates an engine.
     *
     * @param sources completion sources by name; read on every completion, so later changes to the
     *     map are seen
     * @param dynamic answers {@code source: dynamic}
     */
    public CommandEngine(Map<String, CompletionSource> sources, DynamicCompleter dynamic) {
        this.sources = sources;
        this.dynamic = dynamic;
    }

    /** Whether completion offers aliases, as well as names; off by default, they still parse. */
    private volatile boolean showAliases;

    /**
     * Sets whether completion offers command aliases. Off by default: an alias next to its own name
     * only fills the list. Typed aliases parse either way.
     *
     * @param show true to offer them once the typed text matches one
     */
    public void setShowAliases(final boolean show) {
        this.showAliases = show;
    }

    /**
     * An addressed line split at its first word.
     *
     * @param target the first token
     * @param to what it names, or null when it names nothing addressable
     * @param restStart offset of the command line after the address
     */
    public record Address(Token target, @Nullable Addressee to, int restStart) {
        /**
         * Returns the command sets the line reaches.
         *
         * @return the sets; empty when the address names nothing
         */
        public List<BotCommands> sets() {
            return to == null ? List.of() : to.commands();
        }
    }

    /**
     * Splits off the address of a line.
     *
     * @param book every addressable name, as {@link CommandControls#addressees()} lists them
     * @param line the line after {@code /bridge} or the chat prefix
     * @return the address, or null when the line has no first word
     */
    public static @Nullable Address address(List<Addressee> book, String line) {
        List<Token> tokens = Tokenizer.split(line).tokens();
        if (tokens.isEmpty()) {
            return null;
        }
        Token target = tokens.getFirst();
        int rest = target.end();
        while (rest < line.length() && Character.isWhitespace(line.charAt(rest))) {
            rest++;
        }
        return new Address(target, find(book, target.text()), rest);
    }

    /**
     * Finds an address by name, ignoring case.
     *
     * @param book every addressable name
     * @param name the typed name
     * @return the address, or null
     */
    public static @Nullable Addressee find(List<Addressee> book, String name) {
        for (Addressee a : book) {
            if (a.name().equalsIgnoreCase(name)) {
                return a;
            }
        }
        return null;
    }

    /**
     * A command line parsed against every set an address reaches.
     *
     * @param runs the invocations that parsed, each with the set it came from. Immutable.
     * @param help the first set's usage when nothing parsed and the line stops at a grouping node, or null
     * @param failure when nothing parsed and there is no help, the most telling failure: one about a
     *     command that exists wins over an unknown command; null otherwise
     * @param failedIn name of the set the failure came from, or null
     */
    public record Parsed(List<Run> runs, ParseResult.@Nullable Help help, ParseResult.@Nullable Failure failure, @Nullable String failedIn) {
        /** Copies the runs. */
        public Parsed {
            runs = List.copyOf(runs);
        }
    }

    /**
     * One invocation of an addressed line.
     *
     * @param set the command set it parsed in
     * @param invocation the invocation
     */
    public record Run(BotCommands set, ParseResult.Invocation invocation) {}

    /**
     * Parses a command line against each set; every set whose commands parse it runs it.
     *
     * @param sets the sets an address reaches
     * @param line {@code <command> [subcommands...] [args...]}
     * @param ctx the game context
     * @return the runs, or the help or failure to show
     */
    public static Parsed parseAll(List<BotCommands> sets, String line, ArgContext ctx) {
        List<Run> runs = new ArrayList<>();
        ParseResult.Help help = null;
        ParseResult.Failure failure = null;
        String failedIn = null;
        for (BotCommands b : sets) {
            switch (parse(b, line, ctx)) {
                case ParseResult.Invocation inv -> runs.add(new Run(b, inv));
                case ParseResult.Help h -> {
                    if (help == null) {
                        help = h;
                    }
                }
                case ParseResult.Failure f -> {
                    if (failure == null || (failure.command() == null && f.command() != null)) {
                        failure = f;
                        failedIn = b.name();
                    }
                }
            }
        }
        if (!runs.isEmpty()) {
            return new Parsed(runs, null, null, null);
        }
        return help != null ? new Parsed(runs, help, null, null) : new Parsed(runs, null, failure, failedIn);
    }

    /**
     * Parses one bot's command line.
     *
     * @param bot the bot whose commands the line names
     * @param line {@code <command> [subcommands...] [args...]}
     * @param ctx the game context, for coordinates, ids and selectors
     * @return an invocation, a help request, or a failure with the offending range
     */
    public static ParseResult parse(BotCommands bot, String line, ArgContext ctx) {
        List<Token> tokens = Tokenizer.split(line).tokens();
        if (tokens.isEmpty()) {
            return new ParseResult.Failure(0, line.length(), "Type a command for " + bot.name(), null);
        }
        Token head = tokens.getFirst();
        CommandDecl c = bot.command(head.text());
        if (c == null) {
            return new ParseResult.Failure(head.start(), head.end(),
                    bot.name() + " has no command '" + head.text() + "'", null);
        }
        Descent walk = descend(c, tokens);
        TreeNode node = walk.node();
        List<String> path = walk.path();
        int i = walk.next();
        if (i == tokens.size() && !node.children().isEmpty() && (!node.runnable() || node.hasRequiredArgs())) {
            return new ParseResult.Help(c, path, node);
        }
        if (!node.runnable()) {
            if (i < tokens.size()) {
                Token t = tokens.get(i);
                return new ParseResult.Failure(t.start(), t.end(), "Unknown subcommand '" + t.text() + "' of '"
                        + words(c, path) + "'" + expected(node), c);
            }
            return new ParseResult.Help(c, path, node);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (CommandArg a : node.args()) {
            if (i >= tokens.size()) {
                if (TreeNode.required(a)) {
                    String also = args.isEmpty() && !node.children().isEmpty() ? " or a subcommand" + expected(node) : "";
                    return new ParseResult.Failure(line.length(), line.length(),
                            "Missing argument '" + a.name() + "'" + also, c);
                }
                Object def = a.defaultValue();
                if (def != null) {
                    args.put(a.name(), def);
                }
                continue;
            }
            int width = ArgValues.width(a, tokens, i);
            if (i + width > tokens.size()) {
                return new ParseResult.Failure(tokens.get(i).start(), line.length(),
                        "'" + a.name() + "' needs x, y and z", c);
            }
            List<Token> parts = tokens.subList(i, i + width);
            try {
                args.put(a.name(), ArgValues.parse(a, parts, raw(line, parts), ctx, bot));
            } catch (ArgException e) {
                return new ParseResult.Failure(e.start(), e.end(), e.getMessage(), c);
            }
            i += width;
        }
        if (i < tokens.size()) {
            Token first = tokens.get(i);
            Token last = tokens.getLast();
            String extra = line.substring(first.start(), last.end());
            String also = node.args().isEmpty() && !node.children().isEmpty()
                    ? "; '" + first.text() + "' is not a subcommand" + expected(node) : "";
            return new ParseResult.Failure(first.start(), last.end(), "Too many arguments: " + extra + also, c);
        }
        return new ParseResult.Invocation(c, path, node, args, bot.bots());
    }

    /** Where a line's leading subcommand words lead: the node, its canonical path, and the next token index. */
    private record Descent(TreeNode node, List<String> path, int next) {}

    /** Follows tokens after the command name while they name subcommands. */
    private static Descent descend(CommandDecl c, List<Token> tokens) {
        TreeNode node = TreeNode.of(c.data());
        List<String> path = new ArrayList<>();
        int i = 1;
        while (i < tokens.size() && !node.children().isEmpty()) {
            TreeNode child = node.child(tokens.get(i).text());
            if (child == null) {
                break;
            }
            node = child;
            path.add(child.name());
            i++;
        }
        return new Descent(node, path, i);
    }

    /**
     * Parses argument form input for one node through the same conversions as typed lines.
     *
     * @param bot the bot that owns the command
     * @param command the command declaration
     * @param path canonical subcommand names of the node
     * @param raw argument name to raw input: a {@code String}, a {@code Boolean}, or a list of three
     *     strings for positions; blank strings mean not given
     * @param ctx the game context
     * @return an invocation, or a failure whose message names the argument (its range is 0)
     */
    public static ParseResult parseFields(BotCommands bot, CommandDecl command, List<String> path,
            Map<String, ?> raw, ArgContext ctx) {
        TreeNode node = TreeNode.of(command.data()).at(path);
        if (node == null) {
            return new ParseResult.Failure(0, 0, "Unknown subcommand " + String.join(" ", path), command);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (CommandArg a : node.args()) {
            String text = fieldText(raw.get(a.name()));
            List<Token> parts = Tokenizer.split(text).tokens();
            if (parts.isEmpty()) {
                if (TreeNode.required(a)) {
                    return new ParseResult.Failure(0, 0, "'" + a.name() + "' is required", command);
                }
                Object def = a.defaultValue();
                if (def != null) {
                    args.put(a.name(), def);
                }
                continue;
            }
            int width = ArgValues.width(a, parts, 0);
            if (a.type() != ArgType.GREEDY && parts.size() != width) {
                return new ParseResult.Failure(0, 0, ArgValues.isPosition(a) ? "'" + a.name() + "' needs x, y and z"
                        : "'" + a.name() + "' takes one value; quote text with spaces", command);
            }
            try {
                args.put(a.name(), ArgValues.parse(a, parts, a.type() == ArgType.GREEDY ? text.strip() : text, ctx, bot));
            } catch (ArgException e) {
                return new ParseResult.Failure(0, 0, e.getMessage(), command);
            }
        }
        return new ParseResult.Invocation(command, path, node, args, bot.bots());
    }

    private static String fieldText(@Nullable Object input) {
        if (input instanceof List<?> list) {
            List<String> parts = new ArrayList<>(list.size());
            boolean blank = true;
            for (Object o : list) {
                String s = o == null ? "" : o.toString().strip();
                blank &= s.isEmpty();
                parts.add(s.isEmpty() ? "~" : s);
            }
            if (blank) {
                return "";
            }
            if (!parts.isEmpty() && ArgValues.single(parts.getFirst())) {
                return parts.getFirst();
            }
            return String.join(" ", parts);
        }
        return input == null ? "" : input.toString();
    }

    private static String raw(String line, List<Token> parts) {
        return parts.size() == 1 ? parts.getFirst().text() : line.substring(parts.getFirst().start(), parts.getLast().end());
    }

    private static String words(CommandDecl c, List<String> path) {
        return path.isEmpty() ? c.data().name() : c.data().name() + " " + String.join(" ", path);
    }

    private static String expected(TreeNode node) {
        List<String> names = new ArrayList<>(node.children().size());
        for (TreeNode c : node.children()) {
            names.add(c.name());
        }
        return names.isEmpty() ? "" : "; expected " + String.join(", ", names);
    }

    /**
     * Returns the completion spec an argument uses: its own, or the default source of its type.
     *
     * @param arg the declaration
     * @return the completion spec; source {@link #NO_SOURCE} when only built-in values apply
     */
    public static CompletionSpec specOf(CommandArg arg) {
        CompletionSpec own = arg.completion();
        if (own != null) {
            return own;
        }
        String source = switch (arg.type()) {
            case CHOICE -> "choices";
            case BLOCK_ID -> "block_ids";
            case ITEM_ID -> "item_ids";
            case ENTITY, ENTITY_LIST -> "entities";
            case PLAYER -> "players";
            case BOT -> "bots";
            case ZONE -> "zones";
            case POINT -> "points";
            case BLOCK_POS, VEC3 -> "blocks";
            case STRING, GREEDY, INT, FLOAT, BOOL -> NO_SOURCE;
        };
        return new CompletionSpec(source);
    }

    /**
     * Completes an addressed line: addressable names for the first word, then the command line of
     * what it names. When a name reaches several command sets, command names of all of them are
     * offered and arguments come from the first set that has the command.
     *
     * @param book every addressable name
     * @param line the line after {@code /bridge} or the chat prefix
     * @param cursor the cursor offset in {@code line}
     * @param ctx the game context
     * @return the completion, in {@code line} coordinates; never fails
     */
    public CompletableFuture<Completion> completeAddressed(List<Addressee> book, String line, int cursor, ArgContext ctx) {
        String prefix = line.substring(0, cursor);
        Tokenizer.Tokens tk = Tokenizer.split(prefix);
        if (tk.tokens().isEmpty() || (tk.tokens().size() == 1 && !tk.trailingSpace())) {
            Token current = tk.tokens().isEmpty() ? new Token("", cursor, cursor) : tk.tokens().getFirst();
            List<Suggestion> out = new ArrayList<>();
            for (Addressee a : book) {
                if (matches(a.name(), current.text())) {
                    out.add(new Suggestion(Tokenizer.quote(a.name()), null, tooltip(a)));
                }
            }
            return CompletableFuture.completedFuture(new Completion(current.start(), cursor, out));
        }
        Address a = address(book, line);
        if (a == null || a.sets().isEmpty() || cursor < a.restStart()) {
            return CompletableFuture.completedFuture(Completion.none(cursor));
        }
        List<BotCommands> sets = a.sets();
        String rest = line.substring(a.restStart());
        int restCursor = cursor - a.restStart();
        if (sets.size() == 1) {
            return complete(sets.getFirst(), rest, restCursor, ctx).thenApply(c -> c.shifted(a.restStart()));
        }
        List<Token> restTokens = Tokenizer.split(rest.substring(0, restCursor)).tokens();
        boolean rootWord = restTokens.isEmpty() || (restTokens.size() == 1 && !Tokenizer.split(rest.substring(0, restCursor)).trailingSpace());
        if (rootWord) {
            Token current = restTokens.isEmpty() ? new Token("", restCursor, restCursor) : restTokens.getFirst();
            Set<String> seen = new LinkedHashSet<>();
            List<Suggestion> out = new ArrayList<>();
            for (BotCommands b : sets) {
                for (Suggestion s : roots(b, current.text())) {
                    if (seen.add(s.text())) {
                        out.add(s);
                    }
                }
            }
            return CompletableFuture.completedFuture(new Completion(current.start(), restCursor, out).shifted(a.restStart()));
        }
        for (BotCommands b : sets) {
            if (b.command(restTokens.getFirst().text()) != null) {
                return complete(b, rest, restCursor, ctx).thenApply(c -> c.shifted(a.restStart()));
            }
        }
        return CompletableFuture.completedFuture(Completion.none(cursor));
    }

    /** What an address suggestion says about the name. */
    private static String tooltip(Addressee a) {
        return switch (a.kind()) {
            case ALL -> "Every attached bot";
            case GROUP -> "Group: " + String.join(", ", a.members());
            case BOT, CONTROLLER -> {
                int n = 0;
                for (BotCommands b : a.commands()) {
                    n += b.commands().size();
                }
                yield n == 1 ? "1 command" : n + " commands";
            }
        };
    }

    /**
     * Completes one bot's command line at the cursor.
     *
     * @param bot the bot whose commands the line names
     * @param line {@code <command> [subcommands...] [args...]}
     * @param cursor the cursor offset in {@code line}; text after it is ignored
     * @param ctx the game context
     * @return the token range to replace and the suggestions; completes right away unless a
     *     dynamic source is asked; never fails
     */
    public CompletableFuture<Completion> complete(BotCommands bot, String line, int cursor, ArgContext ctx) {
        String prefix = line.substring(0, Math.min(cursor, line.length()));
        Tokenizer.Tokens tk = Tokenizer.split(prefix);
        List<Token> tokens = tk.tokens();
        boolean fresh = tokens.isEmpty() || tk.trailingSpace();
        Token current = fresh ? new Token("", cursor, cursor) : tokens.getLast();
        List<Token> done = fresh ? tokens : tokens.subList(0, tokens.size() - 1);
        if (done.isEmpty()) {
            return CompletableFuture.completedFuture(new Completion(current.start(), cursor, roots(bot, current.text())));
        }
        CommandDecl c = bot.command(done.getFirst().text());
        if (c == null) {
            return CompletableFuture.completedFuture(Completion.none(cursor));
        }
        Descent walk = descend(c, done);
        TreeNode node = walk.node();
        List<String> path = walk.path();
        int i = walk.next();
        List<Suggestion> names = new ArrayList<>();
        if (i == done.size()) {
            for (TreeNode child : node.children()) {
                nodeSuggestions(child, current.text(), names);
            }
        }
        if (!node.runnable() || node.args().isEmpty()) {
            return CompletableFuture.completedFuture(new Completion(current.start(), cursor, names));
        }
        Slot slot = locate(node.args(), done, i);
        if (slot == null) {
            return CompletableFuture.completedFuture(new Completion(current.start(), cursor, names));
        }
        CommandArg arg = node.args().get(slot.arg());
        Map<String, Object> earlier = earlier(bot, node, done, i, slot.arg(), line, ctx);
        int start = current.start();
        String input = current.text();
        if (arg.type() == ArgType.GREEDY) {
            start = slot.token() < done.size() ? done.get(slot.token()).start() : current.start();
            input = prefix.substring(start);
        }
        final int from = start;
        return completeArg(bot, c, path, arg, input, slot.component(), earlier, ctx).thenApply(values -> {
            List<Suggestion> all = new ArrayList<>(names);
            all.addAll(values);
            return new Completion(from, cursor, all);
        });
    }

    /**
     * Suggests values for one argument, as the argument form's dropdown asks.
     *
     * @param bot the bot that owns the command
     * @param command the command declaration
     * @param path canonical subcommand names of the node
     * @param arg the argument
     * @param input what was typed for it so far
     * @param component 0, 1 or 2 for the x, y or z part of a position, else 0
     * @param args earlier arguments, already parsed
     * @param ctx the game context
     * @return the suggestions: source results first, then built-in values; never fails
     */
    public CompletableFuture<List<Suggestion>> completeArg(BotCommands bot, CommandDecl command, List<String> path,
            CommandArg arg, String input, int component, Map<String, Object> args, ArgContext ctx) {
        if (arg.type() == ArgType.ENTITY_LIST) {
            List<Token> entries = ArgValues.listEntries(new Token(input, 0, input.length()));
            Token last = entries.getLast();
            if (last.start() > 0) {
                String head = input.substring(0, last.start());
                return entryValues(bot, command, path, arg, last.text(), 0, args, ctx).thenApply(list -> {
                    List<Suggestion> out = new ArrayList<>(list.size());
                    for (Suggestion s : list) {
                        out.add(new Suggestion(head + s.text(), s.display() != null ? s.display() : s.text(), s.tooltip()));
                    }
                    return out;
                });
            }
        }
        return entryValues(bot, command, path, arg, input, component, args, ctx);
    }

    private CompletableFuture<List<Suggestion>> entryValues(BotCommands bot, CommandDecl command, List<String> path,
            CommandArg arg, String input, int component, Map<String, Object> args, ArgContext ctx) {
        CompletionSpec spec = specOf(arg);
        List<Suggestion> builtins = builtins(arg, input, component);
        CompletionQuery q = new CompletionQuery(bot, command, path, arg, spec, input, component, args, ctx);
        if (spec.source().equals("dynamic")) {
            return dynamic.complete(command.key(), path, arg.name(), input, q.args()).handle((list, error) -> {
                List<Suggestion> out = new ArrayList<>(list == null ? List.of() : list);
                out.addAll(builtins);
                return cap(out);
            });
        }
        List<Suggestion> out = new ArrayList<>();
        if (spec.source().equals("choices")) {
            List<String> choices = arg.choices();
            if (choices != null && arg.type() != ArgType.CHOICE) {
                for (String choice : choices) {
                    if (matches(choice, input)) {
                        out.add(new Suggestion(quoted(arg, choice)));
                    }
                }
            }
        } else {
            CompletionSource source = sources.get(spec.source());
            if (source != null) {
                out.addAll(source.suggest(q));
            }
        }
        out.addAll(builtins);
        return CompletableFuture.completedFuture(cap(out));
    }

    private static List<Suggestion> cap(List<Suggestion> list) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> out = new ArrayList<>(Math.min(list.size(), MAX_SUGGESTIONS));
        for (Suggestion s : list) {
            if (out.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (seen.add(s.text())) {
                out.add(s);
            }
        }
        return out;
    }

    private static String quoted(CommandArg arg, String value) {
        return arg.type() == ArgType.GREEDY ? value : Tokenizer.quote(value);
    }

    /** Built-in values for a type: booleans, choices, selectors, {@code here} and {@code ~}. */
    private static List<Suggestion> builtins(CommandArg arg, String input, int component) {
        List<String> values = new ArrayList<>();
        switch (arg.type()) {
            case BOOL -> {
                values.add("true");
                values.add("false");
            }
            case CHOICE -> {
                List<String> choices = arg.choices();
                if (choices != null) {
                    values.addAll(choices);
                }
            }
            case BLOCK_POS, VEC3 -> {
                if (component == 0) {
                    values.add(ArgValues.HERE);
                    values.add("~ ~ ~");
                    values.add("@me");
                    values.add("@self");
                    values.add("@looked");
                    values.add("@nearest");
                } else {
                    values.add("~");
                }
            }
            case ENTITY -> {
                values.add("@me");
                values.add("@self");
                values.add("@looked");
                values.add("@nearest");
            }
            case ENTITY_LIST -> {
                values.add("@all");
                values.add("@nearest");
                values.add("@me");
                values.add("@self");
                values.add("@looked");
            }
            case STRING, GREEDY, INT, FLOAT, BLOCK_ID, ITEM_ID, PLAYER, BOT, ZONE, POINT -> { }
        }
        List<Suggestion> out = new ArrayList<>();
        boolean selectorsAllowed = arg.type() == ArgType.ENTITY || arg.type() == ArgType.ENTITY_LIST
                || (ArgValues.isPosition(arg) && component == 0);
        if (selectorsAllowed && Selector.looksLike(input) && input.indexOf('[') >= 0) {
            selectorOptions(input, out);
            return out;
        }
        for (String v : values) {
            if (matches(v, input)) {
                out.add(new Suggestion(v.indexOf(' ') >= 0 && arg.type() != ArgType.CHOICE ? v : quoted(arg, v), null,
                        Selector.looksLike(v) ? selectorHelp(v) : null));
            }
        }
        Object def = arg.defaultValue();
        if (out.isEmpty() && input.isEmpty() && def != null) {
            String text = def instanceof Number n ? ArgValues.plain(n.doubleValue()) : String.valueOf(def);
            out.add(new Suggestion(quoted(arg, text), null, "Default"));
        }
        return out;
    }

    private static String selectorHelp(String selector) {
        return switch (selector) {
            case "@me" -> "You";
            case "@self" -> "The bot itself";
            case "@looked" -> "What the crosshair is on";
            case "@nearest" -> "The nearest entity; add [type=..,tag=..,distance=..N,from=bot]";
            default -> "Every matching entity; add [type=..,tag=..,distance=..N,from=bot,limit=N]";
        };
    }

    /** Option keys after {@code [} or {@code ,} inside a selector being typed. */
    private static void selectorOptions(String input, List<Suggestion> out) {
        if (input.endsWith("]")) {
            return;
        }
        int cut = Math.max(input.lastIndexOf('['), input.lastIndexOf(','));
        String head = input.substring(0, cut + 1);
        String typed = input.substring(cut + 1);
        if (typed.indexOf('=') >= 0) {
            if (typed.toLowerCase(Locale.ROOT).startsWith("from=")) {
                for (String v : List.of("player", "bot")) {
                    if (matches("from=" + v, typed)) {
                        out.add(new Suggestion(head + "from=" + v));
                    }
                }
            }
            out.add(new Suggestion(input + "]", null, "Close the selector"));
            return;
        }
        for (String key : Selector.OPTIONS) {
            if (matches(key, typed)) {
                out.add(new Suggestion(head + key + "="));
            }
        }
    }

    private List<Suggestion> roots(BotCommands bot, String typed) {
        List<Suggestion> out = new ArrayList<>();
        for (CommandDecl c : bot.commands()) {
            nodeSuggestions(TreeNode.of(c.data()), typed, out);
        }
        return out;
    }

    private void nodeSuggestions(TreeNode node, String typed, List<Suggestion> out) {
        String usage = CommandUsage.args(node);
        String tooltip = node.description() == null ? (usage.isEmpty() ? null : usage)
                : usage.isEmpty() ? node.description() : node.description() + "\n" + usage;
        if (matches(node.name(), typed)) {
            out.add(new Suggestion(node.name(), null, tooltip));
        }
        for (String alias : node.aliases()) {
            if (showAliases && matches(alias, typed) && !typed.isEmpty()) {
                out.add(new Suggestion(alias, alias + " (" + node.name() + ")", tooltip));
            }
        }
    }

    /** Whether a candidate starts with the typed text, or its path after the namespace does. */
    static boolean matches(String candidate, String typed) {
        String c = candidate.toLowerCase(Locale.ROOT);
        String t = typed.toLowerCase(Locale.ROOT);
        if (c.startsWith(t)) {
            return true;
        }
        int colon = c.indexOf(':');
        return colon >= 0 && !t.isEmpty() && c.startsWith(t, colon + 1);
    }

    /**
     * Where the token being typed falls among a node's arguments.
     *
     * @param arg index of the argument
     * @param component 0, 1 or 2 within a position
     * @param token index into the finished tokens of the argument's first token
     */
    private record Slot(int arg, int component, int token) {}

    private static @Nullable Slot locate(List<CommandArg> args, List<Token> done, int from) {
        int idx = from;
        for (int k = 0; k < args.size(); k++) {
            CommandArg a = args.get(k);
            if (a.type() == ArgType.GREEDY) {
                return new Slot(k, 0, idx);
            }
            if (idx >= done.size()) {
                return new Slot(k, 0, idx);
            }
            if (ArgValues.isPosition(a)) {
                if (ArgValues.single(done.get(idx).text())) {
                    idx++;
                    continue;
                }
                int remaining = done.size() - idx;
                if (remaining < 3) {
                    return new Slot(k, remaining, idx);
                }
                idx += 3;
                continue;
            }
            idx++;
        }
        return null;
    }

    /** Best-effort values of the finished arguments before {@code upTo}, for dynamic sources. */
    private static Map<String, Object> earlier(BotCommands bot, TreeNode node, List<Token> done, int from, int upTo,
            String line, ArgContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        int idx = from;
        for (int k = 0; k < upTo; k++) {
            CommandArg a = node.args().get(k);
            int width = ArgValues.width(a, done, idx);
            if (idx + width > done.size()) {
                break;
            }
            List<Token> parts = done.subList(idx, idx + width);
            try {
                out.put(a.name(), ArgValues.parse(a, parts, raw(line, parts), ctx, bot));
            } catch (ArgException e) {
                out.remove(a.name());
            }
            idx += width;
        }
        return out;
    }
}
