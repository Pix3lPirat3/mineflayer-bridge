package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.seam.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PickGeometry;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickBlock;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickDirection;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickEntity;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickPath;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickPoint;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickPolygon;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRegion;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The {@link PickMode} for each pick kind. Colours are packed {@code 0xRRGGBBAA}, as draw commands take them. */
final class PickModes {

    static final int OK = 0x55FF55FF;
    static final int BAD = 0xFF5555FF;
    static final int CURSOR = 0xFFFFFFCC;
    static final int CANDIDATE = 0x55AAFFB0;
    static final int LINE = 0xFFD040FF;
    private static final float WIDTH = 2f;
    private static final double INFLATE = 0.004d;

    private PickModes() {
    }

    static void box(final List<DrawCommand> out, final double x0, final double y0, final double z0,
            final double x1, final double y1, final double z1, final int rgba, final float fill) {
        out.add(new DrawCommand.Box(new Vec3d(x0 - INFLATE, y0 - INFLATE, z0 - INFLATE),
                new Vec3d(x1 + INFLATE, y1 + INFLATE, z1 + INFLATE), rgba, fill, DepthMode.BOTH));
    }

    static void block(final List<DrawCommand> out, final Vec3i p, final int rgba, final float fill) {
        box(out, p.x(), p.y(), p.z(), p.x() + 1d, p.y() + 1d, p.z() + 1d, rgba, fill);
    }

    static void line(final List<DrawCommand> out, final double ax, final double ay, final double az,
            final double bx, final double by, final double bz, final int rgba) {
        out.add(new DrawCommand.Line(new Vec3d(ax, ay, az), new Vec3d(bx, by, bz), rgba, WIDTH, DepthMode.BOTH));
    }

    /** {@code point}: the crosshair hit, moved along the ray by scrolling. */
    static final class Point implements PickMode {
        private static final double SCROLL_STEP = 0.5d;
        private final double maxDistance;
        private double offset;
        private Vec3 pos = Vec3.ZERO;

        Point(final double maxDistance) {
            this.maxDistance = maxDistance;
        }

        @Override
        public void update() {
            final Vec3 eye = Crosshair.eye();
            final Crosshair.Hit hit = Crosshair.hit(maxDistance, e -> true);
            final double distance = Math.clamp(eye.distanceTo(hit.location()) + offset, 0.25d, maxDistance);
            pos = eye.add(Crosshair.look().scale(distance));
        }

        @Override
        public boolean click() {
            return true;
        }

        @Override
        public boolean undo() {
            final boolean moved = offset != 0d;
            offset = 0d;
            return moved;
        }

        @Override
        public void scroll(final int steps) {
            offset += steps * SCROLL_STEP;
        }

        @Override
        public @Nullable PickResult result() {
            return new PickResult.Point(new PickPoint(Crosshair.vec(pos)));
        }

        @Override
        public String status() {
            return String.format(Locale.ROOT, "%.1f, %.1f, %.1f%s", pos.x, pos.y, pos.z,
                    offset == 0d ? "" : String.format(Locale.ROOT, " (%+.1f)", offset));
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final double r = 0.08d;
            box(out, pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r, OK, 0.6f);
        }
    }

    /** {@code block}: the block under the crosshair, filtered by id and tag. */
    static final class Block implements PickMode {
        private final double maxDistance;
        private final @Nullable TargetSpec filter;
        private @Nullable BlockPos pos;
        private @Nullable Direction face;
        private Vec3 location = Vec3.ZERO;
        private @Nullable BlockState state;
        private boolean valid;

        Block(final double maxDistance, final @Nullable TargetSpec filter) {
            this.maxDistance = maxDistance;
            this.filter = filter;
        }

        @Override
        public void update() {
            final BlockHitResult hit = Crosshair.blockHit(maxDistance);
            final var level = Minecraft.getInstance().level;
            if (hit == null || level == null) {
                pos = null;
                state = null;
                valid = false;
                return;
            }
            pos = hit.getBlockPos();
            face = hit.getDirection();
            location = hit.getLocation();
            final BlockState s = level.getBlockState(hit.getBlockPos());
            state = s;
            valid = filter == null || TargetMatch.matchesFilter(filter.ids(), filter.tags(), Crosshair.blockId(s),
                    tag -> Crosshair.blockHasTag(s, tag));
        }

        @Override
        public boolean click() {
            return pos != null;
        }

        @Override
        public boolean undo() {
            return false;
        }

        @Override
        public void scroll(final int steps) {
        }

        @Override
        public @Nullable PickResult result() {
            final BlockPos p = pos;
            final Direction f = face;
            final BlockState s = state;
            if (p == null || f == null || s == null || !valid) {
                return null;
            }
            return new PickResult.Block(new PickBlock(Crosshair.vec3i(p), f.getSerializedName().toLowerCase(Locale.ROOT),
                    Crosshair.vec(location), BlockStateParser.serialize(s)));
        }

        @Override
        public String status() {
            final BlockState s = state;
            if (s == null) {
                return I18n.get("mineflayer_bridge.pick.status.look_at_block");
            }
            return valid ? Crosshair.blockId(s) : I18n.get("mineflayer_bridge.pick.status.not_accepted", Crosshair.blockId(s));
        }

        @Override
        public boolean valid() {
            return state == null || valid;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final BlockPos p = pos;
            if (p != null) {
                block(out, Crosshair.vec3i(p), valid ? OK : BAD, 0.15f);
            }
        }
    }

    /** {@code region}: two corners; scrolling pushes the second corner along the axis the player looks down. */
    static final class Region implements PickMode {
        private final double maxDistance;
        private final long maxVolume;
        private @Nullable Vec3i first;
        private Vec3i axis = new Vec3i(0, 1, 0);
        private int push;
        private Vec3i cursor = new Vec3i(0, 0, 0);

        Region(final double maxDistance, final @Nullable Integer maxVolume) {
            this.maxDistance = maxDistance;
            this.maxVolume = maxVolume == null ? Long.MAX_VALUE : Integer.toUnsignedLong(maxVolume);
        }

        @Override
        public void update() {
            final BlockHitResult hit = Crosshair.blockHit(maxDistance);
            final Vec3i base = hit != null ? Crosshair.vec3i(hit.getBlockPos())
                    : Crosshair.vec3i(BlockPos.containing(Crosshair.eye().add(Crosshair.look().scale(Crosshair.AIR_DISTANCE))));
            cursor = PickGeometry.offset(base, axis, push);
        }

        @Override
        public boolean click() {
            if (first == null) {
                first = cursor;
                push = 0;
                return false;
            }
            return true;
        }

        @Override
        public boolean undo() {
            if (push != 0) {
                push = 0;
                return true;
            }
            if (first != null) {
                first = null;
                return true;
            }
            return false;
        }

        @Override
        public void scroll(final int steps) {
            final Vec3 look = Crosshair.look();
            final Vec3i next = PickGeometry.dominantAxis(look.x, look.y, look.z);
            if (!next.equals(axis)) {
                axis = next;
                push = 0;
            }
            // Scrolling up pushes the corner away from the player, along the look direction.
            push += steps;
        }

        private long volume() {
            final Vec3i a = first;
            return a == null ? 1L : PickGeometry.volume(a, cursor);
        }

        @Override
        public @Nullable PickResult result() {
            final Vec3i a = first;
            if (a == null || !valid()) {
                return null;
            }
            return new PickResult.Region(new PickRegion(PickGeometry.min(a, cursor), PickGeometry.max(a, cursor)));
        }

        @Override
        public String status() {
            final Vec3i a = first;
            if (a == null) {
                return I18n.get("mineflayer_bridge.pick.status.first_corner");
            }
            final Vec3i min = PickGeometry.min(a, cursor);
            final Vec3i max = PickGeometry.max(a, cursor);
            final int sx = max.x() - min.x() + 1;
            final int sy = max.y() - min.y() + 1;
            final int sz = max.z() - min.z() + 1;
            return maxVolume == Long.MAX_VALUE ? I18n.get("mineflayer_bridge.pick.status.region", sx, sy, sz, volume())
                    : I18n.get("mineflayer_bridge.pick.status.region.max", sx, sy, sz, volume(), maxVolume);
        }

        @Override
        public boolean valid() {
            return volume() <= maxVolume;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final Vec3i a = first;
            if (a == null) {
                block(out, cursor, CURSOR, 0.1f);
                return;
            }
            final Vec3i min = PickGeometry.min(a, cursor);
            final Vec3i max = PickGeometry.max(a, cursor);
            box(out, min.x(), min.y(), min.z(), max.x() + 1d, max.y() + 1d, max.z() + 1d, valid() ? OK : BAD, 0.08f);
            block(out, a, CURSOR, 0f);
        }
    }

    /** {@code polygon}: clicked vertices plus a Y range; scrolling raises the top. */
    static final class Polygon implements PickMode {
        private final double maxDistance;
        private final List<Vec3i> vertices = new ArrayList<>();
        private int extraHeight;
        private @Nullable Vec3i cursor;

        Polygon(final double maxDistance) {
            this.maxDistance = maxDistance;
        }

        @Override
        public void update() {
            final BlockHitResult hit = Crosshair.blockHit(maxDistance);
            cursor = hit == null ? null : Crosshair.vec3i(hit.getBlockPos());
        }

        @Override
        public boolean click() {
            final Vec3i c = cursor;
            if (c != null) {
                vertices.add(c);
            }
            return false;
        }

        @Override
        public boolean undo() {
            if (vertices.isEmpty()) {
                return false;
            }
            vertices.removeLast();
            return true;
        }

        @Override
        public void scroll(final int steps) {
            extraHeight = Math.max(0, extraHeight + steps);
        }

        @Override
        public @Nullable PickResult result() {
            if (vertices.size() < 3) {
                return null;
            }
            return new PickResult.Polygon(new PickPolygon(List.copyOf(vertices), PickGeometry.minY(vertices),
                    PickGeometry.maxY(vertices) + extraHeight));
        }

        @Override
        public String status() {
            if (vertices.isEmpty()) {
                return I18n.get("mineflayer_bridge.pick.status.add_vertices");
            }
            return I18n.get(vertices.size() < 3 ? "mineflayer_bridge.pick.status.polygon.short" : "mineflayer_bridge.pick.status.polygon",
                    vertices.size(), PickGeometry.minY(vertices), PickGeometry.maxY(vertices) + extraHeight);
        }

        @Override
        public boolean valid() {
            return vertices.size() >= 3 || vertices.isEmpty();
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final Vec3i c = cursor;
            if (c != null) {
                block(out, c, CURSOR, 0.1f);
            }
            if (vertices.isEmpty()) {
                return;
            }
            final double bottom = PickGeometry.minY(vertices);
            final double top = PickGeometry.maxY(vertices) + extraHeight + 1d;
            final int n = vertices.size();
            for (int i = 0; i < n; i++) {
                final Vec3i a = vertices.get(i);
                final double ax = a.x() + 0.5d;
                final double az = a.z() + 0.5d;
                line(out, ax, bottom, az, ax, top, az, LINE);
                if (i + 1 < n || n >= 3) {
                    final Vec3i b = vertices.get((i + 1) % n);
                    final double bx = b.x() + 0.5d;
                    final double bz = b.z() + 0.5d;
                    final int colour = i + 1 < n ? LINE : CURSOR;
                    line(out, ax, bottom, az, bx, bottom, bz, colour);
                    line(out, ax, top, az, bx, top, bz, colour);
                }
            }
            if (c != null) {
                final Vec3i last = vertices.getLast();
                line(out, last.x() + 0.5d, bottom, last.z() + 0.5d, c.x() + 0.5d, bottom, c.z() + 0.5d, CURSOR);
            }
        }
    }

    /** {@code entity}: the entity under the crosshair; matching entities in range are highlighted. */
    static final class EntityPick implements PickMode {
        private static final int MAX_CANDIDATES = 64;
        private final double maxDistance;
        private final @Nullable TargetSpec filter;
        private final List<Entity> candidates = new ArrayList<>();
        private @Nullable Entity target;
        private boolean valid;

        EntityPick(final double maxDistance, final @Nullable TargetSpec filter) {
            this.maxDistance = maxDistance;
            this.filter = filter;
        }

        private boolean accepts(final Entity e) {
            return filter == null || TargetMatch.matchesFilter(filter.ids(), filter.tags(), Crosshair.entityTypeId(e),
                    tag -> Crosshair.entityHasTag(e, tag));
        }

        @Override
        public void tick() {
            candidates.clear();
            final Minecraft minecraft = Minecraft.getInstance();
            final LocalPlayer player = minecraft.player;
            if (player == null || minecraft.level == null) {
                return;
            }
            final AABB area = player.getBoundingBox().inflate(maxDistance);
            final double maxSq = maxDistance * maxDistance;
            for (final Entity e : minecraft.level.getEntities(player, area, e -> !e.isSpectator() && e.isPickable())) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                if (e.distanceToSqr(player) <= maxSq && accepts(e)) {
                    candidates.add(e);
                }
            }
        }

        @Override
        public void update() {
            final Crosshair.Hit hit = Crosshair.hit(maxDistance, e -> true);
            final Entity e = hit.entity();
            target = e;
            valid = e != null && accepts(e);
        }

        @Override
        public boolean click() {
            return target != null;
        }

        @Override
        public boolean undo() {
            return false;
        }

        @Override
        public void scroll(final int steps) {
        }

        @Override
        public @Nullable PickResult result() {
            final Entity e = target;
            if (e == null || !valid) {
                return null;
            }
            return new PickResult.Entity(new PickEntity(e.getId(), e.getUUID().toString(), Crosshair.entityTypeId(e),
                    Crosshair.vec(e.position())));
        }

        @Override
        public String status() {
            final Entity e = target;
            if (e == null) {
                return I18n.get("mineflayer_bridge.pick.status.candidates", candidates.size());
            }
            final String name = e.getName().getString() + " (" + Crosshair.entityTypeId(e) + ")";
            return valid ? name : I18n.get("mineflayer_bridge.pick.status.not_accepted", name);
        }

        @Override
        public boolean valid() {
            return target == null || valid;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            for (int i = 0; i < candidates.size(); i++) {
                final Entity e = candidates.get(i);
                if (e != target && !e.isRemoved()) {
                    final AABB b = e.getBoundingBox();
                    box(out, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, CANDIDATE, 0f);
                }
            }
            final Entity e = target;
            if (e != null) {
                final AABB b = e.getBoundingBox();
                box(out, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, valid ? OK : BAD, 0.12f);
            }
        }
    }

    /** {@code path}: clicked nodes on the faces the player clicks. */
    static final class Path implements PickMode {
        private final double maxDistance;
        private final List<Vec3> points = new ArrayList<>();
        private @Nullable Vec3 cursor;

        Path(final double maxDistance) {
            this.maxDistance = maxDistance;
        }

        @Override
        public void update() {
            final BlockHitResult hit = Crosshair.blockHit(maxDistance);
            if (hit == null) {
                cursor = null;
                return;
            }
            // A node stands on the clicked face: the bottom centre of the block in front of it.
            final BlockPos node = hit.getBlockPos().relative(hit.getDirection());
            cursor = new Vec3(node.getX() + 0.5d, node.getY(), node.getZ() + 0.5d);
        }

        @Override
        public boolean click() {
            final Vec3 c = cursor;
            if (c != null) {
                points.add(c);
            }
            return false;
        }

        @Override
        public boolean undo() {
            if (points.isEmpty()) {
                return false;
            }
            points.removeLast();
            return true;
        }

        @Override
        public void scroll(final int steps) {
        }

        @Override
        public @Nullable PickResult result() {
            if (points.isEmpty()) {
                return null;
            }
            final List<io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d> out = new ArrayList<>(points.size());
            for (final Vec3 p : points) {
                out.add(Crosshair.vec(p));
            }
            return new PickResult.Path(new PickPath(out));
        }

        @Override
        public String status() {
            return points.isEmpty() ? I18n.get("mineflayer_bridge.pick.status.add_nodes") : I18n.get("mineflayer_bridge.pick.status.nodes", points.size());
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final double r = 0.15d;
            Vec3 previous = null;
            for (final Vec3 p : points) {
                box(out, p.x - r, p.y, p.z - r, p.x + r, p.y + 2 * r, p.z + r, LINE, 0.4f);
                if (previous != null) {
                    line(out, previous.x, previous.y + 0.1d, previous.z, p.x, p.y + 0.1d, p.z, LINE);
                }
                previous = p;
            }
            final Vec3 c = cursor;
            if (c != null) {
                box(out, c.x - r, c.y, c.z - r, c.x + r, c.y + 2 * r, c.z + r, CURSOR, 0.2f);
                if (previous != null) {
                    line(out, previous.x, previous.y + 0.1d, previous.z, c.x, c.y + 0.1d, c.z, CURSOR);
                }
            }
        }
    }

    /** {@code direction}: the look direction, snapped to 15 degrees while Ctrl is held. */
    static final class DirectionPick implements PickMode {
        private float yaw;
        private float pitch;
        private Vec3 feet = Vec3.ZERO;

        @Override
        public void update() {
            final Minecraft minecraft = Minecraft.getInstance();
            final LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            float y = PickGeometry.wrapDegrees(player.getYRot());
            float p = player.getXRot();
            if (minecraft.hasControlDown()) {
                y = PickGeometry.wrapDegrees(PickGeometry.snap(y, PickGeometry.SNAP_DEGREES));
                p = PickGeometry.snap(p, PickGeometry.SNAP_DEGREES);
            }
            yaw = y;
            pitch = p;
            feet = player.position();
        }

        @Override
        public boolean click() {
            return true;
        }

        @Override
        public boolean undo() {
            return false;
        }

        @Override
        public void scroll(final int steps) {
        }

        @Override
        public @Nullable PickResult result() {
            return new PickResult.Direction(new PickDirection(yaw, pitch, PickGeometry.cardinal(yaw)));
        }

        @Override
        public String status() {
            return I18n.get("mineflayer_bridge.pick.status.direction", String.format(Locale.ROOT, "%.0f", yaw), String.format(Locale.ROOT, "%.0f", pitch),
                    PickGeometry.cardinal(yaw));
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
            final double yr = Math.toRadians(yaw);
            final double pr = Math.toRadians(pitch);
            final double dx = -Math.sin(yr) * Math.cos(pr);
            final double dy = -Math.sin(pr);
            final double dz = Math.cos(yr) * Math.cos(pr);
            final double sx = feet.x;
            final double sy = feet.y + 0.1d;
            final double sz = feet.z;
            line(out, sx, sy, sz, sx + dx * 4, sy + dy * 4, sz + dz * 4, OK);
        }
    }

    /** {@code item}: answered from the inventory screen or the held item; nothing to draw in the world. */
    static final class Item implements PickMode {

        @Override
        public void update() {
        }

        @Override
        public boolean click() {
            return false;
        }

        @Override
        public boolean undo() {
            return false;
        }

        @Override
        public void scroll(final int steps) {
        }

        @Override
        public @Nullable PickResult result() {
            return null;
        }

        @Override
        public String status() {
            return I18n.get("mineflayer_bridge.pick.status.item");
        }

        @Override
        public boolean valid() {
            return true;
        }

        @Override
        public void draw(final List<DrawCommand> out) {
        }
    }
}
