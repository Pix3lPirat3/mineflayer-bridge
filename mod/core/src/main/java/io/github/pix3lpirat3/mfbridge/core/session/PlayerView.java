package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.PlayerControls;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.history.EditHistory;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.core.world.AttachState;
import io.github.pix3lpirat3.mfbridge.core.world.DetailPolicy;
import io.github.pix3lpirat3.mfbridge.core.world.FocusState;
import io.github.pix3lpirat3.mfbridge.core.world.LayerRegistry;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Detail;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.PlayerContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where the player is and what they see: their world and state, which sessions are attached, the
 * layers and the focused bot. It sends each session its {@code player.context} and {@code interest}
 * and tells the snapshot publishers when visibility changed.
 */
@ClientThread
final class PlayerView implements Sight, PlayerControls {
    private static final String SHARED_OWNER = "shared";
    private static final String PLAYER_OWNER = "player";
    private final LayerRegistry layers;
    private final AttachState attach;
    private final FocusState focus;
    private long detailCheckedMs = Long.MIN_VALUE / 2;
    private double playerX = Double.NaN;
    private double playerY;
    private double playerZ;
    private @Nullable WorldKey playerWorld;
    private @Nullable String playerName;
    private @Nullable String playerUuid;
    private boolean playerAlive = true;
    private boolean playerAway;
    private boolean playerWindowFocused = true;
    private boolean allInterestDirty;

    private final BridgeClock clock;
    private final WorldMatchers matchers;
    private final SessionTable table;
    private final Outbox outbox;

    private @Nullable RenderPublisher render;
    private @Nullable DeclarationPublisher declarations;

    PlayerView(BridgeClock clock, WorldMatchers matchers, PlayerPrefs prefs, SessionTable table, Outbox outbox) {
        this.clock = clock;
        this.matchers = matchers;
        this.table = table;
        this.outbox = outbox;
        this.layers = new LayerRegistry(prefs, this::ownerChanged);
        this.attach = new AttachState(prefs, this::identityMatched, this::attachChanged);
        this.focus = new FocusState(this::attachedBotOwners, this::focusChanged);
    }

    /** Sets the snapshot publishers to tell when visibility changes; they are built after this view. */
    void watch(RenderPublisher render, DeclarationPublisher declarations) {
        this.render = render;
        this.declarations = declarations;
    }

    /** Returns the focused bot state. */
    FocusState focus() {
        return focus;
    }

    List<String> attachedBotOwners() {
        List<Session> bots = attachedBots();
        List<String> out = new ArrayList<>(bots.size());
        for (Session s : bots) {
            out.add(s.owner);
        }
        return out;
    }

    void focusChanged() {
        allInterestDirty = true;
        visibilityChanged();
    }

    /** Who or what the player sees changed as a whole: both snapshots look at everything again. */
    void visibilityChanged() {
        if (render != null && declarations != null) {
            render.allChanged();
            declarations.allChanged();
        }
    }

    /** Returns the layers. */
    LayerRegistry layers() {
        return layers;
    }

    /** Returns the attach state. */
    AttachState attach() {
        return attach;
    }

    /**
     * Requires that the player has the session attached.
     *
     * @throws BridgeException {@code not_attached}
     */
    void requireAttached(Session s) {
        if (!attachedNow(s)) {
            throw new BridgeException("not_attached", "the player has not attached " + s.displayName);
        }
    }

    /** Returns whether a bot session reported the world the player is in. */
    boolean worldMatched(Session s) {
        WorldKey player = playerWorld;
        WorldKey bot = s.world;
        return s.kind == SessionKind.BOT && player != null && bot != null && matchers.matches(player, bot);
    }

    boolean attached(Session s, boolean matched) {
        return s.kind == SessionKind.CONTROLLER || attach.isAttached(Objects.requireNonNull(s.identity), matched);
    }

    boolean identityMatched(String identity) {
        Session w = table.writers.get("bot:" + identity + '/');
        return w != null && worldMatched(w);
    }

    void attachChanged(@Nullable String identity) {
        if (identity == null) {
            visibilityChanged();
            allInterestDirty = true;
        } else {
            ownerChanged("bot:" + identity);
        }
    }

    /** Marks the owner's writer for a new {@code interest} and the render snapshot stale. */
    void ownerChanged(String owner) {
        if (render != null && declarations != null) {
            render.ownerChanged(owner);
            declarations.ownerChanged(owner);
        }
        Session w = table.writers.get(owner + '/');
        if (w != null) {
            w.interestDirty = true;
        }
    }

    /**
     * Sets the player's world; null on the title screen. Sessions get new {@code player.context} and
     * {@code interest} on the next tick.
     *
     * @return whether the world changed
     */
    boolean setWorld(@Nullable WorldKey world) {
        if (Objects.equals(world, playerWorld)) {
            return false;
        }
        playerWorld = world;
        allInterestDirty = true;
        visibilityChanged();
        return true;
    }

    @Override
    public void setState(boolean alive, boolean away, boolean windowFocused) {
        if (alive == playerAlive && away == playerAway && windowFocused == playerWindowFocused) {
            return;
        }
        playerAlive = alive;
        playerAway = away;
        playerWindowFocused = windowFocused;
        allInterestDirty = true;
    }

    @Override
    public void setProfile(@Nullable String name, @Nullable String uuid) {
        if (Objects.equals(name, playerName) && Objects.equals(uuid, playerUuid)) {
            return;
        }
        playerName = name;
        playerUuid = uuid;
        allInterestDirty = true;
    }

    @Override
    public @Nullable WorldKey world() {
        return playerWorld;
    }

    /** Returns the player's world, or null on the title screen. */
    @Nullable WorldKey playerWorld() {
        return playerWorld;
    }

    /** Returns the player's position, or null while it is not known. */
    @Nullable Vec3d position() {
        return Double.isNaN(playerX) ? null : new Vec3d(playerX, playerY, playerZ);
    }

    @Override
    public void setPosition(double x, double y, double z) {
        playerX = x;
        playerY = y;
        playerZ = z;
    }

    private double distanceSqToPlayer(Session s) {
        Vec3d p = s.position;
        if (p == null || Double.isNaN(playerX) || !worldMatched(s)) {
            return Double.NaN;
        }
        double dx = p.x() - playerX;
        double dy = p.y() - playerY;
        double dz = p.z() - playerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Sends {@code player.context} and {@code interest} to the sessions whose view of the player changed. */
    void refreshInterest() {
        long now = clock.millis();
        if (now - detailCheckedMs >= 1000) {
            // Positions and the attached count change without a dirty flag, so detail is re-evaluated each second.
            detailCheckedMs = now;
            allInterestDirty = true;
        }
        boolean all = allInterestDirty;
        allInterestDirty = false;
        int attachedBots = -1;
        for (Connection c : table.connections) {
            if (c.state != Connection.State.READY) {
                continue;
            }
            for (Session s : c.sessions.values()) {
                if (!all && !s.interestDirty) {
                    continue;
                }
                s.interestDirty = false;
                boolean matched = worldMatched(s);
                boolean att = attached(s, matched);
                PlayerContext pc = new PlayerContext(playerWorld != null, playerWorld, att, playerName, playerUuid, playerAlive,
                        playerAway, playerWindowFocused);
                if (!pc.equals(s.lastContext)) {
                    s.lastContext = pc;
                    outbox.sendLatest(s, CoreMessages.PLAYER_CONTEXT, pc);
                }
                if (attachedBots < 0) {
                    attachedBots = attachedBots().size();
                }
                s.reducedDetail = s.kind == SessionKind.BOT
                        && DetailPolicy.reduced(s.reducedDetail, attachedBots, att, distanceSqToPlayer(s));
                // Only the focused bot's sessions hear focused = true.
                Interest in = new Interest(att, matched, s.reducedDetail ? Detail.REDUCED : Detail.FULL,
                        layers.forOwner(s.owner), s.owner.equals(focus.focused()));
                if (!in.equals(s.lastInterest)) {
                    s.lastInterest = in;
                    outbox.sendLatest(s, CoreMessages.INTEREST, in);
                }
            }
        }
    }

    @Override
    public boolean focusAllows(String owner, @Nullable ObjectTraits traits) {
        return !Traits.focusedOnly(traits) || owner.equals(focus.focused());
    }

    @Override
    public boolean ownerVisible(String owner) {
        if (owner.equals(SHARED_OWNER) || owner.equals(PLAYER_OWNER)) {
            return true;
        }
        Session w = table.writer(owner);
        return w != null && attachedNow(w);
    }

    /** Returns the live, attached bot sessions (not controllers), sorted by owned prefix. */
    List<Session> attachedBots() {
        List<Session> out = new ArrayList<>();
        for (Session s : table.writers.values()) {
            if (s.kind == SessionKind.BOT && !s.ended && !s.superseded && attachedNow(s)) {
                out.add(s);
            }
        }
        out.sort((a, b) -> a.ownerPrefix.compareTo(b.ownerPrefix));
        return out;
    }

    /** Returns the live bot sessions, attached or not, sorted by owned prefix. */
    List<Session> liveBots() {
        List<Session> out = new ArrayList<>();
        for (Session s : table.writers.values()) {
            if (s.kind == SessionKind.BOT && !s.ended && !s.superseded) {
                out.add(s);
            }
        }
        out.sort((a, b) -> a.ownerPrefix.compareTo(b.ownerPrefix));
        return out;
    }

    /** Live controller sessions, sorted by owner. Controllers are always attached. */
    List<Session> liveControllers() {
        List<Session> out = new ArrayList<>();
        for (Session s : table.writers.values()) {
            if (s.kind == SessionKind.CONTROLLER && !s.ended && !s.superseded) {
                out.add(s);
            }
        }
        out.sort((a, b) -> a.ownerPrefix.compareTo(b.ownerPrefix));
        return out;
    }

    @Override
    public boolean attachedNow(Session s) {
        return attached(s, worldMatched(s));
    }

    @Override
    public boolean worldVisible(@Nullable WorldKey w) {
        WorldKey player = playerWorld;
        return w != null && player != null && matchers.matches(player, w);
    }

    /**
     * Requires that the player sees {@code o} of session {@code s}: the session is attached and a
     * world-scoped object is in the player's world.
     *
     * @throws BridgeException {@code not_attached} or {@code world_mismatch}
     */
    void requireVisible(Session s, StoredObject o) {
        if (!attachedNow(s)) {
            throw new BridgeException("not_attached", "the player has not attached " + s.displayName);
        }
        if (o.state().scope() == Scope.WORLD && !worldVisible(o.state().world())) {
            throw new BridgeException("world_mismatch", "the player is not in the world of " + o.key());
        }
    }
}
