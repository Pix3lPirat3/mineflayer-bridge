package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.command.Addressee;
import io.github.pix3lpirat3.mfbridge.core.command.ArgContext;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandControls;
import io.github.pix3lpirat3.mfbridge.core.command.CommandEngine;
import io.github.pix3lpirat3.mfbridge.core.command.CompletionSource;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.CrewDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandComplete;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.CrewGroup;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** The command engine service behind {@link CommandControls}, with dynamic completion. */
@ClientThread
final class CommandService implements CommandControls {
    private static final int CACHE_ENTRIES = 256;

    /** A cached answer for revision {@code rev}, received at {@code atMs}. */
    private record Cached(long rev, long atMs, List<Suggestion> suggestions, @Nullable List<ChatSpan> usage) {}

    /** The usage line of the latest answer, read while the chat box draws. */
    private @Nullable List<ChatSpan> usage;

    /** A request waiting out its debounce. */
    private record Waiting(String slot, String prefixKey, String input, long rev, CommandComplete message,
            CompletableFuture<List<Suggestion>> future, long dueMs) {}

    private final SessionHost host;
    private final ObjectStore store;
    private final Interactions interactions;
    private final Map<String, CompletionSource> sources = new HashMap<>();
    private final CommandEngine commandEngine;
    private final Map<String, Waiting> waiting = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    private ArgContext context = ArgContext.NONE;

    CommandService(SessionHost host, ObjectStore store, Interactions interactions) {
        this.host = host;
        this.store = store;
        this.interactions = interactions;
        this.commandEngine = new CommandEngine(Collections.unmodifiableMap(sources), this::completeDynamic);
    }

    @Override
    public void setContext(ArgContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public ArgContext context() {
        return context;
    }

    @Override
    public void registerSource(String source, CompletionSource completer) {
        sources.put(source, Objects.requireNonNull(completer, "completer"));
    }

    @Override
    public CommandEngine engine() {
        return commandEngine;
    }

    @Override
    public List<BotCommands> bots() {
        Map<String, List<CommandDecl>> byOwner = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Session s : host.liveBots()) {
            byOwner.put(s.owner, new ArrayList<>());
            names.put(s.owner, s.displayName);
        }
        for (CommandDecl c : host.declarations().commands()) {
            String owner = ownerOf(c);
            if (owner == null || c.data().orders()) {
                continue;
            }
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(c);
            names.putIfAbsent(owner, c.ownerName());
        }
        TreeMap<String, BotCommands> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<CommandDecl>> e : byOwner.entrySet()) {
            List<CommandDecl> commands = e.getValue();
            commands.sort(BY_NAME);
            Session s = host.writer(e.getKey());
            String name = Objects.requireNonNull(names.get(e.getKey()));
            sorted.putIfAbsent(name, new BotCommands(name, e.getKey(), s == null ? null : s.position, commands));
        }
        return List.copyOf(sorted.values());
    }

    @Override
    public @Nullable String unreachable(String name) {
        for (CrewDecl crew : host.declarations().crews()) {
            for (String bot : crew.data().bots()) {
                if (bot.equalsIgnoreCase(name)) {
                    return bot + " is offline";
                }
            }
            List<CrewGroup> groups = crew.data().groups();
            for (CrewGroup g : groups == null ? List.<CrewGroup>of() : groups) {
                if (g.name().equalsIgnoreCase(name)) {
                    return "None of " + g.name() + " is online";
                }
            }
        }
        return null;
    }

    @Override
    public List<Addressee> addressees() {
        DeclarationSnapshot decl = host.declarations();
        Map<String, List<CommandDecl>> orders = new HashMap<>();
        for (CommandDecl c : decl.commands()) {
            String owner = ownerOf(c);
            if (owner != null && c.data().orders()) {
                orders.computeIfAbsent(owner, k -> new ArrayList<>()).add(c);
            }
        }
        for (List<CommandDecl> list : orders.values()) {
            list.sort(BY_NAME);
        }
        Map<String, BotCommands> own = new HashMap<>();
        for (BotCommands b : bots()) {
            own.put(b.owner(), b);
        }
        // Every live bot is addressable, in any world: the world decides what an order does, not who may give it.
        Map<String, Session> attached = new LinkedHashMap<>();
        for (Session s : host.liveBots()) {
            attached.putIfAbsent(s.displayName.toLowerCase(Locale.ROOT), s);
        }
        List<Addressee> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        if (!attached.isEmpty()) {
            List<BotCommands> sets = new ArrayList<>();
            List<String> members = new ArrayList<>();
            List<String> owners = new ArrayList<>();
            for (Session s : attached.values()) {
                sets.add(ownSet(own, s));
                members.add(s.displayName);
                owners.add(s.owner);
            }
            for (CrewDecl crew : decl.crews()) {
                List<Session> crewBots = present(crew.data().bots(), attached);
                List<CommandDecl> list = orders.get(crew.owner());
                if (list != null && !crewBots.isEmpty()) {
                    sets.add(new BotCommands(crew.ownerName(), crew.owner(), null, list, names(crewBots)));
                }
            }
            out.add(new Addressee(CommandEngine.ALL, Addressee.Kind.ALL, sets, members, owners, false));
            taken.add(CommandEngine.ALL);
        }
        List<Session> bots = new ArrayList<>(attached.values());
        bots.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        for (Session s : bots) {
            List<BotCommands> sets = new ArrayList<>();
            sets.add(ownSet(own, s));
            for (CrewDecl crew : decl.crews()) {
                List<CommandDecl> list = orders.get(crew.owner());
                if (list != null && contains(crew.data().bots(), s.displayName)) {
                    sets.add(new BotCommands(s.displayName, crew.owner(), s.position, list, List.of(s.displayName)));
                }
            }
            out.add(new Addressee(s.displayName, Addressee.Kind.BOT, sets, List.of(s.displayName), List.of(s.owner), !host.attachedNow(s)));
            taken.add(s.displayName.toLowerCase(Locale.ROOT));
        }
        for (Session s : host.liveControllers()) {
            if (taken.add(s.displayName.toLowerCase(Locale.ROOT))) {
                BotCommands set = own.get(s.owner);
                out.add(new Addressee(s.displayName, Addressee.Kind.CONTROLLER,
                        List.of(set != null ? set : new BotCommands(s.displayName, s.owner, null, List.of())), List.of(), List.of(s.owner), false));
            }
        }
        Map<String, GroupParts> groups = new LinkedHashMap<>();
        for (CrewDecl crew : decl.crews()) {
            List<CrewGroup> declared = crew.data().groups();
            if (declared == null) {
                continue;
            }
            for (CrewGroup g : declared) {
                String key = g.name().toLowerCase(Locale.ROOT);
                List<Session> members = present(g.members(), attached);
                if (taken.contains(key) || members.isEmpty()) {
                    continue;
                }
                GroupParts parts = groups.computeIfAbsent(key, k -> new GroupParts(g.name()));
                List<CommandDecl> list = orders.get(crew.owner());
                Vec3d pos = members.size() == 1 ? members.getFirst().position : null;
                if (list != null) {
                    parts.sets.add(new BotCommands(g.name(), crew.owner(), pos, list, names(members)));
                }
                for (Session m : members) {
                    if (!parts.members.contains(m.displayName)) {
                        parts.members.add(m.displayName);
                        parts.owners.add(m.owner);
                    }
                }
            }
        }
        for (GroupParts g : groups.values()) {
            out.add(new Addressee(g.name, Addressee.Kind.GROUP, g.sets, g.members, g.owners, false));
        }
        return List.copyOf(out);
    }

    /** A group gathered from every crew that declares it. */
    private static final class GroupParts {
        final String name;
        final List<BotCommands> sets = new ArrayList<>();
        final List<String> members = new ArrayList<>();
        final List<String> owners = new ArrayList<>();

        GroupParts(String name) {
            this.name = name;
        }
    }

    private static final java.util.Comparator<CommandDecl> BY_NAME = (a, b) -> a.data().name().compareTo(b.data().name());

    private static BotCommands ownSet(Map<String, BotCommands> own, Session s) {
        BotCommands b = own.get(s.owner);
        return b != null ? b : new BotCommands(s.displayName, s.owner, s.position, List.of());
    }

    /** The attached bots among the named ones, in the order named. */
    private static List<Session> present(List<String> named, Map<String, Session> attached) {
        List<Session> out = new ArrayList<>();
        for (String n : named) {
            Session s = attached.get(n.toLowerCase(Locale.ROOT));
            if (s != null && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean contains(List<String> named, String name) {
        for (String n : named) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(List<Session> sessions) {
        List<String> out = new ArrayList<>(sessions.size());
        for (Session s : sessions) {
            out.add(s.displayName);
        }
        return out;
    }

    private static @Nullable String ownerOf(CommandDecl c) {
        try {
            return ObjectKeys.parse(c.key()).owner();
        } catch (BridgeException e) {
            return null;
        }
    }

    @Override
    public @Nullable BotCommands bot(String name) {
        for (BotCommands b : bots()) {
            if (b.name().equalsIgnoreCase(name)) {
                return b;
            }
        }
        return null;
    }

    /** Returns the addressable owner of a session, with its own commands. */
    BotCommands of(Session s) {
        for (BotCommands b : bots()) {
            if (b.owner().equals(s.owner)) {
                return b;
            }
        }
        return new BotCommands(s.displayName, s.owner, s.position, List.of());
    }

    @Override
    public CompletableFuture<InvocationResult> invoke(ParseResult.Invocation invocation) {
        return interactions.invokeCommand(invocation.command().key(), invocation.path(), invocation.args(), null, invocation.bots());
    }

    @Override
    public CompletableFuture<InvocationResult> invoke(ParseResult.Invocation invocation, String source) {
        return interactions.invokeCommand(invocation.command().key(), invocation.path(), invocation.args(), source, invocation.bots());
    }

    @Override
    public CompletableFuture<List<Suggestion>> completeDynamic(String commandKey, List<String> path, String arg,
            String input, Map<String, Object> args, List<String> bots) {
        StoredObject o = interactions.declaration(commandKey, CommandData.class);
        if (o == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        long rev = o.state().rev();
        String slot = commandKey + '\u0000' + String.join(" ", path) + '\u0000' + arg;
        // The answer depends on who the order is for, such as the items those bots carry.
        String prefixKey = slot + '\u0000' + new TreeMap<>(args) + '\u0000' + String.join(",", bots) + '\u0000';
        Cached hit = cache.get(prefixKey + input);
        if (hit != null && hit.rev() != rev) {
            invalidate(commandKey);
        } else if (hit != null && host.nowMs() - hit.atMs() < CACHE_FRESH_MS) {
            usage = hit.usage();
            return CompletableFuture.completedFuture(hit.suggestions());
        }
        Waiting previous = waiting.remove(slot);
        if (previous != null) {
            previous.future().complete(fallback(previous));
        }
        CompletableFuture<List<Suggestion>> future = new CompletableFuture<>();
        CommandComplete message = new CommandComplete(commandKey, List.copyOf(path), arg, input, Map.copyOf(args), bots.isEmpty() ? null : List.copyOf(bots));
        waiting.put(slot, new Waiting(slot, prefixKey, input, rev, message, future, host.nowMs() + DEBOUNCE_MS));
        return future;
    }

    /** Sends the requests whose debounce has passed. */
    void tick(long now) {
        if (waiting.isEmpty()) {
            return;
        }
        List<Waiting> due = new ArrayList<>();
        Iterator<Waiting> it = waiting.values().iterator();
        while (it.hasNext()) {
            Waiting w = it.next();
            if (now >= w.dueMs()) {
                it.remove();
                due.add(w);
            }
        }
        for (Waiting w : due) {
            send(w);
        }
    }

    private void send(Waiting w) {
        StoredObject o = interactions.declaration(w.message().command(), CommandData.class);
        Session s;
        try {
            s = o == null ? null : interactions.commandOwner(o);
        } catch (BridgeException e) {
            s = null;
        }
        if (s == null || o == null || o.state().rev() != w.rev()) {
            w.future().complete(fallback(w));
            return;
        }
        host.request(s, CoreMessages.COMMAND_COMPLETE, w.message(), CoreMessages.COMMAND_SUGGESTIONS)
                .whenComplete((answer, error) -> {
                    if (error != null || answer == null) {
                        w.future().complete(fallback(w));
                        return;
                    }
                    List<Suggestion> list = List.copyOf(answer.suggestions());
                    List<ChatSpan> spans = answer.usage() == null || answer.usage().isEmpty() ? null : List.copyOf(answer.usage());
                    usage = spans;
                    StoredObject now = interactions.declaration(w.message().command(), CommandData.class);
                    if (now != null && now.state().rev() == w.rev()) {
                        cache.put(w.prefixKey() + w.input(), new Cached(w.rev(), host.nowMs(), list, spans));
                    }
                    w.future().complete(list);
                });
    }

    /** The answer cached for the longest shorter prefix of the input, filtered by the input. */
    private List<Suggestion> fallback(Waiting w) {
        String input = w.input();
        usage = null;
        for (int len = input.length(); len >= 0; len--) {
            Cached c = cache.get(w.prefixKey() + input.substring(0, len));
            if (c == null || c.rev() != w.rev()) {
                continue;
            }
            usage = c.usage();
            String lower = input.toLowerCase(Locale.ROOT);
            List<Suggestion> out = new ArrayList<>();
            for (Suggestion s : c.suggestions()) {
                String display = s.display();
                if (s.text().toLowerCase(Locale.ROOT).startsWith(lower)
                        || (display != null && display.toLowerCase(Locale.ROOT).startsWith(lower))) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    @Override
    public void forgetCompletions() {
        cache.clear();
        usage = null;
    }

    @Override
    public @Nullable List<ChatSpan> usage() {
        return usage;
    }

    @Override
    public void clearUsage() {
        usage = null;
    }

    private void invalidate(String commandKey) {
        String prefix = commandKey + '\u0000';
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
