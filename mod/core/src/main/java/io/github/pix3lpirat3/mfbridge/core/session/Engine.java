package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.AttachControls;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.BridgeConfig;
import io.github.pix3lpirat3.mfbridge.core.FocusControls;
import io.github.pix3lpirat3.mfbridge.core.HostEnvironment;
import io.github.pix3lpirat3.mfbridge.core.LayerControls;
import io.github.pix3lpirat3.mfbridge.core.PlayerControls;
import io.github.pix3lpirat3.mfbridge.core.PresenceView;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.SessionStats;
import io.github.pix3lpirat3.mfbridge.core.auth.DeviceRegistry;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobCache;
import io.github.pix3lpirat3.mfbridge.core.chat.ChatControls;
import io.github.pix3lpirat3.mfbridge.core.command.CommandControls;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerControls;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.diag.InspectorControls;
import io.github.pix3lpirat3.mfbridge.core.diag.Metric;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficLog;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.history.EditHistory;
import io.github.pix3lpirat3.mfbridge.core.history.HistoryControls;
import io.github.pix3lpirat3.mfbridge.core.history.PlayerObjects;
import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.InteractionControls;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptControls;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayouts;
import io.github.pix3lpirat3.mfbridge.core.persist.BotSettingsStore;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistenceService;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.query.ClientQueries;
import io.github.pix3lpirat3.mfbridge.core.record.RecorderControls;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingControls;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.sample.ClientSampleControls;
import io.github.pix3lpirat3.mfbridge.core.screen.ScreenControls;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.transport.BridgeServer;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.core.world.PresenceRegistry;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.List;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The protocol engine: it builds the parts, ties them together and runs them once per tick. What
 * each part does is in its own class; this one only decides the order.
 *
 * <p>Every part's state is confined to the client thread. Nothing in this package locks: the
 * network threads only put frames into each connection's queue ({@link #netListener()}), and the
 * published snapshots ({@link #renderSnapshot()}, {@link #declarations()}, {@link #variables()})
 * are immutable and may be read from any thread. Everything else is called on the client thread,
 * from {@link #tick(long)} or from the UI.
 */
@ClientThread
public final class Engine {
    private final BridgeConfig config;
    private final BridgeClock clock;
    private final ExtensionHost extensions;
    private final ObjectStore store;
    private final BlobCache blobs;
    private final SurfaceGate gate;
    private final PresenceRegistry presence = new PresenceRegistry();

    private final SessionTable table;
    private final Outbox outbox;
    private final Intake intake;
    private final Handshake handshake;
    private final SessionLifecycle lifecycle;
    private final ObjectWrites writes;
    private final SharedObjects shared;
    private final WorldStores stores;
    private final PlayerEdits edits;
    private final PlayerView view;
    private final ExtensionLinks links;
    private final ClientQueryService clientQueries;
    private final RenderPublisher render;
    private final DeclarationPublisher declarations;
    private final VariablePublisher vars;

    private final InputQueue input;
    private final Interactions interactions;
    private final Containers containers;
    private final Screens screens;
    private final Intercepts intercepts;
    private final CommandService commands;
    private final ChatRouter chat;
    private final BotSettings botSettings;
    private final Streams streams;
    private final Samples samples;
    private final Recorder recorder;
    private final Inspector inspector;
    private final Devices access;
    private final Pairing pairing;
    private final EditHistory history;
    private final HudLayouts hudLayouts;

    private int boundPort;
    private long blobVersionSeen = -1;
    private long prunedMs;

    /**
     * Creates the engine.
     *
     * @param settings the player's stored values for bot-declared settings
     * @param localToken decoded {@code localToken} written to {@code endpoint.json}
     * @param blobs the blob cache that streams write into
     * @param blobWorker executor for stream decoding and hashing, off the client thread
     * @param identity the instance certificate, whose fingerprint {@code pair.result} carries
     */
    public Engine(
            BridgeConfig config,
            HostEnvironment host,
            ExtensionHost extensions,
            WorldMatchers matchers,
            EventBus events,
            DeviceRegistry devices,
            PersistenceService persistence,
            PlayerPrefs prefs,
            BotSettingsStore settings,
            byte[] localToken,
            BlobCache blobs,
            Executor blobWorker,
            InstanceIdentity identity) {
        Logger log = host.logger();
        this.config = config;
        this.clock = config.clock();
        this.extensions = extensions;
        this.blobs = blobs;

        // State and the way out first: nearly everything else needs them.
        StoreEvents storeEvents = new StoreEvents(log, extensions);
        this.store = new ObjectStore(storeEvents);
        this.table = new SessionTable(clock);
        TrafficLog traffic = new TrafficLog();
        this.recorder = new Recorder(clock, table, store, config.bridgeDir().resolve("recordings"), log);
        this.outbox = new Outbox(config, log, clock, table, traffic, recorder);
        this.access = new Devices(clock, table, settings, devices, Quotas.defaults(config.maxObjectsPerSession(),
                config.maxBlobBytes(), config.streamBytesInFlight(), config.messagesPerSecond(), config.bytesPerSecond()));
        this.pairing = new Pairing(clock, Handshake.RANDOM, identity, config.advertiseHost(), config.bindAddress(),
                () -> boundPort == 0 ? config.port() : boundPort);
        OwnerQuotas ownerQuotas = new OwnerQuotas(config.maxSharedObjects(), config.maxSharedBytes());
        this.gate = new SurfaceGate(config.cancelCooldownsMs(), config.cancelResetMs(), config.solicitedWindowMs());
        Permits permits = new Permits(store, access, table, outbox);
        this.view = new PlayerView(clock, matchers, prefs, table, outbox);

        // The services see the engine through one small interface.
        EngineHost services = new EngineHost(clock, table, outbox, view, permits);
        this.input = new InputQueue(services, events, gate, config.maxQueuedInputs(), config.maxInputTimeoutMs(),
                config.chatPromptWindowMs());
        this.interactions = new Interactions(services, store, gate);
        this.containers = new Containers(services, store, events, gate);
        this.screens = new Screens(services, store, interactions, prefs, gate);
        this.intercepts = new Intercepts(services, interactions, prefs);
        this.commands = new CommandService(services, store, interactions);
        this.chat = new ChatRouter(services, input, interactions, commands, events, gate);
        this.botSettings = new BotSettings(services, store, settings);
        this.streams = new Streams(services, table, blobs, blobWorker);
        this.samples = new Samples(services, table);

        this.render = new RenderPublisher(store, view.layers(), blobs, view);
        this.declarations = new DeclarationPublisher(view, store, botSettings);
        this.vars = new VariablePublisher();
        this.inspector = new Inspector(clock, table, access, view, store, render, traffic);
        this.hudLayouts = new HudLayouts(prefs);

        this.stores = new WorldStores(log, extensions, persistence, store);
        this.writes = new ObjectWrites(extensions, store, ownerQuotas, access, table, outbox, permits);
        this.shared = new SharedObjects(matchers, persistence, store, ownerQuotas, access, table, outbox, permits, stores,
                writes);
        this.edits = new PlayerEdits(clock, extensions, persistence, store, table, outbox, view, stores, writes, shared);
        this.history = new EditHistory(edits.target(), edits.storage(), clock::currentTimeMillis,
                HistoryControls.DEFAULT_CAPACITY);
        this.lifecycle = new SessionLifecycle(config, clock, events, store, presence, input, interactions, containers,
                screens, botSettings, ownerQuotas, settings, access, streams, recorder, table, outbox, view, stores, shared);
        this.handshake = new Handshake(config, host, log, clock, extensions, events, devices, identity, access, pairing,
                table, outbox, localToken);
        this.links = new ExtensionLinks(table, outbox);
        this.clientQueries = new ClientQueryService(permits, view);
        Dispatcher dispatcher = new Dispatcher(host, log, clock, events, presence, input, interactions, containers,
                screens, chat, streams, samples, outbox, permits, view, writes, shared, lifecycle, handshake, links,
                clientQueries);
        this.intake = new Intake(log, clock, access, streams, recorder, traffic, table, outbox, stores, dispatcher);

        // Parts that need each other are tied here, once everything exists.
        services.declarations(declarations);
        view.watch(render, declarations);
        access.bind(permits, lifecycle, intercepts);
        storeEvents.bind(render, declarations, vars, blobs, ownerQuotas, view, botSettings, interactions);

        stores.ensureLoading(ServerKeys.GLOBAL, true);
        history.switchStore(ServerKeys.GLOBAL);
    }

    /**
     * Runs one tick: intake under {@code budgetNanos} of real time (at least one frame is always
     * processed when any is queued), store loads, approvals, timers, interest, the snapshots and one
     * flush per connection.
     */
    public void tick(long budgetNanos) {
        long tickStart = System.nanoTime();
        long now = clock.millis();
        intake.accept(now);
        recorder.tick(now);
        intake.drain(budgetNanos, now);
        inspector.metrics().record(Metric.TICK_INTAKE, System.nanoTime() - tickStart);
        streams.tick();
        long blobVersion = blobs.version();
        if (blobVersion != blobVersionSeen) {
            // Objects waiting for a blob render once it arrives.
            blobVersionSeen = blobVersion;
            render.blobsChanged();
        }
        if (stores.applyLoads()) {
            shared.resyncAll();
        }
        history.poll();
        handshake.pollApprovals();
        timers(now);
        view.focus().refresh();
        view.refreshInterest();
        vars.publish();
        render.publish(now);
        // Removed objects are out of the snapshot now, so their handles can go to new ones.
        store.recycleHandles();
        declarations.publish(table.writersInOrder(), now);
        samples.tick(now);
        outbox.flush(now);
        lifecycle.reap(now);
        intake.connectionsChanged();
        inspector.metrics().record(Metric.TICK_TOTAL, System.nanoTime() - tickStart);
    }

    private void timers(long now) {
        store.expire(now);
        outbox.keepAlive(now);
        lifecycle.expireRequests(now);
        writes.expireTransactions(table, now);
        input.expire(now);
        if (now - prunedMs >= 60_000) {
            prunedMs = now;
            gate.prune(now);
            intake.prune(now);
        }
        interactions.expire(now);
        commands.tick(now);
        containers.tick(now);
        screens.tick(now);
        lifecycle.expireDetached(now);
    }

    /** Sends {@code goodbye{shutdown, retry: true}} to every connection and flushes. */
    public void shutdown() {
        long now = clock.millis();
        intake.accept(now);
        for (Connection c : table.connections) {
            outbox.goodbye(c, "shutdown", "the bridge is shutting down", true);
        }
        outbox.flush(now);
        recorder.shutdown();
    }

    /** Returns the listener to hand to {@link BridgeServer}; it only enqueues. */
    @AnyThread
    public BridgeServer.Listener netListener() {
        return intake.netListener();
    }

    /** Records the port the server bound, for pairing URIs. */
    public void boundPort(int port) {
        boundPort = port;
    }

    /**
     * Sets the player's world; null on the title screen. Sessions get new {@code player.context} and
     * {@code interest} on the next tick, and the world's store starts loading.
     */
    public void setPlayerContext(@Nullable WorldKey world) {
        if (!view.setWorld(world)) {
            return;
        }
        if (world != null) {
            stores.ensureLoading(ServerKeys.normalize(world.server()), true);
        }
        // One undo history per world store.
        history.switchStore(WorldStores.storeKeyFor(null, world));
    }

    /** Tells the surface gate that the player asked this owner for something. */
    public void playerAsked(String owner) {
        gate.asked(owner.endsWith("/") ? owner.substring(0, owner.length() - 1) : owner, clock.millis());
    }

    /** Stores a setting value the player chose and tells the bot that declared the setting. */
    public void setSettingValue(String settingsKey, String key, Object value) {
        botSettings.set(settingsKey, key, value);
        gate.asked(ObjectKeys.parse(settingsKey).owner(), clock.millis());
    }

    /** Sets what answers client queries; null answers them {@code unsupported}. */
    public void setClientQueries(@Nullable ClientQueries queries) {
        clientQueries.set(queries);
    }

    /**
     * Sets whether new local devices ask the player before they connect; when off they are approved
     * and remembered automatically.
     */
    public void setAskLocalApproval(boolean ask) {
        handshake.setAskLocalApproval(ask);
    }

    /** Returns the position an owner's bot last reported with {@code world.report}, or null. */
    public @Nullable Vec3d reportedPosition(String owner) {
        return lifecycle.reportedPosition(owner);
    }

    /**
     * Returns the session of an owner as extension handlers see it, so a mod extension can message
     * the Node half first, or null while no session writes for that owner.
     */
    public @Nullable MessageContext sessionContext(String owner) {
        return links.sessionContext(owner);
    }

    /** Returns the latest published render snapshot. */
    @AnyThread
    public RenderSnapshot renderSnapshot() {
        return render.snapshot();
    }

    /** Returns the latest published declaration snapshot. */
    @AnyThread
    public DeclarationSnapshot declarations() {
        return declarations.snapshot();
    }

    /** Returns the latest variables. */
    @AnyThread
    public Variables variables() {
        return vars.variables();
    }

    /**
     * Returns the spatial index of the latest render snapshot's zones, points and routes, rebuilt
     * when one of them changes.
     */
    public SpatialIndex spatial() {
        return render.spatial();
    }

    /** Returns diagnostics for every live or detached session. */
    public List<SessionStats> sessionStats() {
        return table.sessionStats();
    }

    /** Returns the number of inbound frames waiting across all connections. */
    public int queuedInboundFrames() {
        return table.queuedInboundFrames();
    }

    /** Returns whether every world store requested so far has been loaded and applied. */
    public boolean storesLoaded() {
        return stores.loaded();
    }

    /** Returns the store, for tests. */
    public ObjectStore store() {
        return store;
    }

    /** Returns the mod extensions registered at start. */
    public ExtensionHost extensions() {
        return extensions;
    }

    /** Returns the player's world and state, as the client reports them. */
    public PlayerControls player() {
        return view;
    }

    /** Returns what the player writes: shared ops, player objects and edit proposals. */
    public PlayerObjects playerObjects() {
        return edits;
    }

    /** Returns the actions, commands, keybinds, tools and elements the player can trigger. */
    public InteractionControls interactions() {
        return interactions;
    }

    /** Returns the input request queue. */
    public InputControls input() {
        return input;
    }

    /** Returns the container view controls. */
    public ContainerControls containers() {
        return containers;
    }

    /** Returns the declared-screen controls. */
    public ScreenControls screens() {
        return screens;
    }

    /** Returns the interaction intercepts. */
    public InterceptControls intercepts() {
        return intercepts;
    }

    /** Returns the chat bridge. */
    public ChatControls chat() {
        return chat;
    }

    /** Returns the command engine controls. */
    public CommandControls commands() {
        return commands;
    }

    /** Returns the layer controls. */
    public LayerControls layers() {
        return view.layers();
    }

    /** Returns the attach controls. */
    public AttachControls attach() {
        return view.attach();
    }

    /** Returns the focused bot controls. */
    public FocusControls focus() {
        return view.focus();
    }

    /** Returns the presence registry. */
    public PresenceView presence() {
        return presence;
    }

    /** Returns the pairing controls. */
    public PairingControls pairing() {
        return pairing;
    }

    /** Returns the device, permission and quota controls. */
    public DeviceControls devices() {
        return access;
    }

    /** Returns the undo and redo history of player edits. */
    public HistoryControls history() {
        return history;
    }

    /** Returns the inspector views and metrics. */
    public InspectorControls inspector() {
        return inspector;
    }

    /** Returns the recorder. */
    public RecorderControls recorder() {
        return recorder;
    }

    /** Returns the client sample controls. */
    public ClientSampleControls samples() {
        return samples;
    }

    /** Returns the saved HUD panel placements. */
    public HudLayouts hudLayouts() {
        return hudLayouts;
    }
}
