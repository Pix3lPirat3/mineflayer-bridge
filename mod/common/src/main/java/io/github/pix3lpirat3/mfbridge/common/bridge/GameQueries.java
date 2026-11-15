package io.github.pix3lpirat3.mfbridge.common.bridge;

import io.github.pix3lpirat3.mfbridge.core.query.ClientQueries;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueriedEntity;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocks;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocksResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntities;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntitiesResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Answers client queries from the player's loaded level. Reads only what the
 * client already has; nothing is sent to the server. Client thread.
 */
final class GameQueries implements ClientQueries {

    @Override
    public QueryEntitiesResult entities(final QueryEntities q) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        final Vec3d c = q.center();
        if (level == null || c == null) {
            return new QueryEntitiesResult(List.of());
        }
        final double r = q.radius();
        final double r2 = r * r;
        final Set<String> types = q.types() == null ? null : new HashSet<>(q.types());
        final List<Entity> found = level.getEntities((Entity) mc.player, new AABB(c.x() - r, c.y() - r, c.z() - r, c.x() + r, c.y() + r, c.z() + r),
                e -> e.distanceToSqr(c.x(), c.y(), c.z()) <= r2 && (types == null || types.contains(typeId(e))));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(c.x(), c.y(), c.z())));
        final List<QueriedEntity> out = new ArrayList<>(Math.min(found.size(), q.limit()));
        for (final Entity e : found) {
            if (out.size() >= q.limit()) {
                break;
            }
            final boolean player = e instanceof Player;
            out.add(new QueriedEntity(e.getId(), typeId(e), new Vec3d(e.getX(), e.getY(), e.getZ()), name(e), player));
        }
        return new QueryEntitiesResult(out);
    }

    @Override
    public QueryBlocksResult blocks(final QueryBlocks q) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return new QueryBlocksResult(new int[0], List.of(), new short[0], false);
        }
        final Set<String> wanted = q.blocks() == null ? null : new HashSet<>(q.blocks());
        final Object2IntOpenHashMap<String> palette = new Object2IntOpenHashMap<>();
        palette.defaultReturnValue(-1);
        final List<String> names = new ArrayList<>();
        final IntArrayList positions = new IntArrayList();
        final ShortArrayList ids = new ShortArrayList();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean complete = true;
        scan:
        for (int x = q.min().x(); x <= q.max().x(); x++) {
            for (int z = q.min().z(); z <= q.max().z(); z++) {
                for (int y = q.min().y(); y <= q.max().y(); y++) {
                    pos.set(x, y, z);
                    if (!level.isLoaded(pos)) {
                        complete = false;
                        continue;
                    }
                    final BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    final String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (wanted != null && !wanted.contains(id)) {
                        continue;
                    }
                    if (ids.size() >= q.limit()) {
                        complete = false;
                        break scan;
                    }
                    int index = palette.getInt(id);
                    if (index < 0) {
                        index = names.size();
                        names.add(id);
                        palette.put(id, index);
                    }
                    positions.add(x);
                    positions.add(y);
                    positions.add(z);
                    ids.add((short) index);
                }
            }
        }
        return new QueryBlocksResult(positions.toIntArray(), List.copyOf(names), ids.toShortArray(), complete);
    }

    private static String typeId(final Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static @Nullable String name(final Entity e) {
        if (e instanceof Player p) {
            return p.getGameProfile().name();
        }
        final var custom = e.hasCustomName() ? e.getCustomName() : null;
        return custom == null ? null : custom.getString();
    }
}
