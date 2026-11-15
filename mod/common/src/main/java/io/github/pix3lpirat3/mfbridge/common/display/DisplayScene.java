package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.display.logic.Animation;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.protocol.core.GroupData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * The displays, groups and particle emitters of the current render snapshot, by object key, plus
 * nodes other UI parts add for one frame (world menu entries).
 * {@link #sync} follows the snapshot when its version changes, touching only the entries the
 * snapshot lists as changed, and {@link #resolve} computes every
 * node's world transform once per frame: groups first, children relative to them, following entities
 * and the camera for billboards. Client thread only.
 */
final class DisplayScene {
    /** Deepest group nesting followed; deeper parents are ignored, which also breaks cycles. */
    private static final int MAX_DEPTH = 8;
    private static final float DEG = (float) (Math.PI / 180d);

    private final Map<String, DisplayNode> byKey = new HashMap<>();
    private final ArrayList<DisplayNode> nodes = new ArrayList<>();
    private final ArrayList<DisplayNode> extra = new ArrayList<>();
    private final Set<String> live = new HashSet<>();
    private static final Comparator<DisplayNode> KEY_ORDER = Comparator.comparing(n -> n.key);
    private final Consumer<DisplayNode> disposer;
    private long version = -1L;
    private int frame;
    private float cameraYaw;
    private float cameraPitch;
    private float partialTick;
    private @Nullable ClientLevel level;
    private final Quaternionf scratch = new Quaternionf();
    private final Vector3d vector = new Vector3d();

    DisplayScene(final Consumer<DisplayNode> disposer) {
        this.disposer = disposer;
    }

    /**
     * Follows a new snapshot: creates nodes for new objects, updates those whose revision changed and
     * disposes those that left. Returns at once when the version did not change.
     */
    void sync(final RenderSnapshot snapshot, final long nowMs) {
        if (snapshot.version() == version) {
            return;
        }
        final RenderSnapshot.Change change = snapshot.changesSince(version);
        version = snapshot.version();
        boolean membership = false;
        if (change == null) {
            // The snapshots in between were missed: go through every entry.
            live.clear();
            for (final RenderEntry e : snapshot.entries()) {
                if (upsert(e, nowMs)) {
                    membership = true;
                }
                if (DisplayNode.kindOf(e.data()) != null) {
                    live.add(e.key());
                }
            }
            final var it = byKey.values().iterator();
            while (it.hasNext()) {
                final DisplayNode n = it.next();
                if (!live.contains(n.key)) {
                    disposer.accept(n);
                    it.remove();
                    membership = true;
                }
            }
            live.clear();
        } else {
            for (final RenderEntry e : change.removed()) {
                membership |= remove(e.key());
            }
            for (final RenderEntry e : change.upserted()) {
                membership |= upsert(e, nowMs);
            }
        }
        if (membership) {
            nodes.clear();
            nodes.addAll(byKey.values());
            nodes.sort(KEY_ORDER);
        }
    }

    /** Creates or updates the node of an entry; returns whether the set of nodes changed. */
    private boolean upsert(final RenderEntry e, final long nowMs) {
        final Object data = e.data();
        final DisplayNode.Kind kind = DisplayNode.kindOf(data);
        if (kind == null || data == null) {
            // The key may have held a display before.
            return remove(e.key());
        }
        boolean added = false;
        DisplayNode node = byKey.get(e.key());
        if (node == null || node.kind != kind) {
            if (node != null) {
                disposer.accept(node);
            }
            final String layer = e.layer();
            final String prefix = layer == null ? null : e.owner() + '/' + e.extension() + '/' + layer + '/';
            node = new DisplayNode(e.key(), e.owner(), prefix, kind, data);
            byKey.put(node.key, node);
            added = true;
        }
        if (node.rev != e.rev()) {
            node.update(data, e.traits(), e.rev(), nowMs);
        }
        return added;
    }

    private boolean remove(final String key) {
        final DisplayNode node = byKey.remove(key);
        if (node == null) {
            return false;
        }
        disposer.accept(node);
        return true;
    }

    /** Disposes every node, for example when the core stops. */
    void clear() {
        for (final DisplayNode n : byKey.values()) {
            disposer.accept(n);
        }
        byKey.clear();
        nodes.clear();
        version = -1L;
    }

    /** Returns the snapshot-backed nodes in key order. */
    List<DisplayNode> nodes() {
        return nodes;
    }

    /** Returns the nodes added for this frame with {@link #addExtra}. */
    List<DisplayNode> extra() {
        return extra;
    }

    /** Adds a node that is drawn and resolved like the others until the next {@link #clearExtra}. */
    void addExtra(final DisplayNode node) {
        extra.add(node);
    }

    /** Drops the nodes added with {@link #addExtra}. */
    void clearExtra() {
        extra.clear();
    }

    /** Returns the node with a full object key, or null. */
    @Nullable DisplayNode byKey(final String key) {
        return byKey.get(key);
    }

    /**
     * Starts a frame: the next {@link #resolve} calls compute fresh transforms.
     *
     * @param yaw camera yaw, degrees
     * @param pitch camera pitch, degrees
     */
    void beginFrame(final @Nullable ClientLevel newLevel, final float yaw, final float pitch, final float newPartialTick) {
        frame++;
        level = newLevel;
        cameraYaw = yaw;
        cameraPitch = pitch;
        partialTick = newPartialTick;
    }

    /**
     * Starts a client tick: the next {@link #resolve} calls compute fresh transforms at the entities'
     * current positions, for the emitters, which are not drawn.
     */
    void beginTick(final ClientLevel newLevel) {
        frame++;
        level = newLevel;
        partialTick = 1f;
    }

    /**
     * Computes a node's world transform for this frame, resolving its parent group first.
     *
     * @param hoverScale extra scale while hovered, 1 otherwise
     */
    void resolve(final DisplayNode n, final long nowMs, final double hoverScale) {
        resolve(n, nowMs, hoverScale, 0);
    }

    private void resolve(final DisplayNode n, final long nowMs, final double hoverScale, final int depth) {
        if (n.resolvedFrame == frame || n.resolving) {
            return;
        }
        n.resolving = true;
        try {
            n.sample(nowMs);
            final DisplayFields f = n.fields;
            if (f == null) {
                n.wx = n.pose.x;
                n.wy = n.pose.y;
                n.wz = n.pose.z;
                n.rotation.identity();
                n.resolvedFrame = frame;
                return;
            }
            final Animation.Sample anim = n.animation;
            f.animation().sample(f.amplitude(), Integer.toUnsignedLong(f.periodMs()), nowMs, anim);
            double px = n.pose.x;
            double py = n.pose.y + anim.dy;
            double pz = n.pose.z;
            final Quaternionf rot = n.rotation.identity();
            float psx = 1f;
            float psy = 1f;
            float psz = 1f;
            final String groupKey = n.groupKey;
            final DisplayNode parent = groupKey == null || depth >= MAX_DEPTH ? null : byKey.get(groupKey);
            boolean placed = false;
            if (parent != null && parent.kind == DisplayNode.Kind.GROUP && !parent.resolving) {
                resolve(parent, nowMs, 1d, depth + 1);
                final Vector3d local = vector.set(px * parent.scaleX, py * parent.scaleY, pz * parent.scaleZ);
                parent.rotation.transform(local);
                px = parent.wx + local.x;
                py = parent.wy + local.y;
                pz = parent.wz + local.z;
                rot.set(parent.rotation);
                psx = parent.scaleX;
                psy = parent.scaleY;
                psz = parent.scaleZ;
                placed = true;
            }
            if (!placed && n.data instanceof GroupData g) {
                final Entity followed = followed(g, n.owner);
                if (followed != null) {
                    final double ex = Mth.lerp(partialTick, followed.xOld, followed.getX());
                    final double ey = Mth.lerp(partialTick, followed.yOld, followed.getY());
                    final double ez = Mth.lerp(partialTick, followed.zOld, followed.getZ());
                    // A following group's pos is an offset from the followed entity's feet.
                    px += ex;
                    py += ey;
                    pz += ez;
                }
            }
            billboard(f.billboard(), rot);
            final double ox = n.pose.ox * psx;
            final double oy = n.pose.oy * psy;
            final double oz = n.pose.oz * psz;
            final Vector3d off = vector.set(ox, oy, oz);
            rot.transform(off);
            n.wx = px + off.x;
            n.wy = py + off.y;
            n.wz = pz + off.z;
            scratch.set((float) n.pose.qx, (float) n.pose.qy, (float) n.pose.qz, (float) n.pose.qw);
            rot.mul(scratch);
            if (anim.yaw != 0d) {
                rot.rotateY((float) anim.yaw);
            }
            if (anim.roll != 0d) {
                rot.rotateZ((float) anim.roll);
            }
            final double s = anim.scale * hoverScale;
            n.scaleX = (float) (psx * n.pose.sx * s);
            n.scaleY = (float) (psy * n.pose.sy * s);
            n.scaleZ = (float) (psz * n.pose.sz * s);
            n.resolvedFrame = frame;
        } finally {
            n.resolving = false;
        }
    }

    /**
     * Applies a billboard mode the way vanilla display entities do (26.3 {@code DisplayRenderer}):
     * the camera's yaw, pitch or both replace the parent orientation.
     */
    private void billboard(final String mode, final Quaternionf rot) {
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "vertical" -> rot.rotationYXZ(-DEG * (cameraYaw - 180f), 0f, 0f);
            case "horizontal" -> rot.rotationYXZ(0f, DEG * -cameraPitch, 0f);
            case "center" -> rot.rotationYXZ(-DEG * (cameraYaw - 180f), DEG * -cameraPitch, 0f);
            default -> {
            }
        }
    }

    private @Nullable Entity followed(final GroupData g, final String owner) {
        final ClientLevel l = level;
        if (l == null) {
            return null;
        }
        final Integer id = g.followEntity();
        if (id != null) {
            return l.getEntity(id);
        }
        return g.followSelf() ? botPlayer(l, owner) : null;
    }

    /**
     * Finds the owning bot's player entity, for {@code follow: 'self'}: by UUID when the
     * bot identity is one, otherwise by the username part of {@code username@server}.
     */
    static @Nullable Player botPlayer(final ClientLevel level, final String owner) {
        if (!owner.startsWith("bot:")) {
            return null;
        }
        final String identity = owner.substring(4);
        final int at = identity.lastIndexOf('@');
        if (at < 0) {
            try {
                return level.getPlayerByUUID(UUID.fromString(identity));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
        final String name = identity.substring(0, at);
        for (final Player p : level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }
}
