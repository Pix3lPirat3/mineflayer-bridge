package io.github.pix3lpirat3.mfbridge.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatRoute;
import io.github.pix3lpirat3.mfbridge.core.command.ArgContext;
import io.github.pix3lpirat3.mfbridge.core.diag.ConnectionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.input.PickInput;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.input.WorldMenuInput;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.query.ClientQueries;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueriedEntity;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocks;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocksResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntities;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntitiesResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * The real core behind the conformance suite: starts a bridge on a free
 * port, prints {@code {"event":"ready","endpointFile":...}}, then answers one JSON
 * command per stdin line with one JSON line on stdout, ticking the core between them. The suite in
 * {@code tools/conformance} runs the same scenarios against this server and the mock mod.
 *
 * <p>The clock is the real one plus what the {@code advance} op skipped. Arguments: {@code
 * --max-objects <n>} sets the object quota and {@code --surface-cooldowns <ms,ms,...>} the cooldowns
 * after the player dismisses a surface.
 */
public final class ConformanceServer {

    private static final Gson GSON = new Gson();
    private static final long TICK_BUDGET_NANOS = 2_000_000L;

    private record Pending(long id, CompletableFuture<JsonObject> result) {}

    /** The system clock, moved forward by the {@code advance} op. */
    private static final class SkippingClock implements BridgeClock {
        private final AtomicLong aheadMs = new AtomicLong();

        void advance(long ms) {
            aheadMs.addAndGet(ms);
        }

        @Override
        public long nanoTime() {
            return System.nanoTime() + aheadMs.get() * 1_000_000L;
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis() + aheadMs.get();
        }
    }

    private static final SkippingClock CLOCK = new SkippingClock();

    /** What bots asked to show with {@code notify}, oldest first. */
    private static final List<Notifier.Notification> NOTIFICATIONS = new ArrayList<>();

    /** The chat lines bots printed, oldest first. */
    private static final List<ChatPrint> PRINTED = new ArrayList<>();

    /** The loaded world the {@code setLoadedWorld} op sets, answered like the mock mod answers it. */
    private static final JsonQueries LOADED = new JsonQueries();

    private ConformanceServer() {
    }

    /** Client queries over a JSON world: {@code entities}, {@code blocks} and {@code unloadedFromX}. */
    private static final class JsonQueries implements ClientQueries {
        JsonObject world = new JsonObject();

        @Override
        public QueryEntitiesResult entities(QueryEntities q) {
            Vec3d c = Objects.requireNonNull(q.center());
            double r2 = (double) q.radius() * q.radius();
            List<QueriedEntity> found = new ArrayList<>();
            for (JsonElement el : array("entities")) {
                JsonObject e = el.getAsJsonObject();
                JsonObject p = e.getAsJsonObject("pos");
                Vec3d pos = new Vec3d(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
                String type = e.get("type").getAsString();
                if (d2(pos, c) <= r2 && (q.types() == null || q.types().contains(type))) {
                    found.add(new QueriedEntity(e.get("id").getAsInt(), type, pos, e.has("name") ? e.get("name").getAsString() : null,
                            e.has("player") && e.get("player").getAsBoolean()));
                }
            }
            found.sort(Comparator.comparingDouble(e -> d2(e.pos(), c)));
            return new QueryEntitiesResult(List.copyOf(found.subList(0, Math.min(found.size(), q.limit()))));
        }

        @Override
        public QueryBlocksResult blocks(QueryBlocks q) {
            int unloaded = world.has("unloadedFromX") ? world.get("unloadedFromX").getAsInt() : Integer.MAX_VALUE;
            boolean complete = q.max().x() < unloaded;
            List<int[]> inside = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (JsonElement el : array("blocks")) {
                JsonObject b = el.getAsJsonObject();
                int x = b.get("x").getAsInt();
                int y = b.get("y").getAsInt();
                int z = b.get("z").getAsInt();
                String name = b.get("name").getAsString();
                if (x >= q.min().x() && x <= q.max().x() && y >= q.min().y() && y <= q.max().y() && z >= q.min().z() && z <= q.max().z()
                        && x < unloaded && (q.blocks() == null || q.blocks().contains(name))) {
                    inside.add(new int[] {x, y, z, names.size()});
                    names.add(name);
                }
            }
            // The game integration walks x, then z, then y.
            inside.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[2]).thenComparingInt(a -> a[1]));
            if (inside.size() > q.limit()) {
                complete = false;
                inside = inside.subList(0, q.limit());
            }
            List<String> palette = new ArrayList<>();
            int[] positions = new int[inside.size() * 3];
            short[] ids = new short[inside.size()];
            for (int i = 0; i < inside.size(); i++) {
                int[] b = inside.get(i);
                String name = names.get(b[3]);
                int index = palette.indexOf(name);
                if (index < 0) {
                    index = palette.size();
                    palette.add(name);
                }
                positions[i * 3] = b[0];
                positions[i * 3 + 1] = b[1];
                positions[i * 3 + 2] = b[2];
                ids[i] = (short) index;
            }
            return new QueryBlocksResult(positions, palette, ids, complete);
        }

        /** Where the player stands, or the origin when the world names no player. */
        Vec3d player() {
            if (!world.has("player")) {
                return new Vec3d(0, 0, 0);
            }
            JsonObject p = world.getAsJsonObject("player");
            return new Vec3d(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
        }

        private JsonArray array(String name) {
            return world.has(name) ? world.getAsJsonArray(name) : new JsonArray();
        }

        private static double d2(Vec3d a, Vec3d b) {
            double dx = a.x() - b.x();
            double dy = a.y() - b.y();
            double dz = a.z() - b.z();
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Command arguments parsed against the loaded world: {@code ~} is the player's position, nothing is looked at. */
    private static final ArgContext ARGS = new ArgContext() {
        @Override
        public Vec3i originBlock() {
            Vec3d p = LOADED.player();
            return new Vec3i((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
        }

        @Override
        public Vec3d originExact() {
            return LOADED.player();
        }

        @Override
        public @Nullable Vec3i crosshairBlock() {
            return null;
        }

        @Override
        public @Nullable Vec3d crosshairPos() {
            return null;
        }

        @Override
        public boolean blockExists(String id) {
            return true;
        }

        @Override
        public boolean itemExists(String id) {
            return true;
        }
    };

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path dir = Files.createTempDirectory("mfb-conformance-");
        BridgeConfig.Builder builder = BridgeConfig.builder(dir).port(0).attachPolicy(AttachPolicy.ALWAYS).askLocalApproval(false)
                .clock(CLOCK);
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--max-objects" -> builder.maxObjectsPerSession(Integer.parseInt(args[i + 1]));
                case "--surface-cooldowns" -> {
                    List<Long> cooldowns = new ArrayList<>();
                    for (String ms : args[i + 1].split(",")) {
                        if (!ms.isBlank()) {
                            cooldowns.add(Long.parseLong(ms.strip()));
                        }
                    }
                    builder.cancelCooldowns(cooldowns, 60_000);
                }
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        HostEnvironment host = new HostEnvironment("0.1.0-conformance", "26.3", "conformance", req -> DeviceApprover.ALLOW_ONCE.requestApproval(req),
                NOTIFICATIONS::add, LoggerFactory.getLogger("conformance"));
        BridgeCore core = BridgeCore.start(builder.build(), host);
        core.setClientQueries(LOADED);
        core.commands().setContext(ARGS);
        core.events().subscribe(ChatPrint.TYPE, PRINTED::add);
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    lines.add(line);
                }
            } catch (Exception e) {
                // stdin closed: the suite is done.
            }
            lines.add("{\"op\":\"close\"}");
        }, "conformance-stdin");
        reader.setDaemon(true);
        reader.start();
        JsonObject ready = new JsonObject();
        ready.addProperty("event", "ready");
        ready.addProperty("endpointFile", core.endpointFile().toString());
        out.println(GSON.toJson(ready));
        List<Pending> pending = new ArrayList<>();
        try {
            while (true) {
                for (String line = lines.poll(); line != null; line = lines.poll()) {
                    JsonObject cmd = JsonParser.parseString(line).getAsJsonObject();
                    String op = cmd.get("op").getAsString();
                    if (op.equals("close")) {
                        return;
                    }
                    long id = cmd.has("id") ? cmd.get("id").getAsLong() : 0;
                    try {
                        CompletableFuture<JsonObject> later = handle(core, op, cmd, id, out);
                        if (later != null) {
                            pending.add(new Pending(id, later));
                        }
                    } catch (RuntimeException e) {
                        reply(out, id, null, e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
                core.tick(TICK_BUDGET_NANOS);
                for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
                    Pending p = it.next();
                    if (p.result().isDone()) {
                        it.remove();
                        try {
                            reply(out, p.id(), p.result().join(), null);
                        } catch (RuntimeException e) {
                            Throwable cause = e.getCause() == null ? e : e.getCause();
                            reply(out, p.id(), null, String.valueOf(cause.getMessage()));
                        }
                    }
                }
                Thread.sleep(2);
            }
        } finally {
            core.close();
        }
    }

    private static JsonObject result(InvocationResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok());
        if (r.message() != null) {
            o.addProperty("message", r.message());
        }
        return o;
    }

    /** What a chat line did, once every command it ran has answered. */
    private static CompletableFuture<JsonObject> chat(ChatRoute route) {
        JsonObject o = new JsonObject();
        switch (route) {
            case ChatRoute.PassThrough p -> {
                o.addProperty("route", "pass");
                o.addProperty("text", p.lineForServer());
            }
            case ChatRoute.CapturedCommand c -> {
                o.addProperty("route", "command");
                List<CompletableFuture<InvocationResult>> runs = new ArrayList<>();
                for (ChatRoute.Run r : c.runs()) {
                    runs.add(r.result());
                }
                return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).thenApply(done -> {
                    JsonArray results = new JsonArray();
                    for (CompletableFuture<InvocationResult> r : runs) {
                        results.add(result(r.join()));
                    }
                    o.add("results", results);
                    return o;
                });
            }
            case ChatRoute.CapturedHelp h -> {
                o.addProperty("route", "help");
                JsonArray lines = new JsonArray();
                h.lines().forEach(lines::add);
                o.add("lines", lines);
            }
            case ChatRoute.CapturedMessage m -> {
                o.addProperty("route", "message");
                if (m.commandError() != null) {
                    o.addProperty("commandError", m.commandError());
                }
            }
            case ChatRoute.CapturedPrompt p -> {
                o.addProperty("route", "prompt");
                if (p.error() != null) {
                    o.addProperty("error", p.error());
                }
            }
            case ChatRoute.Unknown u -> {
                o.addProperty("route", "unknown");
                o.addProperty("message", u.message());
            }
            case ChatRoute.Mistyped m -> {
                o.addProperty("route", "mistyped");
                o.addProperty("closest", m.closest());
                o.addProperty("corrected", m.corrected());
            }
        }
        return CompletableFuture.completedFuture(o);
    }

    private static SessionInfo session(BridgeCore core, String owner) {
        for (SessionInfo s : core.inspector().sessions()) {
            if (s.owner().equals(owner)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no session " + owner);
    }

    /** The device whose connection carries the session of an owner. */
    private static String deviceOf(BridgeCore core, String owner) {
        long connection = session(core, owner).connectionId();
        for (ConnectionInfo c : core.inspector().connections()) {
            if (c.id() == connection && c.deviceId() != null) {
                return c.deviceId();
            }
        }
        throw new IllegalArgumentException(owner + " is not on a connection");
    }

    private static String typeOf(InputRequest r) {
        return switch (r) {
            case PickInput p -> "pick";
            case PromptInput p -> "prompt";
            case FormInput f -> "form";
            case WorldMenuInput w -> "world-menu";
        };
    }

    private static @Nullable CompletableFuture<JsonObject> handle(BridgeCore core, String op, JsonObject cmd, long id,
            PrintStream out) {
        switch (op) {
            case "setPlayerContext" -> {
                JsonObject w = cmd.getAsJsonObject("world");
                core.setPlayerContext(w == null ? null : new WorldKey(w.get("server").getAsString(), null, w.get("dimension").getAsString()));
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "objects" -> {
                String prefix = cmd.has("prefix") ? cmd.get("prefix").getAsString() : "";
                JsonArray list = new JsonArray();
                for (ObjectInfo o : core.inspector().objects()) {
                    if (!o.key().startsWith(prefix)) {
                        continue;
                    }
                    JsonObject j = new JsonObject();
                    j.addProperty("key", o.key());
                    j.addProperty("kind", o.kind());
                    j.addProperty("rev", o.rev());
                    j.addProperty("scope", o.scope().name().toLowerCase(java.util.Locale.ROOT));
                    if (o.layer() != null) {
                        j.addProperty("layer", o.layer());
                    }
                    byte[] data = encode(o.kind(), o.decoded());
                    if (data != null) {
                        j.addProperty("data", Base64.getEncoder().encodeToString(data));
                    }
                    list.add(j);
                }
                JsonObject r = new JsonObject();
                r.add("objects", list);
                reply(out, id, r, null);
                return null;
            }
            case "setLoadedWorld" -> {
                JsonObject w = cmd.getAsJsonObject("world");
                LOADED.world = w;
                if (w.has("player")) {
                    JsonObject p = w.getAsJsonObject("player");
                    core.setPlayerPosition(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
                }
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "sessions" -> {
                JsonArray list = new JsonArray();
                for (SessionInfo s : core.inspector().sessions()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("owner", s.owner());
                    j.addProperty("kind", s.kind());
                    j.addProperty("name", s.displayName());
                    j.addProperty("connected", s.connected());
                    list.add(j);
                }
                JsonObject r = new JsonObject();
                r.add("sessions", list);
                reply(out, id, r, null);
                return null;
            }
            case "invokeAction" -> {
                return core.invokeAction(cmd.get("key").getAsString(), target(cmd.getAsJsonObject("target")))
                        .thenApply(ConformanceServer::result);
            }
            case "invokeCommand" -> {
                List<String> path = new ArrayList<>();
                if (cmd.has("path")) {
                    cmd.getAsJsonArray("path").forEach(p -> path.add(p.getAsString()));
                }
                Map<String, Object> args = new HashMap<>();
                if (cmd.has("args")) {
                    for (Map.Entry<String, JsonElement> e : cmd.getAsJsonObject("args").entrySet()) {
                        args.put(e.getKey(), plain(e.getValue()));
                    }
                }
                return core.invokeCommand(cmd.get("key").getAsString(), path, args).thenApply(ConformanceServer::result);
            }
            case "screenEvent" -> {
                JsonElement v = cmd.get("value");
                Object value = v == null || v.isJsonNull() ? null : v.getAsJsonPrimitive().isBoolean() ? (Object) v.getAsBoolean() : (Object) v.getAsLong();
                return core.screens().event(cmd.get("key").getAsString(), cmd.get("item").getAsString(), value)
                        .thenApply(ConformanceServer::result);
            }
            case "chat" -> {
                return chat(core.chat().route(cmd.get("line").getAsString()));
            }
            case "setPermissions" -> {
                String device = deviceOf(core, cmd.get("owner").getAsString());
                Set<String> wanted = new HashSet<>();
                cmd.getAsJsonArray("permissions").forEach(p -> wanted.add(p.getAsString()));
                for (Permission p : Permission.values()) {
                    core.devices().setPermission(device, p, wanted.contains(p.key()));
                }
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "setAttached" -> {
                String identity = session(core, cmd.get("owner").getAsString()).identity();
                if (identity == null) {
                    throw new IllegalArgumentException(cmd.get("owner").getAsString() + " is not a bot");
                }
                core.attach().setChoice(identity, cmd.get("attached").getAsBoolean());
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "inputs" -> {
                Map<Integer, String> owners = new HashMap<>();
                for (SessionInfo s : core.inspector().sessions()) {
                    owners.put(s.sessionId(), s.owner());
                }
                JsonArray list = new JsonArray();
                for (InputRequest r : core.input().pending()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", r.id());
                    j.addProperty("type", typeOf(r));
                    j.addProperty("owner", owners.get(r.sessionId()));
                    list.add(j);
                }
                JsonObject r = new JsonObject();
                r.add("inputs", list);
                reply(out, id, r, null);
                return null;
            }
            case "dismiss" -> {
                core.input().cancel(cmd.get("input").getAsLong());
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "advance" -> {
                CLOCK.advance(cmd.get("ms").getAsLong());
                reply(out, id, new JsonObject(), null);
                return null;
            }
            case "printed" -> {
                JsonArray list = new JsonArray();
                for (ChatPrint line : PRINTED) {
                    StringBuilder text = new StringBuilder();
                    line.spans().forEach(span -> text.append(span.text()));
                    JsonObject j = new JsonObject();
                    j.addProperty("owner", line.owner());
                    j.addProperty("text", text.toString());
                    list.add(j);
                }
                JsonObject r = new JsonObject();
                r.add("printed", list);
                reply(out, id, r, null);
                return null;
            }
            case "notifications" -> {
                JsonArray list = new JsonArray();
                for (Notifier.Notification n : NOTIFICATIONS) {
                    JsonObject j = new JsonObject();
                    j.addProperty("owner", n.owner());
                    j.addProperty("text", n.text());
                    list.add(j);
                }
                JsonObject r = new JsonObject();
                r.add("notifications", list);
                reply(out, id, r, null);
                return null;
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private static Target target(@Nullable JsonObject t) {
        if (t == null) {
            return new Target("none");
        }
        Vec3d pos = null;
        if (t.has("pos")) {
            JsonObject p = t.getAsJsonObject("pos");
            pos = new Vec3d(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
        }
        return new Target(t.get("type").getAsString(), null, null, null, pos, null, t.has("objectKey") ? t.get("objectKey").getAsString() : null);
    }

    private static @Nullable Object plain(JsonElement e) {
        if (e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) ? (Object) p.getAsLong() : (Object) d;
            }
            return p.getAsString();
        }
        return GSON.fromJson(e, Object.class);
    }

    @SuppressWarnings("unchecked")
    private static byte @Nullable [] encode(String kind, @Nullable Object decoded) {
        if (decoded == null) {
            return null;
        }
        for (KindType<?> k : CoreKinds.ALL) {
            if (k.name().equals(kind)) {
                return ((KindType<Object>) k).encode(decoded);
            }
        }
        return null;
    }

    private static void reply(PrintStream out, long id, @Nullable JsonObject result, @Nullable String error) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (error != null) {
            o.addProperty("error", error);
        } else {
            o.add("result", result == null ? new JsonObject() : result);
        }
        out.println(GSON.toJson(o));
    }
}
