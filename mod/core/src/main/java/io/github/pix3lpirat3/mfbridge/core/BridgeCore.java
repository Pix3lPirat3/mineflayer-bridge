package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.core.history.PlayerObjects;
import io.github.pix3lpirat3.mfbridge.core.input.InteractionControls;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.SettingValue;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.auth.DeviceRegistry;
import io.github.pix3lpirat3.mfbridge.core.auth.EndpointFile;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobCache;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatControls;
import io.github.pix3lpirat3.mfbridge.core.command.CommandControls;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerControls;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.diag.InspectorControls;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionSettingsFile;
import io.github.pix3lpirat3.mfbridge.core.history.HistoryControls;
import io.github.pix3lpirat3.mfbridge.core.input.InputChanged;
import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptControls;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayouts;
import io.github.pix3lpirat3.mfbridge.core.persist.BotSettingsStore;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.query.ClientQueries;
import io.github.pix3lpirat3.mfbridge.core.record.RecorderControls;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingControls;
import io.github.pix3lpirat3.mfbridge.core.sample.ClientSampleControls;
import io.github.pix3lpirat3.mfbridge.core.screen.ScreenControls;
import io.github.pix3lpirat3.mfbridge.core.session.Engine;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.transport.BridgeServer;
import io.github.pix3lpirat3.mfbridge.core.transport.TransportLimits;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * The bridge core: WebSocket endpoint, protocol engine, object store, world context and
 * persistence, with no Minecraft dependency. The loader glue starts it once, calls {@link
 * #tick(long)} every client tick, reports the player's world with {@link #setPlayerContext} and
 * renders {@link #renderSnapshot()}. The UI layer reads {@link #declarations()} and {@link #input()},
 * and reports player input with the invocation, keybind, tool and settings methods.
 *
 * <p>Threading: all {@link ClientThread} methods must be called from one thread (the client thread);
 * {@link #renderSnapshot()}, {@link #declarations()} and {@link #boundPort()} are safe from any
 * thread.
 */
public final class BridgeCore implements AutoCloseable {
    private final BridgeConfig config;
    private final HostEnvironment host;
    private final Engine engine;
    private final BridgeServer server;
    private final PersistenceService persistence;
    private final EventBus events;
    private final Path endpointFile;
    private final BlobCache blobs;
    private final ExecutorService blobWorker;
    private final InstanceIdentity identity;
    // The engine's services, held directly: the engine itself only ticks.
    private final InputControls input;
    private final ContainerControls containers;
    private final ScreenControls screens;
    private final InterceptControls intercepts;
    private final ChatControls chat;
    private final CommandControls commands;
    private final PresenceView presence;
    private final LayerControls layers;
    private final AttachControls attach;
    private final FocusControls focus;
    private final PairingControls pairing;
    private final DeviceControls devices;
    private final HistoryControls history;
    private final InspectorControls inspector;
    private final RecorderControls recorder;
    private final ClientSampleControls samples;
    private final HudLayouts hudLayouts;
    private final InteractionControls interactions;
    private final PlayerControls player;
    private final PlayerObjects playerObjects;
    private boolean closed;

    private BridgeCore(
            BridgeConfig config,
            HostEnvironment host,
            Engine engine,
            BridgeServer server,
            PersistenceService persistence,
            EventBus events,
            Path endpointFile,
            BlobCache blobs,
            ExecutorService blobWorker,
            InstanceIdentity identity) {
        this.config = config;
        this.host = host;
        this.engine = engine;
        this.server = server;
        this.persistence = persistence;
        this.events = events;
        this.endpointFile = endpointFile;
        this.blobs = blobs;
        this.blobWorker = blobWorker;
        this.identity = identity;
        this.input = engine.input();
        this.containers = engine.containers();
        this.screens = engine.screens();
        this.intercepts = engine.intercepts();
        this.chat = engine.chat();
        this.commands = engine.commands();
        this.presence = engine.presence();
        this.layers = engine.layers();
        this.attach = engine.attach();
        this.focus = engine.focus();
        this.pairing = engine.pairing();
        this.devices = engine.devices();
        this.history = engine.history();
        this.inspector = engine.inspector();
        this.recorder = engine.recorder();
        this.samples = engine.samples();
        this.hudLayouts = engine.hudLayouts();
        this.interactions = engine.interactions();
        this.player = engine.player();
        this.playerObjects = engine.playerObjects();
    }

    /**
     * Starts the bridge: registers extensions, loads or creates the instance identity, loads devices
     * and player preferences, binds the server (TLS for everyone, plain only from loopback while
     * allowed) and writes {@code endpoint.json} with the fingerprint and a fresh local token. Fails
     * fast if the port is taken; it never picks another port.
     *
     * @throws IOException when the port cannot be bound or the bridge directory cannot be written
     * @throws IllegalArgumentException when an extension registers conflicting names
     */
    @ClientThread
    public static BridgeCore start(BridgeConfig config, HostEnvironment host) throws IOException {
        Path dir = config.bridgeDir();
        Files.createDirectories(dir);
        EventBus events = new EventBus((type, e) -> host.logger().warn("Listener for {} failed", type, e));
        WorldMatchers matchers = new WorldMatchers();
        ExtensionSettingsFile extensionSettings = ExtensionSettingsFile.load(dir.resolve("extension-settings.json"), host.logger());
        ExtensionHost extensions = new ExtensionHost(matchers, events, extensionSettings);
        for (BridgeExtension e : config.extensions()) {
            extensions.register(e);
        }
        PersistenceService persistence =
                new PersistenceService(dir.resolve("data"), PersistenceService.DEFAULT_COMPACT_FLOOR,
                        config.maxOpenStores(), host.logger());
        // Stream verification and blob decoding: CPU work that must stay off the client and render threads.
        ExecutorService blobWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mfbridge-blob");
            t.setDaemon(true);
            return t;
        });
        extensionSettings.writeWith(b -> persistence.writeFile(dir.resolve("extension-settings.json"), b, false));
        BlobCache blobs = new BlobCache(dir.resolve("blobs"), config.blobCacheBytes(), persistence, blobWorker,
                host.logger());
        BridgeServer server = null;
        try {
            DeviceRegistry devices = DeviceRegistry.load(
                    dir.resolve(DeviceRegistry.NAME), (f, b) -> persistence.writeFile(f, b, true), host.logger());
            PlayerPrefs prefs = PlayerPrefs.load(dir.resolve("player").resolve("prefs.json"), config.attachPolicy(),
                    (f, b) -> persistence.writeFile(f, b, false), host.logger());
            BotSettingsStore settings = BotSettingsStore.load(dir.resolve("player").resolve("settings.json"),
                    (f, b) -> persistence.writeFile(f, b, false), host.logger());
            SecureRandom random = new SecureRandom();
            InstanceIdentity identity = InstanceIdentity.loadOrCreate(dir, random, host.logger());
            byte[] token = new byte[32];
            random.nextBytes(token);
            Engine engine = new Engine(config, host, extensions, matchers, events, devices, persistence, prefs,
                    settings, token, blobs, blobWorker, identity);
            server = BridgeServer.start(config.bindAddress(), config.port(),
                    new TransportLimits(config.maxFrameBytes(), config.maxQueuedFrames(), config.maxQueuedBytes(),
                            config.preAuthFrames(), config.preAuthBytes(), config.maxUnauthenticatedConnections()),
                    config.allowPlainLoopback(), identity.serverContext(), engine.netListener(), host.logger());
            engine.boundPort(server.port());
            Path endpoint = EndpointFile.write(dir, server.port(), !config.allowPlainLoopback(), identity.fingerprint(),
                    token, host.logger());
            return new BridgeCore(config, host, engine, server, persistence, events, endpoint, blobs, blobWorker,
                    identity);
        } catch (IOException | RuntimeException e) {
            if (server != null) {
                server.close();
            }
            persistence.close();
            blobWorker.shutdownNow();
            throw e;
        }
    }

    /**
     * Runs one bridge tick: drains inbound frames within {@code budgetNanos} of real time (always at
     * least one frame when any is queued, so the bridge makes progress), then processes timers (TTL,
     * heartbeat, acks, request timeouts, resume window expiry), interest updates and the render
     * snapshot, and flushes each connection once. Frames left over wait for the next tick.
     *
     * @param budgetNanos intake time budget in nanoseconds, for example 2 ms
     */
    @ClientThread
    public void tick(long budgetNanos) {
        engine.tick(budgetNanos);
    }

    /**
     * Reports what the player is doing; sessions receive it in {@code player.context} on the next
     * tick when it changed.
     *
     * @param alive false while the death screen shows
     * @param away no input for the time the player set
     * @param windowFocused the game window has focus
     */
    @ClientThread
    public void setPlayerState(boolean alive, boolean away, boolean windowFocused) {
        player.setState(alive, away, windowFocused);
    }

    /**
     * Reports the player's profile name and id; sessions receive them in {@code player.context} on
     * the next tick, so bots know who gives orders.
     *
     * @param name the name, or null when unknown
     * @param uuid the profile id as a dashed lowercase UUID, or null when unknown
     */
    @ClientThread
    public void setPlayer(@Nullable String name, @Nullable String uuid) {
        player.setProfile(name, uuid);
    }

    /**
     * Reports the player's world key, or null when not in a world (title screen). Sessions receive
     * {@code player.context} and {@code interest} on the next tick when this changes.
     */
    @ClientThread
    public void setPlayerContext(@Nullable WorldKey world) {
        engine.setPlayerContext(world);
    }

    /**
     * Reports the player's position for level of detail: bots far from it
     * get {@code interest.detail = reduced}. Cheap; call every tick while in a world.
     *
     * @param x block x, or NaN when unknown
     * @param y block y
     * @param z block z
     */
    @ClientThread
    public void setPlayerPosition(double x, double y, double z) {
        player.setPosition(x, y, z);
    }

    /**
     * Returns where a bot last said it is ({@code world.report}), for views of bots the player's client
     * has not loaded, such as a map.
     *
     * @param owner the owner key
     * @return the position, or null when the bot has not reported one
     */
    @ClientThread
    public @Nullable Vec3d botPosition(String owner) {
        return engine.reportedPosition(owner);
    }

    /**
     * Returns an owner's session for messages from a mod extension: {@link
     * MessageContext#send} reaches its Node half when the connection negotiated the message.
     *
     * @param owner the owner key, such as {@code bot:alice@example.net}
     * @return the session, or null while the owner has none
     */
    @ClientThread
    public @Nullable MessageContext session(String owner) {
        return engine.sessionContext(owner);
    }

    /**
     * Notes that the player just used something of an owner through a mod extension, such as a
     * button, a key or a typed line that the extension passes on to its Node half. What that owner
     * opens in the next few seconds counts as asked for: it is not held back by the cooldown after
     * a dismissal and may open over Bridge home. Commands, actions, screen items and container
     * clicks that go through the core note this themselves. Call it only for what the player did,
     * never for something a bot's own message set off.
     *
     * @param owner the owner key, such as {@code bot:alice@example.net}
     */
    @ClientThread
    public void playerAsked(String owner) {
        engine.playerAsked(owner);
    }

    /**
     * Sets what answers {@code query.entities} and {@code query.blocks}; without
     * it, queries are answered {@code unsupported}.
     *
     * @param queries the game's answers, or null
     */
    @ClientThread
    public void setClientQueries(@Nullable ClientQueries queries) {
        engine.setClientQueries(queries);
    }

    /**
     * Returns the blob cache: availability and lazy decoding of blobs that retained-tier objects refer
     * to. The same store is reachable through {@link RenderSnapshot#blobs()}.
     */
    @AnyThread
    public BlobStore blobs() {
        return blobs;
    }

    /** Returns the player's world as last reported, or null. */
    @ClientThread
    public @Nullable WorldKey playerContext() {
        return player.world();
    }

    /**
     * Returns the latest immutable render snapshot. A new instance is published by {@link #tick}
     * only when visible content changed; otherwise the same instance is returned.
     */
    @AnyThread
    public RenderSnapshot renderSnapshot() {
        return engine.renderSnapshot();
    }

    /**
     * Returns the latest immutable declaration snapshot: commands, actions, keybinds, tools,
     * settings, HUD panels and boss bars of attached sessions. A new instance is published by {@link
     * #tick} only when its content changed; otherwise the same instance is returned.
     */
    @AnyThread
    public DeclarationSnapshot declarations() {
        return engine.declarations();
    }

    /**
     * Returns the queue of picks, prompts, forms and world menus bots are waiting on. {@link InputChanged#TYPE}
     * is posted on {@link #events()} when its active request or length changes.
     */
    @ClientThread
    public InputControls input() {
        return input;
    }

    /**
     * Returns the container view controls: open requests from bots, the open view and slot gestures.
     * {@link io.github.pix3lpirat3.mfbridge.core.container.ContainerOpenRequested}
     * and {@link io.github.pix3lpirat3.mfbridge.core.container.ContainerCloseRequested} are posted on
     * {@link #events()}.
     */
    @ClientThread
    public ContainerControls containers() {
        return containers;
    }

    /**
     * Returns the declared-screen controls: open requests from bots, which screens are open, and the
     * clicks and value changes sent to their owners.
     */
    @ClientThread
    public ScreenControls screens() {
        return screens;
    }

    /**
     * Returns the interaction intercepts: the rules bots declared
     * for the player's real block and entity interactions, their dispatch, the pause switch and the
     * per-bot cancel permission ({@code input.intercept}).
     */
    @ClientThread
    public InterceptControls intercepts() {
        return intercepts;
    }

    /**
     * Returns the chat bridge: routing of the player's chat lines to attached bots.
     * {@link io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint} is posted on {@link
     * #events()} for client-only lines bots print.
     */
    @ClientThread
    public ChatControls chat() {
        return chat;
    }

    /**
     * Runs a context action: sends {@code action.invoked} with a fresh invocation id to the owning
     * session.
     *
     * @param actionKey full key of a {@code core:action} object
     * @param target the resolved target; its {@code type} must equal the action's target type
     * @return completes on the client thread when the bot answers with {@code invocation.result}, or
     *     fails with a {@link BridgeException}: {@code invalid_message} (unknown action or wrong
     *     target type), {@code disconnected} (no owner, or its session ended first), {@code
     *     not_attached}, {@code world_mismatch} (world-scoped action outside its world) or {@code
     *     timeout} (no answer within 30 s)
     */
    @ClientThread
    public CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target) {
        return interactions.invokeAction(actionKey, target, null, null);
    }

    /**
     * Like {@link #invokeAction(String, Target)}, telling the bot how the player ran it, so it can
     * answer later messages the same way.
     *
     * @param actionKey full object key of the {@code core:action}
     * @param target the target
     * @param source {@code menu}, {@code chat}, {@code palette}, {@code screen} or {@code map}
     * @return completes like {@link #invokeAction(String, Target)}
     */
    public CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target, String source) {
        return interactions.invokeAction(actionKey, target, source, null);
    }

    /**
     * Like {@link #invokeAction(String, Target, String)}, also naming the bots the player had
     * selected (on the map, for example), so an order can go to them.
     *
     * @param actionKey full object key of the {@code core:action}
     * @param target the target
     * @param source how the player ran it
     * @param bots names of the selected bots; empty for none
     * @return completes like {@link #invokeAction(String, Target)}
     */
    public CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target, String source, List<String> bots) {
        return interactions.invokeAction(actionKey, target, source, bots);
    }

    /**
     * Runs a command: validates {@code args} against the declared arguments, then sends {@code
     * command.invoked} with a fresh invocation id to the owning session. Missing arguments take their
     * default; integers are sent as {@code Long}, floats as {@code Double}, positions as maps with
     * {@code x}, {@code y}, {@code z}.
     *
     * @param commandKey full key of a {@code core:command} object
     * @param args argument name to value
     * @return completes on the client thread with the bot's answer, or fails with a {@link
     *     BridgeException}: {@code invalid_message} (unknown command, or arguments that break their
     *     declaration, with a message for the player), {@code disconnected}, {@code not_attached},
     *     {@code world_mismatch} or {@code timeout} (no answer within 30 s)
     */
    @ClientThread
    public CompletableFuture<InvocationResult> invokeCommand(String commandKey, Map<String, Object> args) {
        return interactions.invokeCommand(commandKey, List.of(), args, null, List.of());
    }

    /**
     * Runs a subcommand of a command tree: validates {@code args} against
     * the arguments of the node at {@code path}, then sends {@code command.invoked} with the path.
     *
     * @param commandKey full key of a {@code core:command} object
     * @param path canonical node names below the root, outermost first; empty for the root
     * @param args argument name to value, as for {@link #invokeCommand(String, Map)}
     * @return completes like {@link #invokeCommand(String, Map)}; also fails with {@code
     *     invalid_message} when the path names no node or a node that only groups subcommands
     */
    @ClientThread
    public CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args) {
        return interactions.invokeCommand(commandKey, path, args, null, List.of());
    }

    /**
     * Like {@link #invokeCommand(String, List, Map)}, telling the bot how the player ran it.
     *
     * @param commandKey full object key of the {@code core:command}
     * @param path subcommand names below the root
     * @param args argument name to value
     * @param source {@code command}, {@code chat}, {@code palette}, {@code menu}, {@code screen} or {@code map}
     * @return completes like {@link #invokeCommand(String, List, Map)}
     */
    public CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args, String source) {
        return interactions.invokeCommand(commandKey, path, args, source, List.of());
    }

    /**
     * Runs an order (a command declared with {@code orders}) for some of its session's crew bots.
     *
     * @param commandKey full object key of the {@code core:command}
     * @param path subcommand names below the root
     * @param args argument name to value
     * @param source how the player ran it, as for {@link #invokeCommand(String, List, Map, String)}
     * @param bots display names of the bots it is for; not empty
     * @return completes like {@link #invokeCommand(String, List, Map)}; also fails with {@code
     *     invalid_message} when the command is not an order or {@code bots} is empty
     */
    public CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args, String source,
            List<String> bots) {
        return interactions.invokeCommand(commandKey, path, args, source, bots);
    }

    /**
     * Returns the command engine controls: the shared parser
     * and completer, client completion sources, the game context and dynamic completion.
     */
    @ClientThread
    public CommandControls commands() {
        return commands;
    }

    /**
     * Reports a press or release of a bot keybind as {@code keybind.event}. Dropped silently when
     * the keybind or its owner is gone, or the owner is not attached or not in its world.
     */
    @ClientThread
    public void keybindEvent(String keybindKey, boolean pressed) {
        interactions.keybindEvent(keybindKey, pressed);
    }

    /**
     * Reports use of a bot tool as {@code tool.event}. Dropped silently when the tool or its owner
     * is gone, or the owner is not attached or not in its world.
     *
     * @param delta scroll steps for {@link ToolInput#SCROLL}, else null
     * @param key vanilla key name for {@link ToolInput#KEY}, else null
     * @param target the target under the crosshair, or null when nothing resolved
     */
    @ClientThread
    public void toolEvent(
            String toolKey, ToolInput input, @Nullable Float delta, @Nullable String key, @Nullable Target target) {
        interactions.toolEvent(toolKey, input, delta, key, target);
    }

    /**
     * Reports player input on an interactive display as {@code element.event}.
     * Dropped silently when the element is gone or not interactive, it did not
     * declare the input (hover leave and drag move or end count as declared with hover enter and
     * drag start; a key must be in its {@code keys}), or its owner is gone, not attached or not in
     * the element's world.
     *
     * @param elementKey full object key of the display
     * @param delta accumulated scroll steps for {@link ElementInput#SCROLL}, else null
     * @param key vanilla key name for {@link ElementInput#KEY}, else null
     * @param local hit point relative to the element position in blocks, for clicks and drags; else
     *     null
     * @param modifiers bit 0 shift, bit 1 control, bit 2 alt
     */
    @ClientThread
    public void elementEvent(String elementKey, ElementInput input, @Nullable Float delta, @Nullable String key,
            @Nullable Vec3d local, int modifiers) {
        interactions.elementEvent(elementKey, input, delta, key, local, modifiers);
    }

    /**
     * Selects the active tool, or ends tool mode with null. The owner of the previous tool is sent
     * {@code tool.state{active: null}} and the owner of the new one {@code tool.state{active: key}}.
     * A key that names no live, visible tool ends tool mode. Tool mode also ends when the active
     * tool's object is removed.
     */
    @ClientThread
    public void setActiveTool(@Nullable String toolKey) {
        interactions.setActiveTool(toolKey);
    }

    /** Returns the key of the active tool, or null when tool mode is off. */
    @ClientThread
    public @Nullable String activeTool() {
        return interactions.activeTool();
    }

    /**
     * Sets the player's value for a bot-declared setting, saves it per settings object key (which
     * holds the bot identity) and sends {@code settings.values} to the owning session.
     *
     * @param settingsKey full key of a {@code core:settings} object
     * @param key the setting's key within the group
     * @param value a {@code Boolean} for bool, a whole {@code Number} for int, a {@code Number} for
     *     float, a {@code String} for string and choice, and a packed {@code 0xAARRGGBB} number or
     *     {@code #rrggbb} / {@code #rrggbbaa} for color
     * @throws IllegalArgumentException when the group or key is unknown, or the value breaks the
     *     declaration (type, range or options); the message is fit to show the player
     */
    @ClientThread
    public void setSettingValue(String settingsKey, String key, Object value) {
        engine.setSettingValue(settingsKey, key, value);
    }

    /** Returns bot presence records. */
    @ClientThread
    public PresenceView presence() {
        return presence;
    }

    /** Returns the player's layer toggles. */
    @ClientThread
    public LayerControls layers() {
        return layers;
    }

    /** Returns the player's attach controls. */
    @ClientThread
    public AttachControls attach() {
        return attach;
    }

    /** Returns the focused bot. */
    @ClientThread
    public FocusControls focus() {
        return focus;
    }

    /**
     * Returns the latest variables: every {@code core:vars} map, published with
     * the render snapshot. A new instance with a larger version follows each change.
     */
    @AnyThread
    public Variables variables() {
        return engine.variables();
    }

    /**
     * Sets whether new local devices ask the player before connecting. Off by
     * default ({@link BridgeConfig#askLocalApproval()}): local devices that prove the local token are
     * approved and remembered automatically.
     */
    @ClientThread
    public void setAskLocalApproval(boolean ask) {
        engine.setAskLocalApproval(ask);
    }

    /**
     * Returns the pairing controls: pairing codes and URIs for remote devices.
     */
    @ClientThread
    public PairingControls pairing() {
        return pairing;
    }

    /** Returns the devices with their permissions and quotas. */
    @ClientThread
    public DeviceControls devices() {
        return devices;
    }

    /** Returns the instance identity: certificate and fingerprint. */
    @AnyThread
    public InstanceIdentity identity() {
        return identity;
    }

    /** Returns the bridge event bus. */
    @AnyThread
    public EventBus events() {
        return events;
    }

    /**
     * Proposes an edit of a bot- or controller-owned object to its writer ({@code obj.edit.propose}).
     *
     * @param fields MessagePack map from field index to proposed value
     * @return completes on the client thread with the writer's answer, or fails with a {@link
     *     BridgeException} ({@code timeout} after the schema timeout of 10 s, {@code disconnected},
     *     {@code superseded} or the peer's error)
     */
    @ClientThread
    public CompletableFuture<ObjEditResult> proposeEdit(String key, long baseRev, byte[] fields) {
        return playerObjects.proposeEdit(key, baseRev, fields);
    }

    /**
     * Applies a shared op on behalf of the player, broadcasting the change to subscribed sessions.
     *
     * @throws BridgeException with {@code conflict} (details {@code {rev}}), {@code not_owner},
     *     {@code quota_exceeded} (the shared object or data limit is reached),
     *     {@code invalid_message}, {@code unknown_kind}, or a retryable {@code unsupported} while the
     *     world store is still loading
     */
    @ClientThread
    public SharedOpResult sharedOp(SharedOpBody op) {
        return playerObjects.sharedOp(op);
    }

    /**
     * Writes a persistent player object under {@code player/}; the revision increments
     * automatically.
     *
     * @param data kind data encoded with the kind's codec
     * @throws IllegalArgumentException when the key or data is invalid
     */
    @ClientThread
    public void putPlayerObject(
            String key, KindType<?> kind, @Nullable String layer, Scope scope, @Nullable WorldKey world, byte[] data) {
        playerObjects.put(key, kind, layer, scope, world, data);
    }

    /**
     * Removes a player object.
     *
     * @return whether it existed
     */
    @ClientThread
    public boolean removePlayerObject(String key) {
        return playerObjects.remove(key);
    }

    /**
     * Returns the undo and redo history of the player's edits of shared, player and bot objects.
     * Edits made through {@link #sharedOp}, {@link #putPlayerObject} and
     * {@link #proposeEdit} are not recorded; the UI records undoable edits through the history.
     */
    @ClientThread
    public HistoryControls history() {
        return history;
    }

    /**
     * Returns the spatial index over the zones, points and routes in the current render snapshot:
     * shared ones, the player's and those of attached bots in the player's world.
     * The instance is rebuilt only when the snapshot changes.
     */
    @ClientThread
    public SpatialIndex spatial() {
        return engine.spatial();
    }

    /**
     * Returns the inspector views: connections, sessions, the object browser,
     * the traffic log and the cost metrics. The renderer reports its per-frame costs into {@link
     * InspectorControls#metrics()}.
     */
    @ClientThread
    public InspectorControls inspector() {
        return inspector;
    }

    /**
     * Returns the recorder: recording of raw frames per connection to
     * {@code <config>/mineflayer_bridge/recordings/} and replay of a recording into a virtual
     * connection.
     */
    @ClientThread
    public RecorderControls recorder() {
        return recorder;
    }

    /**
     * Returns the client sample controls. The client feeds the player's
     * position, look, crosshair target and held item every tick while any bot wants them.
     */
    @ClientThread
    public ClientSampleControls samples() {
        return samples;
    }

    /** Returns the HUD panel placements the player saved in the HUD editor. */
    @ClientThread
    public HudLayouts hudLayouts() {
        return hudLayouts;
    }

    /** Returns diagnostics for every live or detached session. */
    @ClientThread
    public List<SessionStats> sessionStats() {
        return engine.sessionStats();
    }

    /** Returns the number of inbound frames waiting for intake across all connections. */
    @ClientThread
    public int queuedInboundFrames() {
        return engine.queuedInboundFrames();
    }

    /** Returns whether every world store requested so far has been loaded. */
    @ClientThread
    public boolean storesLoaded() {
        return engine.storesLoaded();
    }

    /**
     * Returns the settings a mod extension registered, in registration order; changing one saves it.
     *
     * @param extensionId the extension id
     * @return its settings, empty when it registered none
     */
    @ClientThread
    public List<SettingValue<?>> extensionSettings(String extensionId) {
        return engine.extensions().settings(extensionId);
    }

    /** Returns the bound port, useful when the configured port was 0. */
    @AnyThread
    public int boundPort() {
        return server.port();
    }

    /** Returns the {@code endpoint.json} path. */
    @AnyThread
    public Path endpointFile() {
        return endpointFile;
    }

    /** Returns the configuration this bridge was started with. */
    @AnyThread
    public BridgeConfig config() {
        return config;
    }

    /** Returns the host environment this bridge was started with. */
    @AnyThread
    public HostEnvironment host() {
        return host;
    }

    /**
     * Says goodbye to every connection, stops the server, flushes pending file writes and deletes
     * {@code endpoint.json}, whose token is of no use any more. Idempotent. Blocks for up to a few
     * seconds.
     */
    @ClientThread
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.shutdown();
        server.close();
        persistence.close();
        blobWorker.shutdownNow();
        try {
            Files.deleteIfExists(endpointFile);
        } catch (IOException e) {
            host.logger().warn("Cannot delete {}: {}", endpointFile, e.toString());
        }
    }
}
