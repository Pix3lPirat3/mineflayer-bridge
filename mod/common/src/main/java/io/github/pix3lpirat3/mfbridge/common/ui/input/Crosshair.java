package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.ui.command.ClientEntities;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.command.ArgContext;
import io.github.pix3lpirat3.mfbridge.core.command.EntityQuery;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Resolves what is under the crosshair into bridge targets.
 * Uses the camera ray, so it works in third person too. Client thread only.
 */
public final class Crosshair {

    /** Default reach for context actions and tools, in blocks. */
    public static final double ACTION_RANGE = 64d;

    /** How far in front of the player an {@code air} target sits when the ray hits nothing, in blocks. */
    public static final double AIR_DISTANCE = 4d;

    private static BotLookup bots = entity -> null;

    private Crosshair() {
    }

    /** Answers which attached bot an entity is, so a bot's own player entity resolves as a {@code bot} target. */
    @FunctionalInterface
    public interface BotLookup {
        /**
         * Returns the owner key of the bot this entity is.
         *
         * @param entity an entity under the crosshair
         * @return {@code bot:<identity>}, or null when the entity is no connected bot
         */
        @Nullable String owner(Entity entity);
    }

    /**
     * Installs the bot lookup; the UI layer does this once, since only it knows the running core.
     *
     * @param lookup the lookup
     */
    public static void bots(final BotLookup lookup) {
        bots = lookup;
    }

    /** Returns the owner of the attached bot a player entity is, matched by UUID or by name. */
    public static @Nullable String botOwner(final BridgeCore core, final Player player) {
        final String uuid = player.getUUID().toString();
        final String byName = player.getGameProfile().name().toLowerCase(Locale.ROOT) + "@";
        for (final Presence p : core.presence().all()) {
            if (p.connected() && (p.identity().equalsIgnoreCase(uuid) || p.identity().startsWith(byName))) {
                return DisplayText.botOwner(p.identity());
            }
        }
        return null;
    }

    /**
     * What the crosshair is on.
     *
     * @param type block, entity or miss
     * @param block the block position for a block hit, else null
     * @param face the hit face for a block hit, else null
     * @param state the block state for a block hit, else null
     * @param entity the entity for an entity hit, else null
     * @param location the exact hit position, or the ray end for a miss
     */
    public record Hit(
            HitResult.Type type,
            @Nullable BlockPos block,
            @Nullable Direction face,
            @Nullable BlockState state,
            @Nullable Entity entity,
            Vec3 location) {
    }

    /**
     * Returns the camera position.
     *
     * @return the eye position used for rays
     */
    public static Vec3 eye() {
        return Game.mainCamera().position();
    }

    /**
     * Returns the camera look direction as a unit vector.
     *
     * @return the forward vector
     */
    public static Vec3 look() {
        final Camera camera = Game.mainCamera();
        final Vector3fc f = camera.forwardVector();
        return new Vec3(f.x(), f.y(), f.z());
    }

    /**
     * Ray casts against blocks.
     *
     * @param maxDistance ray length, in blocks
     * @return the hit, or a miss result
     */
    public static BlockHitResult block(final double maxDistance) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Vec3 from = eye();
        final Vec3 to = from.add(look().scale(maxDistance));
        final ClientLevel level = minecraft.level;
        final Entity viewer = minecraft.getCameraEntity();
        if (level == null || viewer == null) {
            return BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to));
        }
        return level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
    }

    /**
     * Ray casts against blocks and keeps only a block hit.
     *
     * @param maxDistance ray length, in blocks
     * @return the hit, or null when the ray hits no block
     */
    public static @Nullable BlockHitResult blockHit(final double maxDistance) {
        final BlockHitResult hit = block(maxDistance);
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * Ray casts against blocks and entities; the nearer hit wins.
     *
     * @param maxDistance ray length, in blocks
     * @param entities which entities the ray may hit
     * @return the hit
     */
    public static Hit hit(final double maxDistance, final Predicate<Entity> entities) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Vec3 from = eye();
        final Vec3 dir = look();
        final Vec3 to = from.add(dir.scale(maxDistance));
        final BlockHitResult blockHit = block(maxDistance);
        final Entity viewer = minecraft.getCameraEntity();
        final double blockDistSq = blockHit.getType() == HitResult.Type.MISS ? maxDistance * maxDistance
                : blockHit.getLocation().distanceToSqr(from);
        if (viewer != null && minecraft.level != null) {
            final AABB area = viewer.getBoundingBox().expandTowards(to.subtract(from)).inflate(1d);
            final EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(viewer, from, to, area,
                    e -> e != viewer && !e.isSpectator() && e.isPickable() && entities.test(e), blockDistSq);
            if (entityHit != null) {
                return new Hit(HitResult.Type.ENTITY, null, null, null, entityHit.getEntity(), entityHit.getLocation());
            }
        }
        if (blockHit.getType() == HitResult.Type.MISS || minecraft.level == null) {
            return new Hit(HitResult.Type.MISS, null, null, null, null, from.add(dir.scale(Math.min(AIR_DISTANCE, maxDistance))));
        }
        final BlockPos pos = blockHit.getBlockPos();
        return new Hit(HitResult.Type.BLOCK, pos, blockHit.getDirection(), minecraft.level.getBlockState(pos), null,
                blockHit.getLocation());
    }

    /**
     * Converts a hit into a target of a given type, when the hit can be one.
     *
     * @param hit the crosshair hit
     * @param type a target type: {@code block}, {@code entity}, {@code air} or {@code item}
     * @return the target, or null when the hit is not of that type
     */
    public static @Nullable Target target(final Hit hit, final String type) {
        return switch (type) {
            case "block" -> hit.block() != null && hit.face() != null
                    ? new Target("block", vec3i(hit.block()), hit.face().getSerializedName().toLowerCase(Locale.ROOT), null,
                            vec(hit.location()), null, null)
                    : null;
            case "entity" -> hit.entity() != null
                    ? new Target("entity", null, null, hit.entity().getId(), vec(hit.entity().position()), null, null)
                    : null;
            case "bot" -> {
                final Entity entity = hit.entity();
                final String owner = entity == null ? null : bots.owner(entity);
                yield owner == null ? null : new Target("bot", null, null, entity.getId(), vec(entity.position()), null, owner);
            }
            case "air" -> new Target("air", null, null, null, vec(hit.location()), null, null);
            case "item" -> {
                final String held = heldItemId();
                yield held == null ? null : new Target("item", null, null, null, null, held, null);
            }
            default -> null;
        };
    }

    /**
     * Resolves the first target type in {@code types} that the hit can be, for a tool.
     *
     * @param hit the crosshair hit
     * @param types the tool's declared target types
     * @return a target, or null when none applies
     */
    public static @Nullable Target firstTarget(final Hit hit, final Collection<String> types) {
        final String natural = switch (hit.type()) {
            case BLOCK -> "block";
            case ENTITY -> types.contains("bot") && hit.entity() != null && bots.owner(hit.entity()) != null ? "bot" : "entity";
            case MISS -> "air";
        };
        if (types.contains(natural)) {
            return target(hit, natural);
        }
        for (final String type : types) {
            final Target t = target(hit, type);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * Returns whether a hit matches a target spec, checking ids and tags of blocks, entities and items.
     *
     * @param spec the declaration
     * @param hit the crosshair hit
     * @return whether an action with this spec applies
     */
    public static boolean matches(final TargetSpec spec, final Hit hit) {
        return switch (spec.type()) {
            case "block" -> {
                final BlockState state = hit.state();
                yield state != null && TargetMatch.matches(spec, "block", blockId(state), tag -> blockHasTag(state, tag));
            }
            case "entity" -> {
                final Entity entity = hit.entity();
                yield entity != null && TargetMatch.matches(spec, "entity", entityTypeId(entity), tag -> entityHasTag(entity, tag));
            }
            case "bot" -> hit.entity() instanceof Player player && bots.owner(player) != null
                    && TargetMatch.matches(spec, "bot", player.getGameProfile().name(), tag -> false);
            case "air" -> hit.type() == HitResult.Type.MISS && TargetMatch.matches(spec, "air", null, tag -> false);
            case "item" -> {
                final ItemStack held = heldItem();
                yield held != null && TargetMatch.matches(spec, "item", SlotItems.id(held),
                        tag -> itemHasTag(held, tag));
            }
            default -> false;
        };
    }

    /**
     * Returns the registry id of a block state's block.
     *
     * @param state the state
     * @return the id, for example {@code minecraft:chest}
     */
    public static String blockId(final BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Returns whether a block state's block is in a tag.
     *
     * @param state the state
     * @param tag a namespaced tag id without {@code #}
     * @return whether the block carries the tag; false for malformed ids
     */
    public static boolean blockHasTag(final BlockState state, final String tag) {
        final Identifier id = Identifier.tryParse(tag);
        return id != null && state.is(TagKey.create(Registries.BLOCK, id));
    }

    /**
     * Returns the registry id of an entity's type.
     *
     * @param entity the entity
     * @return the id, for example {@code minecraft:cow}
     */
    public static String entityTypeId(final Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static final String KIND_PREFIX = "mineflayer_bridge:";

    /**
     * What kind of entity this is, as the map and the kind tags see it.
     *
     * @param entity the entity
     * @return {@code player}, {@code bot}, {@code hostile}, {@code animal}, {@code item}, or null
     *     for anything else (armour stands, boats, projectiles)
     */
    public static @Nullable String kind(final Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity) {
            return "item";
        }
        if (entity instanceof Player p) {
            return bots.owner(p) != null ? "bot" : "player";
        }
        if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
            return "hostile";
        }
        if (entity instanceof net.minecraft.world.entity.LivingEntity && !(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            return "animal";
        }
        return null;
    }


    /**
     * Returns whether an entity's type is in a tag. Besides the game's entity type tags there are
     * kinds the game has no tag for: {@code mineflayer_bridge:player} (a player who is not a bot),
     * {@code mineflayer_bridge:bot}, {@code mineflayer_bridge:hostile} (monsters),
     * {@code mineflayer_bridge:animal} (other living mobs) and {@code mineflayer_bridge:item} (items
     * on the ground).
     *
     * @param entity the entity
     * @param tag a namespaced tag id without {@code #}
     * @return whether the type carries the tag; false for malformed ids
     */
    public static boolean entityHasTag(final Entity entity, final String tag) {
        if (tag.startsWith(KIND_PREFIX)) {
            return tag.substring(KIND_PREFIX.length()).equals(kind(entity));
        }
        final Identifier id = Identifier.tryParse(tag);
        return id != null && BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).is(TagKey.create(Registries.ENTITY_TYPE, id));
    }

    private static boolean itemHasTag(final ItemStack stack, final String tag) {
        final Identifier id = Identifier.tryParse(tag);
        return id != null && stack.is(TagKey.create(Registries.ITEM, id));
    }

    /**
     * Returns the item in the player's main hand.
     *
     * @return the stack, or null when the hand is empty or there is no player
     */
    public static @Nullable ItemStack heldItem() {
        final var player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        final ItemStack stack = player.getMainHandItem();
        return stack.isEmpty() ? null : stack;
    }

    private static @Nullable String heldItemId() {
        final ItemStack stack = heldItem();
        return stack == null ? null : SlotItems.id(stack);
    }

    /**
     * Converts a block position.
     *
     * @param pos a Minecraft block position
     * @return the protocol position
     */
    public static Vec3i vec3i(final BlockPos pos) {
        return new Vec3i(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Converts a position.
     *
     * @param v a Minecraft vector
     * @return the protocol vector
     */
    public static Vec3d vec(final Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    /**
     * Returns an argument context over the current player and crosshair.
     *
     * @return the context; reads game state on each call, so use it on the client thread right away
     */
    public static ArgContext argContext() {
        return new ArgContext() {
            @Override
            public Vec3i originBlock() {
                final var player = Minecraft.getInstance().player;
                return player == null ? new Vec3i(0, 0, 0) : vec3i(player.blockPosition());
            }

            @Override
            public Vec3d originExact() {
                final var player = Minecraft.getInstance().player;
                return player == null ? new Vec3d(0, 0, 0) : vec(player.position());
            }

            @Override
            public @Nullable Vec3i crosshairBlock() {
                final BlockHitResult hit = blockHit(ACTION_RANGE);
                return hit == null ? null : vec3i(hit.getBlockPos());
            }

            @Override
            public @Nullable Vec3d crosshairPos() {
                final BlockHitResult hit = blockHit(ACTION_RANGE);
                return hit == null ? null : vec(hit.getLocation());
            }

            @Override
            public boolean blockExists(final String id) {
                final Identifier parsed = Identifier.tryParse(id);
                return parsed != null && BuiltInRegistries.BLOCK.containsKey(parsed);
            }

            @Override
            public boolean itemExists(final String id) {
                final Identifier parsed = Identifier.tryParse(id);
                return parsed != null && BuiltInRegistries.ITEM.containsKey(parsed);
            }

            @Override
            public EntityQuery entities() {
                return ClientEntities.INSTANCE;
            }
        };
    }
}
