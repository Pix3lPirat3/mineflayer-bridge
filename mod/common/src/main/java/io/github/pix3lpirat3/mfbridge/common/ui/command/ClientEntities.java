package io.github.pix3lpirat3.mfbridge.common.ui.command;

import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.core.command.EntityQuery;
import io.github.pix3lpirat3.mfbridge.core.command.Selector;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Resolves selectors and entity names against the client level. Only
 * loaded entities can be found. Client thread only.
 */
public final class ClientEntities implements EntityQuery {
    /** The shared instance; it holds no state. */
    public static final ClientEntities INSTANCE = new ClientEntities();

    private ClientEntities() {
    }

    /**
     * An entity with its distance from a point.
     *
     * @param entity the entity
     * @param distance in blocks
     */
    public record Near(Entity entity, double distance) {
    }

    @Override
    public @Nullable Integer player() {
        final var player = Minecraft.getInstance().player;
        return player == null ? null : player.getId();
    }

    @Override
    public @Nullable Integer bot(final String username) {
        final Player p = playerNamed(username);
        return p == null ? null : p.getId();
    }

    @Override
    public @Nullable Integer looked() {
        final HitResult hit = Minecraft.getInstance().hitResult;
        if (hit instanceof EntityHitResult e) {
            return e.getEntity().getId();
        }
        final Crosshair.Hit h = Crosshair.hit(Crosshair.ACTION_RANGE, e -> true);
        final Entity entity = h.entity();
        return entity == null ? null : entity.getId();
    }

    @Override
    public @Nullable Integer named(final String name) {
        final Player p = playerNamed(name);
        return p == null ? null : p.getId();
    }

    /**
     * Finds a loaded player by profile name, ignoring case.
     *
     * @param name the name
     * @return the player, or null
     */
    public static @Nullable Player playerNamed(final String name) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (final Player p : level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public List<Integer> find(final Selector.Filter filter, final Vec3d from, final int limit) {
        final List<Integer> out = new ArrayList<>();
        for (final Near n : near(from, filter, limit)) {
            out.add(n.entity().getId());
        }
        return List.copyOf(out);
    }

    /**
     * Returns loaded entities matching a selector filter, nearest first.
     *
     * @param from the point distances are measured from
     * @param filter type, tag and distance constraints
     * @param limit the most entities to return
     * @return the entities with their distances
     */
    public static List<Near> near(final Vec3d from, final Selector.Filter filter, final int limit) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || limit <= 0) {
            return List.of();
        }
        final List<Near> found = new ArrayList<>();
        for (final Entity e : level.entitiesForRendering()) {
            if (e.isRemoved()) {
                continue;
            }
            if (!filter.types().isEmpty() && !filter.types().contains(Crosshair.entityTypeId(e))) {
                continue;
            }
            if (!e.entityTags().containsAll(filter.tags())) {
                continue;
            }
            final double dx = e.getX() - from.x();
            final double dy = e.getY() - from.y();
            final double dz = e.getZ() - from.z();
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (filter.inRange(distance)) {
                found.add(new Near(e, distance));
            }
        }
        found.sort(Comparator.comparingDouble(Near::distance));
        return found.size() > limit ? List.copyOf(found.subList(0, limit)) : List.copyOf(found);
    }

    @Override
    public @Nullable Vec3d position(final int entityId) {
        final ClientLevel level = Minecraft.getInstance().level;
        final Entity e = level == null ? null : level.getEntity(entityId);
        return e == null ? null : Crosshair.vec(e.position());
    }
}
