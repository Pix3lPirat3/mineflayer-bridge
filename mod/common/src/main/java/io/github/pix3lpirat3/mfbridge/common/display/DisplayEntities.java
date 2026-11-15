package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.mixin.EntityTypeAccessor;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.display.logic.OnceLog;
import io.github.pix3lpirat3.mfbridge.common.mixin.ArmorStandInvoker;
import io.github.pix3lpirat3.mfbridge.common.mixin.CreeperAccessor;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArmorStandProps;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ARGB;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

/**
 * Creates and poses the detached entities behind {@code core:display.entity}. Entities are created against the client level so they can read its light,
 * but are never added to it: they have no server id, never tick game logic and vanilla picking cannot
 * see them. Player models use a mannequin with the requested skin.
 *
 * <p>Appearance is set in three steps when an entity is created: the display's {@code data} SNBT is
 * loaded as {@code /summon} would, then the typed appearance fields (invisible, on fire, frozen,
 * variant, colour, profession, charged, saddled, armor stand) are applied through the entity's own
 * setters and data components, and {@link #configure} applies pose, name and equipment on every
 * revision. A change to any appearance field recreates the entity (see {@link Look}), so a field that
 * goes back to its default never leaves the old look behind. Client thread only.
 */
final class DisplayEntities {
    /** Variant data components, tried in order; an entity ignores components it does not use. */
    private static final List<DataComponentType<?>> VARIANTS = List.of(DataComponents.CAT_VARIANT, DataComponents.WOLF_VARIANT,
            DataComponents.FROG_VARIANT, DataComponents.PIG_VARIANT, DataComponents.COW_VARIANT, DataComponents.CHICKEN_VARIANT,
            DataComponents.ZOMBIE_NAUTILUS_VARIANT, DataComponents.HORSE_VARIANT, DataComponents.LLAMA_VARIANT,
            DataComponents.AXOLOTL_VARIANT, DataComponents.PARROT_VARIANT, DataComponents.RABBIT_VARIANT, DataComponents.FOX_VARIANT,
            DataComponents.MOOSHROOM_VARIANT, DataComponents.SALMON_SIZE, DataComponents.VILLAGER_VARIANT,
            DataComponents.PAINTING_VARIANT);
    /** Colour data components: wool, collars, shulker shell and tropical fish body. */
    private static final List<DataComponentType<?>> COLORS = List.of(DataComponents.SHEEP_COLOR, DataComponents.WOLF_COLLAR,
            DataComponents.CAT_COLLAR, DataComponents.SHULKER_COLOR, DataComponents.TROPICAL_FISH_BASE_COLOR);
    /** Keys of {@code data} that would reach outside the detached entity (scoreboard, leash) or move it. */
    private static final List<String> STRIPPED_KEYS = List.of("Team", "Pos", "UUID", "Passengers", "leash");
    private static final OnceLog PROBLEMS = new OnceLog(64);
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-1_000_000);

    private DisplayEntities() {
    }

    /**
     * Everything that decides how a created entity looks before {@link #configure}; when it changes
     * between revisions the entity is recreated.
     *
     * @param type entity type id
     * @param skin player skin, for player models
     * @param data SNBT entity data
     * @param invisible model hidden
     * @param onFire fire overlay
     * @param frozen powder snow shake
     * @param variant variant name or id
     * @param color dye colour name
     * @param profession villager profession id
     * @param charged charged creeper
     * @param saddled saddle equipped
     * @param armorStand armor stand flags and poses
     */
    record Look(String type, @Nullable String skin, @Nullable String data, boolean invisible, boolean onFire, boolean frozen,
            @Nullable String variant, @Nullable String color, @Nullable String profession, boolean charged, boolean saddled,
            @Nullable ArmorStandProps armorStand) {

        static Look of(final DisplayEntityData d) {
            return new Look(d.type(), d.skin(), d.data(), d.invisible(), d.onFire(), d.frozen(), d.variant(), d.color(),
                    d.profession(), d.charged(), d.saddled(), d.armorStand());
        }
    }

    /**
     * A mannequin whose skin comes from a profile lookup instead of its synced data, since detached
     * entities never tick and so never finish vanilla's own skin lookup (26.3 {@code ClientMannequin.tick}).
     */
    private static final class DisplayAvatar extends ClientMannequin {
        private final Supplier<PlayerSkinRenderCache.RenderInfo> skin;

        DisplayAvatar(final ClientLevel level, final PlayerSkinRenderCache cache, final @Nullable String profile) {
            super(level, cache);
            this.skin = profile == null ? () -> cache.getOrDefault(ResolvableProfile.Static.EMPTY) : cache.createLookup(profile(profile));
        }

        @Override
        public PlayerSkin getSkin() {
            return skin.get().playerSkin();
        }

        @Override
        public @Nullable Component belowNameDisplay() {
            return null;
        }
    }

    /**
     * Returns an unresolved profile for a player name or UUID, as player skins and heads use.
     *
     * @param nameOrUuid a player name or a UUID with dashes
     * @return the profile
     */
    static ResolvableProfile profile(final String nameOrUuid) {
        try {
            return ResolvableProfile.createUnresolved(UUID.fromString(nameOrUuid));
        } catch (final IllegalArgumentException e) {
            return ResolvableProfile.createUnresolved(nameOrUuid);
        }
    }

    /**
     * Creates the entity for a display with its data and appearance applied, or null when the type is
     * unknown or cannot be created.
     *
     * @param level the client level, used only for light, registries and models
     */
    static @Nullable Entity create(final ClientLevel level, final DisplayEntityData d) {
        Entity entity = createUnnumbered(level, d);
        if (entity == null) {
            return null;
        }
        final String data = d.data();
        if (data != null && !load(entity, data, level)) {
            // A failed load can leave the entity half read; start again without the data.
            entity = createUnnumbered(level, d);
            if (entity == null) {
                return null;
            }
        }
        // Vanilla renderers read the entity id (26.3 ItemModelResolver.updateForLiving). Negative ids
        // never collide with server-assigned ones, and the entity is never added to the level.
        entity.setId(NEXT_ID.getAndDecrement());
        appearance(entity, d, level);
        return entity;
    }

    private static @Nullable Entity createUnnumbered(final ClientLevel level, final DisplayEntityData d) {
        final String type = d.type().toLowerCase(Locale.ROOT);
        if (type.equals("minecraft:player") || type.equals("player") || type.equals("minecraft:mannequin")) {
            return new DisplayAvatar(level, Minecraft.getInstance().playerSkinRenderCache(), d.skin());
        }
        final Identifier id = Identifier.tryParse(type);
        if (id == null) {
            return null;
        }
        final EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (entityType == null) {
            return null;
        }
        return construct(entityType, level);
    }

    /**
     * Loads SNBT entity data through the 26.3 {@code ValueInput} path, as {@code /summon} does.
     *
     * @return false when the text does not parse or the entity rejects it; each problem is logged once
     */
    private static boolean load(final Entity entity, final String snbt, final ClientLevel level) {
        final CompoundTag tag;
        try {
            tag = TagParser.parseCompoundFully(snbt);
        } catch (final CommandSyntaxException e) {
            if (PROBLEMS.first(snbt)) {
                Constants.LOG.warn("Ignoring display entity data that is not valid SNBT ({}): {}", e.getMessage(), snbt);
            }
            return false;
        }
        for (final String key : STRIPPED_KEYS) {
            tag.remove(key);
        }
        try {
            entity.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
            return true;
        } catch (final RuntimeException e) {
            if (PROBLEMS.first(snbt)) {
                Constants.LOG.warn("Ignoring display entity data that {} rejected: {}", entity.getType(), snbt, e);
            }
            return false;
        }
    }

    /** Applies the typed appearance fields after any SNBT data, so they win over it. */
    private static void appearance(final Entity entity, final DisplayEntityData d, final ClientLevel level) {
        if (d.invisible()) {
            // Like vanilla invisible mobs and armor stands: equipment, name and glow outline still render.
            entity.setInvisible(true);
        }
        if (d.onFire()) {
            // The client reads the shared flag for the fire overlay (26.3 Entity.isOnFire on the client).
            entity.setSharedFlagOnFire(true);
        }
        if (d.frozen()) {
            // Fully frozen entities shake; the shake reads tickCount, which Displays.tick advances.
            entity.setTicksFrozen(entity.getTicksRequiredToFreeze());
        }
        final RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
        final String variant = d.variant();
        if (variant != null) {
            for (final DataComponentType<?> type : VARIANTS) {
                component(entity, type, variant, ops);
            }
        }
        final String color = d.color();
        if (color != null) {
            for (final DataComponentType<?> type : COLORS) {
                component(entity, type, color, ops);
            }
            if (entity instanceof Llama llama) {
                final DyeColor dye = DyeColor.byName(bare(color.toLowerCase(Locale.ROOT)), null);
                if (dye != null) {
                    final Item carpet = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(dye.getSerializedName() + "_carpet"));
                    llama.setItemSlot(EquipmentSlot.BODY, new ItemStack(carpet));
                }
            }
        }
        final String profession = d.profession();
        if (profession != null && entity instanceof VillagerDataHolder villager) {
            final Identifier id = Identifier.tryParse(profession.toLowerCase(Locale.ROOT));
            if (id != null) {
                BuiltInRegistries.VILLAGER_PROFESSION.get(id).ifPresent(p ->
                        villager.setVillagerData(villager.getVillagerData().withProfession(p)));
            }
        }
        if (d.charged() && entity instanceof Creeper creeper) {
            creeper.getEntityData().set(CreeperAccessor.mfbridge$poweredKey(), Boolean.TRUE);
        }
        if (d.saddled() && entity instanceof LivingEntity living) {
            living.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
        }
        final ArmorStandProps props = d.armorStand();
        if (props != null && entity instanceof ArmorStand stand) {
            armorStand(stand, props);
        }
    }

    private static void armorStand(final ArmorStand stand, final ArmorStandProps props) {
        final ArmorStandInvoker invoker = (ArmorStandInvoker) stand;
        invoker.mfbridge$setSmall(props.small());
        invoker.mfbridge$setMarker(props.marker());
        stand.setShowArms(props.showArms());
        stand.setNoBasePlate(props.noBasePlate());
        final Rotations head = rotations(props.headPose());
        if (head != null) {
            stand.setHeadPose(head);
        }
        final Rotations body = rotations(props.bodyPose());
        if (body != null) {
            stand.setBodyPose(body);
        }
        final Rotations leftArm = rotations(props.leftArmPose());
        if (leftArm != null) {
            stand.setLeftArmPose(leftArm);
        }
        final Rotations rightArm = rotations(props.rightArmPose());
        if (rightArm != null) {
            stand.setRightArmPose(rightArm);
        }
        final Rotations leftLeg = rotations(props.leftLegPose());
        if (leftLeg != null) {
            stand.setLeftLegPose(leftLeg);
        }
        final Rotations rightLeg = rotations(props.rightLegPose());
        if (rightLeg != null) {
            stand.setRightLegPose(rightLeg);
        }
    }

    /** Decodes a variant or colour name with the component's own codec and hands it to the entity. */
    private static <T> void component(final Entity entity, final DataComponentType<T> type, final String value, final RegistryOps<Tag> ops) {
        final Codec<T> codec = type.codec();
        if (codec == null) {
            return;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        Optional<T> decoded = codec.parse(ops, StringTag.valueOf(lower)).result();
        if (decoded.isEmpty() && !bare(lower).equals(lower)) {
            // Enum-backed variants (fox, parrot, axolotl) use bare names; registry variants take ids.
            decoded = codec.parse(ops, StringTag.valueOf(bare(lower))).result();
        }
        decoded.ifPresent(v -> entity.setComponent(type, v));
    }

    private static String bare(final String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** Armor stand poses are x, y, z rotations in degrees. */
    private static @Nullable Rotations rotations(final float @Nullable [] pose) {
        return pose == null || pose.length < 3 ? null : new Rotations(pose[0], pose[1], pose[2]);
    }

    /**
     * Returns the swirl particles of a display's status effects.
     *
     * @return one particle option per known effect; empty when there are none. Immutable.
     */
    static List<ParticleOptions> effectParticles(final DisplayEntityData d) {
        final List<String> effects = d.effects();
        if (effects == null || effects.isEmpty()) {
            return List.of();
        }
        final List<ParticleOptions> out = new ArrayList<>(effects.size());
        for (final String effect : effects) {
            final Identifier id = Identifier.tryParse(effect.toLowerCase(Locale.ROOT));
            final MobEffect type = id == null ? null : BuiltInRegistries.MOB_EFFECT.getValue(id);
            if (type != null) {
                out.add(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, ARGB.opaque(type.getColor())));
            }
        }
        return List.copyOf(out);
    }

    /** Constructs without spawn rules, so hostile mobs work on peaceful difficulty too. */
    @SuppressWarnings("unchecked")
    private static <T extends Entity> T construct(final EntityType<T> type, final ClientLevel level) {
        return ((EntityTypeAccessor<T>) (Object) type).mfbridge$factory().create(type, level);
    }

    /**
     * Applies the display's pose, rotation, name, age and equipment to its entity. With SNBT data,
     * unset name, age and equipment keep what the data loaded instead of the schema defaults.
     */
    static void configure(final Entity entity, final DisplayEntityData d) {
        entity.setYRot(d.yaw());
        entity.yRotO = d.yaw();
        entity.setXRot(d.pitch());
        entity.xRotO = d.pitch();
        final float head = d.headYaw() != null ? d.headYaw() : d.yaw();
        entity.setYHeadRot(head);
        entity.setNoGravity(true);
        final Pose pose = pose(d.pose());
        entity.setPose(pose);
        final boolean data = d.data() != null;
        final String name = d.name();
        if (name != null || !data) {
            entity.setCustomName(name == null ? null : Component.literal(name));
            entity.setCustomNameVisible(name != null && d.nameVisible());
        }
        if (entity instanceof Mob mob && (d.baby() || !data)) {
            mob.setBaby(d.baby());
        }
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = d.yaw();
            living.yBodyRotO = d.yaw();
            living.yHeadRot = head;
            living.yHeadRotO = head;
            final Map<String, SlotItem> equipment = d.equipment();
            if (equipment == null && data) {
                return;
            }
            for (final EquipmentSlot slot : EquipmentSlot.values()) {
                final String key = slotName(slot);
                if (key != null) {
                    final SlotItem item = equipment == null ? null : equipment.get(key);
                    living.setItemSlot(slot, item == null ? ItemStack.EMPTY : SlotItems.stack(item));
                }
            }
        }
    }

    private static Pose pose(final @Nullable String name) {
        if (name != null) {
            for (final Pose p : Pose.values()) {
                if (p.getSerializedName().equalsIgnoreCase(name)) {
                    return p;
                }
            }
        }
        return Pose.STANDING;
    }

    /** Equipment keys {@code head | chest | legs | feet | mainhand | offhand}. */
    private static @Nullable String slotName(final EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            case MAINHAND -> "mainhand";
            case OFFHAND -> "offhand";
            case BODY, SADDLE -> null;
        };
    }

    /** Moves an entity to a world position without leaving interpolation history behind. */
    static void place(final Entity entity, final double x, final double y, final double z) {
        entity.setPos(x, y, z);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
    }
}
