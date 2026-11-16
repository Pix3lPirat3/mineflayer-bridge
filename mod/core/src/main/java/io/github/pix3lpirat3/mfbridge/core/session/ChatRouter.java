package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.chat.BridgeClick;
import io.github.pix3lpirat3.mfbridge.core.decl.CrewDecl;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatControls;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.command.Addressee;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandEngine;
import io.github.pix3lpirat3.mfbridge.core.command.CommandUsage;
import io.github.pix3lpirat3.mfbridge.core.command.Completion;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.util.TextLimits;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** The chat bridge behind {@link ChatControls}. */
@ClientThread
final class ChatRouter implements ChatControls {
    static final String CHAT_SURFACE = "chat";

    private final SessionHost host;
    private final SurfaceGate gate;
    private final InputQueue input;
    private final Interactions interactions;
    private final CommandService commands;
    private final EventBus events;
    private String prefix = DEFAULT_PREFIX;

    ChatRouter(SessionHost host, InputQueue input, Interactions interactions, CommandService commands, EventBus events,
            SurfaceGate gate) {
        this.host = host;
        this.gate = gate;
        this.input = input;
        this.interactions = interactions;
        this.commands = commands;
        this.events = events;
    }

    @Override
    public String prefix() {
        return prefix;
    }

    @Override
    public void setPrefix(String prefix) {
        if (prefix.isEmpty() || whitespaceAt(prefix) >= 0) {
            throw new IllegalArgumentException("the chat prefix must be one or more characters without spaces");
        }
        this.prefix = prefix;
    }

    @Override
    public ChatRoute route(String line) {
        ChatRoute answered = answerPrompt(line);
        if (answered != null) {
            return answered;
        }
        if (!line.startsWith(prefix)) {
            return new ChatRoute.PassThrough(line);
        }
        String rest = line.substring(prefix.length());
        if (rest.startsWith(prefix)) {
            return new ChatRoute.PassThrough(rest);
        }
        int space = whitespaceAt(rest);
        String name = space < 0 ? rest : rest.substring(0, space);
        String text = space < 0 ? "" : rest.substring(space).strip();
        if (name.isEmpty()) {
            return new ChatRoute.PassThrough(line);
        }
        Addressee to = CommandEngine.find(commands.addressees(), name);
        if (to == null) {
            String closest = closestName(name);
            if (closest != null) {
                return new ChatRoute.Mistyped(name, closest, prefix + closest + rest.substring(name.length()), prefix + line);
            }
            // A bot the bridge knows is meant, even when it cannot be reached: never send it as chat.
            String why = commands.unreachable(name);
            if (why != null) {
                return new ChatRoute.Unknown(why);
            }
            return new ChatRoute.PassThrough(line);
        }
        Addressee permitted = permitted(to);
        if (permitted.commands().isEmpty() && permitted.chatOwners().isEmpty()) {
            return new ChatRoute.Unknown(name + " runs on a device without the chat permission");
        }
        if (text.isEmpty()) {
            return new ChatRoute.Unknown("Type a message or command after " + prefix + name);
        }
        return deliver(line, text, permitted);
    }

    /** The address with only the command sets and chat owners whose sessions hold the chat permission. */
    private Addressee permitted(Addressee to) {
        List<BotCommands> sets = new ArrayList<>();
        for (BotCommands b : to.commands()) {
            if (chatAllowed(b.owner())) {
                sets.add(b);
            }
        }
        List<String> owners = new ArrayList<>();
        for (String o : to.chatOwners()) {
            if (chatAllowed(o)) {
                owners.add(o);
            }
        }
        return new Addressee(to.name(), to.kind(), sets, to.members(), owners, to.elsewhere());
    }

    private boolean chatAllowed(String owner) {
        Session s = host.writer(owner);
        return s != null && host.permitted(s, Permission.CHAT);
    }

    /**
     * The addressable name a typed one is most likely a typo of: one edit away (two from six letters
     * on), or one starting with the other. Null when none is that close, so names of players and
     * other chat go to the server. {@code all} is never suggested.
     */
    private @Nullable String closestName(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Addressee a : commands.addressees()) {
            if (a.kind() == Addressee.Kind.ALL) {
                continue;
            }
            String known = a.name().toLowerCase(Locale.ROOT);
            int d = editDistance(lower, known);
            boolean close = d <= (lower.length() >= 6 ? 2 : 1) && d < lower.length() || known.length() >= 3 && lower.startsWith(known) || lower.length() >= 2 && known.startsWith(lower);
            if (close && d < bestDistance) {
                best = a.name();
                bestDistance = d;
            }
        }
        return best;
    }

    /** Levenshtein distance; names are short. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private ChatRoute deliver(String line, String text, Addressee to) {
        CommandEngine.Parsed parsed = CommandEngine.parseAll(to.commands(), text, commands.context());
        if (!parsed.runs().isEmpty()) {
            List<ChatRoute.Run> runs = new ArrayList<>();
            for (CommandEngine.Run r : parsed.runs()) {
                runs.add(new ChatRoute.Run(r.set().name(), commands.invoke(r.invocation(), "chat")));
            }
            return new ChatRoute.CapturedCommand(parsed.runs().getFirst().invocation().command().data().name(), runs);
        }
        ParseResult.Help h = parsed.help();
        if (h != null) {
            return new ChatRoute.CapturedHelp(to.name(), CommandUsage.help(words(h), h.node()));
        }
        ParseResult.Failure f = parsed.failure();
        String commandError = f == null || f.command() == null ? null : f.command().data().name() + ": " + f.message();
        List<String> names = new ArrayList<>(to.chatOwners().size());
        for (String owner : to.chatOwners()) {
            Session s = host.writer(owner);
            if (s != null) {
                // The player spoke to it, so what it opens next was asked for.
                gate.asked(s.owner, host.nowMs());
                host.sendReliable(s, CoreMessages.CHAT_MESSAGE, new ChatMessage(text, line, to.broadcast()), 0, Envelope.NONE);
                names.add(s.displayName);
            }
        }
        return new ChatRoute.CapturedMessage(names, commandError);
    }

    private static List<String> words(ParseResult.Help h) {
        List<String> words = new ArrayList<>();
        words.add(h.command().data().name());
        words.addAll(h.path());
        return words;
    }

    @Override
    public @Nullable CompletableFuture<Completion> complete(String line, int cursor) {
        String rest = cursor < prefix.length() ? null : addressed(line);
        if (rest == null) {
            return null;
        }
        int restCursor = cursor - prefix.length();
        List<Addressee> book = book();
        CommandEngine.Address a = CommandEngine.address(book, rest);
        boolean typingName = a == null || restCursor <= a.target().end();
        if (typingName) {
            String typed = a == null ? "" : rest.substring(a.target().start(), Math.min(restCursor, a.target().end()));
            if (!anyNameStartsWith(book, typed)) {
                return null;
            }
        } else if (a.sets().isEmpty()) {
            return null;
        }
        return commands.engine().completeAddressed(book, rest, restCursor, commands.context())
                .thenApply(c -> c.shifted(prefix.length()));
    }

    @Override
    public @Nullable ParseResult validate(String line) {
        String rest = addressed(line);
        if (rest == null) {
            return null;
        }
        CommandEngine.Address a = CommandEngine.address(book(), rest);
        if (a == null || a.sets().isEmpty() || a.restStart() >= rest.length()) {
            return null;
        }
        String text = rest.substring(a.restStart());
        int offset = prefix.length() + a.restStart();
        ParseResult first = null;
        for (BotCommands b : a.sets()) {
            ParseResult r = CommandEngine.parse(b, text, commands.context());
            if (r instanceof ParseResult.Failure f && f.command() == null) {
                continue;
            }
            if (first == null || r instanceof ParseResult.Invocation) {
                first = r;
            }
            if (r instanceof ParseResult.Invocation) {
                break;
            }
        }
        if (first instanceof ParseResult.Failure f) {
            return new ParseResult.Failure(f.start() + offset, f.end() + offset, f.message(), f.command());
        }
        return first;
    }

    /** The address book, each entry holding only what the chat permission allows. */
    private List<Addressee> book() {
        List<Addressee> out = new ArrayList<>();
        for (Addressee a : commands.addressees()) {
            Addressee p = permitted(a);
            if (!p.commands().isEmpty() || !p.chatOwners().isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean anyNameStartsWith(List<Addressee> book, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        for (Addressee a : book) {
            if (a.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the text after the prefix of a line that addresses bots, or null while a chat prompt is
     * active, without the prefix, or when a doubled prefix escapes it.
     */
    private @Nullable String addressed(String line) {
        if (activeChatPrompt() || !line.startsWith(prefix)) {
            return null;
        }
        String rest = line.substring(prefix.length());
        return rest.startsWith(prefix) ? null : rest;
    }

    private boolean activeChatPrompt() {
        InputRequest active = input.active();
        return active instanceof PromptInput p && CHAT_SURFACE.equals(p.request().surface());
    }

    @Override
    public CompletableFuture<InvocationResult> runCommandLine(String owner, String line) {
        Session target = null;
        for (Session s : host.liveBots()) {
            if (s.owner.equals(owner)) {
                target = s;
            }
        }
        if (target == null) {
            return CompletableFuture.failedFuture(new BridgeException("not_attached", owner + " is not a connected bot"));
        }
        BotCommands bot = commands.of(target);
        return switch (CommandEngine.parse(bot, line.strip(), commands.context())) {
            case ParseResult.Invocation inv -> interactions.invokeCommand(inv.command().key(), inv.path(), inv.args(), "chat");
            case ParseResult.Help h -> CompletableFuture.failedFuture(new BridgeException("invalid_message",
                    String.join(" ", words(h)) + ": choose a subcommand: " + String.join("; ", CommandUsage.help(words(h), h.node()))));
            case ParseResult.Failure f -> CompletableFuture.failedFuture(new BridgeException("invalid_message",
                    f.command() == null ? f.message() : f.command().data().name() + ": " + f.message()));
        };
    }

    @Override
    public BridgeClick describeBridgeClick(String owner, String value) {
        return bridgeClick(owner, value, false);
    }

    @Override
    public BridgeClick runBridgeClick(String owner, String value) {
        return bridgeClick(owner, value, true);
    }

    private BridgeClick bridgeClick(String owner, String value, boolean run) {
        String v = value.strip();
        if (v.equals("menu")) {
            return new BridgeClick(BridgeClick.Kind.MENU, null);
        }
        if (v.equals("settings")) {
            return new BridgeClick(BridgeClick.Kind.SETTINGS, null);
        }
        int colon = v.indexOf(':');
        String verb = colon < 0 ? v : v.substring(0, colon);
        if (!verb.equals("attach") && !verb.equals("detach")) {
            throw new BridgeException("invalid_message", "a bridge click is attach, detach, menu or settings, not " + value);
        }
        Session target = colon < 0 ? ownBot(owner) : crewBot(owner, v.substring(colon + 1).strip());
        boolean attach = verb.equals("attach");
        if (run) {
            host.chooseAttached(target, attach);
        }
        return new BridgeClick(attach ? BridgeClick.Kind.ATTACHED : BridgeClick.Kind.DETACHED, target.displayName);
    }

    /** The printing session itself, which must be a bot. */
    private Session ownBot(String owner) {
        for (Session s : host.liveBots()) {
            if (s.owner.equals(owner)) {
                return s;
            }
        }
        throw new BridgeException("invalid_message", "only a bot can offer to attach itself; a controller names a bot of its crew");
    }

    /** A connected bot by name, which the printing session may offer: itself, or one of its crew. */
    private Session crewBot(String owner, String name) {
        Session found = null;
        for (Session s : host.liveBots()) {
            if (s.displayName.equalsIgnoreCase(name)) {
                found = s;
            }
        }
        if (found == null) {
            throw new BridgeException("invalid_message", name + " is not a connected bot");
        }
        if (found.owner.equals(owner)) {
            return found;
        }
        for (CrewDecl crew : host.declarations().crews()) {
            if (!crew.owner().equals(owner)) {
                continue;
            }
            for (String bot : crew.data().bots()) {
                if (bot.equalsIgnoreCase(name)) {
                    return found;
                }
            }
        }
        throw new BridgeException("not_owner", name + " is not in the crew of the session that offered it");
    }

    /** Surface {@code chat} takes the next line for text and confirm prompts. */
    private @Nullable ChatRoute answerPrompt(String line) {
        InputRequest active = input.active();
        if (!(active instanceof PromptInput p) || !CHAT_SURFACE.equals(p.request().surface())) {
            return null;
        }
        long id = p.id();
        switch (p.request().kind()) {
            case TEXT -> {
                try {
                    input.resolvePrompt(id, line, "chat");
                } catch (IllegalArgumentException e) {
                    return new ChatRoute.CapturedPrompt(id, e.getMessage());
                }
                return new ChatRoute.CapturedPrompt(id, null);
            }
            case CONFIRM -> {
                Boolean answer = yesNo(line);
                if (answer == null) {
                    return new ChatRoute.CapturedPrompt(id, "Answer yes or no");
                }
                input.resolvePrompt(id, answer, "chat");
                return new ChatRoute.CapturedPrompt(id, null);
            }
            case CHOICE, NUMBER, LINES, PAGES -> {
                return null;
            }
        }
        return null;
    }

    /** Parses a confirm answer typed in chat, or returns null. */
    static @Nullable Boolean yesNo(String line) {
        return switch (line.strip().toLowerCase(Locale.ROOT)) {
            case "yes", "y" -> Boolean.TRUE;
            case "no", "n" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** A bot printed a client-only chat line; the {@code chat} permission is off for remote devices by default. */
    void print(Session s, io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint m) {
        host.requirePermission(s, Permission.CHAT);
        if (!host.attachedNow(s) || m.spans().isEmpty()) {
            return;
        }
        int length = 0;
        for (var span : m.spans()) {
            length += span.text().length();
        }
        if (length > TextLimits.MAX_CHARS) {
            throw new BridgeException("invalid_message", "a chat line takes at most " + TextLimits.MAX_CHARS + " characters");
        }
        if (events.hasListeners(ChatPrint.TYPE)) {
            events.post(ChatPrint.TYPE, new ChatPrint(s.displayName, s.identity, s.owner, m.spans()));
        }
    }

    private static int whitespaceAt(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
