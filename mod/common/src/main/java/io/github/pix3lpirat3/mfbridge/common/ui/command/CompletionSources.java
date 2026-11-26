package io.github.pix3lpirat3.mfbridge.common.ui.command;

import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.command.ArgValues;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandControls;
import io.github.pix3lpirat3.mfbridge.core.command.CompletionQuery;
import io.github.pix3lpirat3.mfbridge.core.command.Selector;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The client-resolved completion sources, registered with the core's
 * {@link CommandControls}. Every source filters by what was typed, honours {@code limit}
 * (at most 50) and returns quickly; the block scan is bounded. Client thread only.
 */
public final class CompletionSources {

    /** Default search radius for entities, in blocks. */
    private static final double ENTITY_RADIUS = 32d;

    /** Default and largest search radius of the block scan, in blocks; the scan is a cube. */
    private static final int BLOCK_RADIUS = 8;
    private static final int MAX_BLOCK_RADIUS = 16;

    private static final int MAX_LIMIT = 50;

    private CompletionSources() {
    }

    /**
     * Registers every client source with a core.
     *
     * @param core the core
     * @param snapshot the latest render snapshot entries, for zones, points and waypoints
     */
    public static void register(final BridgeCore core, final Supplier<List<RenderEntry>> snapshot) {
        final CommandControls c = core.commands();
        c.registerSource("entities", CompletionSources::entities);
        c.registerSource("blocks", CompletionSources::blocks);
        c.registerSource("block_ids", q -> ids(q, BuiltInRegistries.BLOCK));
        c.registerSource("item_ids", q -> ids(q, BuiltInRegistries.ITEM));
        c.registerSource("sounds", q -> ids(q, BuiltInRegistries.SOUND_EVENT));
        c.registerSource("particles", q -> ids(q, BuiltInRegistries.PARTICLE_TYPE));
        c.registerSource("enchantments", CompletionSources::enchantments);
        c.registerSource("players", CompletionSources::players);
        c.registerSource("bots", q -> bots(q, core));
        c.registerSource("zones", q -> objects(q, snapshot.get(), "core:zone"));
        c.registerSource("points", q -> objects(q, snapshot.get(), "core:point"));
        c.registerSource("waypoints", q -> objects(q, snapshot.get(), "core:waypoint"));
    }

    private static int limit(final CompletionQuery q) {
        return Math.clamp(q.spec().limit(), 1, MAX_LIMIT);
    }

    private static boolean starts(final String candidate, final String typed) {
        final String c = candidate.toLowerCase(Locale.ROOT);
        final String t = typed.toLowerCase(Locale.ROOT);
        if (c.startsWith(t)) {
            return true;
        }
        final int colon = c.indexOf(':');
        return colon >= 0 && c.startsWith(t, colon + 1);
    }

    /** Where {@code near} measures from: the player, or the bot's entity or reported position. */
    private static @Nullable Vec3d origin(final CompletionQuery q) {
        if ("bot".equals(q.spec().near())) {
            return ArgValues.botPosition(q.context(), q.bot());
        }
        final var player = Minecraft.getInstance().player;
        return player == null ? null : Crosshair.vec(player.position());
    }

    /** Entities near the player or the bot, shown as "Zombie (12 m)", inserting the entity id. */
    private static List<Suggestion> entities(final CompletionQuery q) {
        final Vec3d from = origin(q);
        if (from == null) {
            return List.of();
        }
        final Float r = q.spec().radius();
        final double radius = r == null ? ENTITY_RADIUS : r;
        final TargetSpec filter = q.spec().filter();
        final List<Suggestion> out = new ArrayList<>();
        final int limit = limit(q);
        final String typed = q.input();
        for (final ClientEntities.Near n : ClientEntities.near(from, new Selector.Filter(List.of(), List.of(), null, radius, false, null),
                Integer.MAX_VALUE)) {
            if (out.size() >= limit) {
                break;
            }
            final Entity e = n.entity();
            final String type = Crosshair.entityTypeId(e);
            if (filter != null && !TargetMatch.matchesFilter(filter.ids(), filter.tags(), type, t -> Crosshair.entityHasTag(e, t))) {
                continue;
            }
            final String id = Integer.toString(e.getId());
            final String name = e.getName().getString();
            final String typeName = e.getType().getDescription().getString();
            if (!typed.isEmpty() && !id.startsWith(typed) && !starts(name, typed) && !starts(typeName, typed) && !starts(type, typed)) {
                continue;
            }
            final long metres = Math.round(n.distance());
            final String tooltip = name.equals(typeName) ? type : type + "\n" + name;
            out.add(new Suggestion(id, I18n.get("mineflayer_bridge.suggest.distance", name, metres), tooltip, null));
        }
        return out;
    }

    /**
     * The crosshair block, {@code ~} relative positions, and blocks of the filter's type
     * near the player or bot. Component 0 suggests whole positions ({@code x y z}); later components
     * suggest the crosshair block's coordinate.
     */
    private static List<Suggestion> blocks(final CompletionQuery q) {
        final List<Suggestion> out = new ArrayList<>();
        final Vec3i cross = q.context().crosshairBlock();
        final String typed = q.input();
        if (q.component() > 0) {
            if (cross != null) {
                final int v = q.component() == 1 ? cross.y() : cross.z();
                final String text = Integer.toString(v);
                if (text.startsWith(typed)) {
                    out.add(new Suggestion(text, null, I18n.get("mineflayer_bridge.suggest.crosshair"), null));
                }
            }
            return out;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (cross != null) {
            final String text = cross.x() + " " + cross.y() + " " + cross.z();
            if (text.startsWith(typed)) {
                final String what = level == null ? "block" : Crosshair.blockId(level.getBlockState(new BlockPos(cross.x(), cross.y(), cross.z())));
                out.add(new Suggestion(text, null, I18n.get("mineflayer_bridge.suggest.crosshair.block", what), null));
            }
        }
        final TargetSpec filter = q.spec().filter();
        final Vec3d from = origin(q);
        if (filter == null || level == null || from == null) {
            return out;
        }
        final Float r = q.spec().radius();
        final int radius = Math.clamp(r == null ? BLOCK_RADIUS : (int) Math.ceil(r), 1, MAX_BLOCK_RADIUS);
        final BlockPos center = BlockPos.containing(from.x(), from.y(), from.z());
        final List<BlockPos> found = new ArrayList<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    final BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    final String id = Crosshair.blockId(state);
                    if (TargetMatch.matchesFilter(filter.ids(), filter.tags(), id, t -> Crosshair.blockHasTag(state, t))) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(from.x(), from.y(), from.z())));
        final int limit = limit(q);
        for (final BlockPos p : found) {
            if (out.size() >= limit) {
                break;
            }
            final String text = p.getX() + " " + p.getY() + " " + p.getZ();
            if (text.startsWith(typed)) {
                final long metres = Math.round(Math.sqrt(p.distToCenterSqr(from.x(), from.y(), from.z())));
                out.add(new Suggestion(text, I18n.get("mineflayer_bridge.suggest.distance", Crosshair.blockId(level.getBlockState(p)), metres), null, null));
            }
        }
        return out;
    }

    /** Registry ids filtered by prefix and the optional {@code filter} ids and tags. */
    private static <T> List<Suggestion> ids(final CompletionQuery q, final Registry<T> registry) {
        final TargetSpec filter = q.spec().filter();
        final List<String> matches = new ArrayList<>();
        for (final Identifier id : registry.keySet()) {
            final String text = id.toString();
            if (!starts(text, q.input())) {
                continue;
            }
            if (filter != null) {
                final Holder<T> holder = registry.get(id).map(h -> (Holder<T>) h).orElse(null);
                if (holder == null || !TargetMatch.matchesFilter(filter.ids(), filter.tags(), text, t -> hasTag(registry, holder, t))) {
                    continue;
                }
            }
            matches.add(text);
        }
        matches.sort(null);
        final List<Suggestion> out = new ArrayList<>();
        for (final String m : matches.subList(0, Math.min(matches.size(), limit(q)))) {
            out.add(new Suggestion(m));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean hasTag(final Registry<T> registry, final Holder<T> holder, final String tag) {
        final Identifier id = Identifier.tryParse(tag);
        return id != null && holder.is(TagKey.create((ResourceKey<? extends Registry<T>>) registry.key(), id));
    }

    /** Enchantments are data-driven, so they come from the connection's registries. */
    private static List<Suggestion> enchantments(final CompletionQuery q) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return List.of();
        }
        return connection.registryAccess().lookup(Registries.ENCHANTMENT).map(r -> ids(q, r)).orElse(List.of());
    }

    /** Players in the tab list. */
    private static List<Suggestion> players(final CompletionQuery q) {
        final ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final PlayerInfo info : connection.getOnlinePlayers()) {
            final String name = info.getProfile().name();
            if (starts(name, q.input())) {
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        final List<Suggestion> out = new ArrayList<>();
        for (final String n : names.subList(0, Math.min(names.size(), limit(q)))) {
            out.add(new Suggestion(n));
        }
        return out;
    }

    /** Connected bot names. */
    private static List<Suggestion> bots(final CompletionQuery q, final BridgeCore core) {
        final List<Suggestion> out = new ArrayList<>();
        for (final Presence p : core.presence().all()) {
            if (p.connected() && starts(p.displayName(), q.input()) && out.size() < limit(q)) {
                out.add(new Suggestion(p.displayName(), null, I18n.get(core.attach().isAttached(p.identity()) ? "mineflayer_bridge.suggest.attached" : "mineflayer_bridge.suggest.detached"), null));
            }
        }
        return out;
    }

    /**
     * Shared definitions, the player's own and the bot's own, by kind. The suggestion
     * inserts the full object key, with the object's name as the display label; it matches the
     * typed text against the key, the name and the local id.
     */
    private static List<Suggestion> objects(final CompletionQuery q, final List<RenderEntry> entries, final String kind) {
        final BotCommands bot = q.bot();
        final List<Suggestion> out = new ArrayList<>();
        for (final RenderEntry e : entries) {
            if (out.size() >= limit(q)) {
                break;
            }
            final String owner = e.owner();
            final boolean mine = owner.equals(bot.owner());
            if (!e.kind().name().equals(kind) || !(owner.equals("shared") || owner.equals("player") || mine)) {
                continue;
            }
            final String name = switch (e.data()) {
                case WaypointData w -> w.name();
                case ZoneData z -> z.name();
                case PointData p -> p.name();
                case null, default -> e.localId();
            };
            if (!starts(e.key(), q.input()) && !starts(name, q.input()) && !starts(e.localId(), q.input())) {
                continue;
            }
            out.add(new Suggestion(e.key(), name, mine ? bot.name() : owner, null));
        }
        return out;
    }
}
