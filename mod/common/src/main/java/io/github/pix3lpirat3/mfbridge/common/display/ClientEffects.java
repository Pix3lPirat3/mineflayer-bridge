package io.github.pix3lpirat3.mfbridge.common.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.pix3lpirat3.mfbridge.api.event.Subscription;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.sound.BotSounds;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.effect.EffectRequested;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectActionBar;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectParticles;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectTitle;
import io.github.pix3lpirat3.mfbridge.protocol.core.ParticleEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Plays client-only effects: {@code effect.play} particles, titles and
 * action-bar lines, sounds through {@link BotSounds}, and the particles of {@code core:particle-emitter} objects. Everything
 * goes through the client's own sound manager, particle engine and HUD; nothing reaches the server.
 * Client thread only.
 */
final class ClientEffects {
    /** Emitters farther than this from the player do not spawn, blocks. */
    private static final double EMITTER_RANGE = 64d;

    private final RandomSource random = RandomSource.create();
    private final BotSounds sounds;
    private @Nullable Subscription subscription;

    ClientEffects(final BotSounds sounds) {
        this.sounds = sounds;
    }

    void coreChanged(final @Nullable BridgeCore core) {
        final Subscription old = subscription;
        subscription = null;
        if (old != null) {
            old.close();
        }
        if (core != null) {
            subscription = core.events().subscribe(EffectRequested.TYPE, this::play);
        }
    }

    private void play(final EffectRequested e) {
        final Minecraft minecraft = Minecraft.getInstance();
        switch (e.effect()) {
            case EffectBody.Sound s -> sounds.oneShot(s.value());
            case EffectBody.Particles p -> {
                final ClientLevel level = minecraft.level;
                final EffectParticles v = p.value();
                final ParticleOptions options = level == null ? null : particle(v.type(), v.options(), level.registryAccess());
                if (level != null && options != null) {
                    spawn(level, options, v.at(), v.count(), v.spread(), v.speed());
                }
            }
            case EffectBody.Title t -> title(t.value());
            case EffectBody.Actionbar a -> actionBar(e.ownerName(), a.value());
        }
    }

    private static void title(final EffectTitle t) {
        Game.title(t.fadeIn(), t.stay(), t.fadeOut(), t.title() == null ? null : Component.literal(t.title()),
                t.subtitle() == null ? null : Component.literal(t.subtitle()));
    }

    /** Action-bar lines are labelled with the bot's name. */
    private static void actionBar(final String owner, final EffectActionBar a) {
        Game.overlayMessage(Component.literal(owner + ": " + a.text()), false);
    }

    /** Spawns an emitter's due bursts near the player. */
    void emit(final DisplayNode n, final ClientLevel level, final LocalPlayer player, final long nowMs) {
        if (!(n.data instanceof ParticleEmitterData d)) {
            return;
        }
        if (n.dirty) {
            n.dirty = false;
            n.particle = particle(d.particle(), d.options(), level.registryAccess());
            n.bursts.reset();
        }
        final long last = n.lastEmitMs;
        n.lastEmitMs = nowMs;
        final ParticleOptions options = n.particle;
        if (last == Long.MIN_VALUE || options == null || player.distanceToSqr(n.wx, n.wy, n.wz) > EMITTER_RANGE * EMITTER_RANGE) {
            return;
        }
        final int bursts = n.bursts.advance(d.rate(), nowMs - last);
        final Vec3d at = new Vec3d(n.wx, n.wy, n.wz);
        for (int i = 0; i < bursts; i++) {
            spawn(level, options, at, d.count(), d.spread(), d.speed());
        }
    }

    private void spawn(final ClientLevel level, final ParticleOptions options, final Vec3d at, final int count, final @Nullable Vec3d spread,
            final float speed) {
        final int n = Math.clamp(count, 1, 256);
        for (int i = 0; i < n; i++) {
            final double x = at.x() + (spread == null ? 0d : random.nextGaussian() * spread.x());
            final double y = at.y() + (spread == null ? 0d : random.nextGaussian() * spread.y());
            final double z = at.z() + (spread == null ? 0d : random.nextGaussian() * spread.z());
            level.addParticle(options, x, y, z, random.nextGaussian() * speed, random.nextGaussian() * speed,
                    random.nextGaussian() * speed);
        }
    }

    /**
     * Resolves a particle type and its options. Options are decoded with the type's own codec from
     * the MessagePack value, so every vanilla particle with options (dust, block, item, ...) works.
     *
     * @return the options, or null when the type is unknown or the options do not decode
     */
    static @Nullable ParticleOptions particle(final String type, final @Nullable Object options, final RegistryAccess registries) {
        final Identifier id = Identifier.tryParse(type);
        final ParticleType<?> particleType = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
        if (particleType == null) {
            return null;
        }
        if (options == null && particleType instanceof SimpleParticleType simple) {
            return simple;
        }
        return decode(particleType, json(options == null ? Map.of() : options), registries);
    }

    private static <T extends ParticleOptions> @Nullable ParticleOptions decode(final ParticleType<T> type, final JsonElement json,
            final RegistryAccess registries) {
        return type.codec().codec().parse(registries.createSerializationContext(JsonOps.INSTANCE), json)
                .resultOrPartial(problem -> Constants.LOG.debug("Particle options rejected: {}", problem))
                .orElse(null);
    }

    private static JsonElement json(final @Nullable Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case String s -> new JsonPrimitive(s);
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            case Map<?, ?> m -> {
                final JsonObject o = new JsonObject();
                for (final Map.Entry<?, ?> e : m.entrySet()) {
                    o.add(String.valueOf(e.getKey()), json(e.getValue()));
                }
                yield o;
            }
            case List<?> l -> {
                final JsonArray a = new JsonArray();
                for (final Object item : l) {
                    a.add(json(item));
                }
                yield a;
            }
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }
}
