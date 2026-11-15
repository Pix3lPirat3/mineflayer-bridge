package io.github.pix3lpirat3.mfbridge.common.sound;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobDecoders;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectSound;
import io.github.pix3lpirat3.mfbridge.protocol.core.SoundEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * Plays every bot sound: one-shots from {@code effect.play}, the sounds
 * of screen controls, and sound emitters. Emitters work per tick: between {@link #beginTick} and
 * {@link #endTick} the display layer reports each emitter that exists with its resolved position, and
 * whatever was not reported stops. So removing, hiding or detaching an emitter stops its sound with no
 * extra bookkeeping. The Bot sounds setting scales all of them, emitters live.
 */
public final class BotSounds {

    /** Beyond this many blocks per unit of volume, plus {@link #RANGE_MARGIN}, a positional emitter holds no channel. */
    private static final double RANGE_PER_VOLUME = 16d;
    private static final double RANGE_MARGIN = 8d;
    /** A sound that stopped by itself, such as a missing event, is tried again at most this often, ms. */
    private static final long RETRY_MS = 1000L;

    private final Map<String, Playing> emitters = new HashMap<>();
    private final Set<String> audioInUse = new HashSet<>();
    private float gain = 1f;

    private static final class Playing {
        @Nullable SoundEmitterData data;
        @Nullable EmitterSound instance;
        /** The {@code plays} value last started, so a non-looping sound plays once per value. */
        long playedFor = -1L;
        /** Earliest time to start again: after a loop's interval, or a retry delay for a sound that failed. */
        long nextAtMs;
        boolean seen;
    }

    /**
     * Sets the Bot sounds volume.
     *
     * @param value {@code 0..1}
     */
    public void setGain(final float value) {
        gain = Math.clamp(value, 0f, 1f);
    }

    /**
     * Plays a one-shot sound; not positional without a position, as a UI sound.
     *
     * @param s the sound
     */
    public void oneShot(final EffectSound s) {
        final Identifier id = Identifier.tryParse(s.id());
        if (id == null || gain <= 0f) {
            return;
        }
        final SoundEvent event = SoundEvent.createVariableRangeEvent(id);
        final Vec3d at = s.at();
        final float volume = s.volume() * gain;
        final SoundInstance instance = at == null
                ? new SimpleSoundInstance(event.location(), source(s.category(), SoundSource.UI), volume, s.pitch(), SoundInstance.createUnseededRandom(),
                        false, 0, SoundInstance.Attenuation.NONE, 0d, 0d, 0d, true)
                : new SimpleSoundInstance(event, source(s.category(), SoundSource.NEUTRAL), volume, s.pitch(), SoundInstance.createUnseededRandom(),
                        at.x(), at.y(), at.z());
        Minecraft.getInstance().getSoundManager().play(instance);
    }

    /**
     * Plays the sound of a screen control the player used.
     *
     * @param soundId a sound event id, or null for none
     */
    public void ui(final @Nullable String soundId) {
        if (soundId == null) {
            return;
        }
        oneShot(new EffectSound(soundId).withCategory("ui"));
    }

    /** Starts a tick of emitter reports. */
    public void beginTick() {
        audioInUse.clear();
        for (final Playing p : emitters.values()) {
            p.seen = false;
        }
    }

    /**
     * Reports an emitter that exists this tick.
     *
     * @param key the object key, stable across revisions
     * @param d the emitter
     * @param x resolved world position
     * @param y resolved world position
     * @param z resolved world position
     * @param blobs where custom audio comes from, or null while the core is down
     */
    public void emitter(final String key, final SoundEmitterData d, final double x, final double y, final double z,
            final @Nullable BlobStore blobs) {
        Playing p = emitters.get(key);
        final SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (p != null && p.data != null && !sameSound(p.data, d)) {
            stop(manager, p);
            p = null;
        }
        if (p == null) {
            p = new Playing();
            emitters.put(key, p);
        }
        final boolean replay = p.data != null && p.data.plays() != d.plays();
        p.data = d;
        p.seen = true;
        final Identifier id = soundId(d, blobs);
        if (id == null) {
            return;
        }
        if (replay) {
            stop(manager, p);
        }
        if (!inRange(d, x, y, z)) {
            stop(manager, p);
            p.playedFor = d.loop() ? p.playedFor : d.plays();
            return;
        }
        final EmitterSound playing = p.instance;
        if (playing != null && manager.isActive(playing)) {
            playing.set(x, y, z, d.volume() * gain, d.pitch());
            return;
        }
        final long now = System.currentTimeMillis();
        if (playing != null) {
            // It ended: an interval loop waits its interval; a seamless loop only ends when it failed.
            p.instance = null;
            p.nextAtMs = now + (d.intervalMs() > 0 ? d.intervalMs() : RETRY_MS);
        }
        if (!d.loop() && p.playedFor == d.plays() || now < p.nextAtMs) {
            return;
        }
        final EmitterSound s = new EmitterSound(id, d.blob() != null, source(d.category(), SoundSource.NEUTRAL), d.positional(),
                d.loop() && d.intervalMs() == 0);
        s.set(x, y, z, d.volume() * gain, d.pitch());
        final SoundEngine.PlayResult result = manager.play(s);
        Constants.LOG.debug("Sound emitter {} started {} at {}, {}, {}: {}", key, id, x, y, z, result);
        p.instance = s;
        p.playedFor = d.plays();
    }

    /** Ends a tick of reports: emitters not reported stop, and audio no emitter uses is let go. */
    public void endTick() {
        final SoundManager manager = Minecraft.getInstance().getSoundManager();
        for (final Iterator<Playing> it = emitters.values().iterator(); it.hasNext(); ) {
            final Playing p = it.next();
            if (!p.seen) {
                stop(manager, p);
                it.remove();
            }
        }
        BridgeAudio.retain(audioInUse);
    }

    /** Stops every emitter, for example when the core stops or the player leaves the world. */
    public void stopAll() {
        final SoundManager manager = Minecraft.getInstance().getSoundManager();
        for (final Playing p : emitters.values()) {
            stop(manager, p);
        }
        emitters.clear();
        audioInUse.clear();
        BridgeAudio.retain(audioInUse);
    }

    /** The id to play, loading custom audio from the blob cache first; null while it is not ready. */
    private @Nullable Identifier soundId(final SoundEmitterData d, final @Nullable BlobStore blobs) {
        if (d.blob() == null) {
            return d.sound() == null ? null : Identifier.tryParse(d.sound());
        }
        final BlobRef ref = BlobRef.tryOf(d.blob(), d.size());
        if (ref == null) {
            return null;
        }
        audioInUse.add(ref.hex());
        if (!BridgeAudio.has(ref.hex())) {
            final byte[] ogg = blobs == null ? null : blobs.decodedNow(ref, BlobDecoders.OGG);
            if (ogg == null) {
                return null;
            }
            BridgeAudio.put(ref.hex(), ogg);
        }
        return BridgeAudio.id(ref.hex());
    }

    private static boolean inRange(final SoundEmitterData d, final double x, final double y, final double z) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (!d.positional() || player == null) {
            return player != null;
        }
        final double range = RANGE_PER_VOLUME * Math.max(1d, d.volume()) + RANGE_MARGIN;
        return player.distanceToSqr(x, y, z) <= range * range;
    }

    private static void stop(final SoundManager manager, final Playing p) {
        final EmitterSound s = p.instance;
        p.instance = null;
        if (s != null) {
            s.end();
            manager.stop(s);
        }
    }

    /** What decides the sound itself; a change restarts it. Position, volume and pitch apply live. */
    private static boolean sameSound(final SoundEmitterData a, final SoundEmitterData b) {
        return Objects.equals(a.sound(), b.sound()) && Arrays.equals(a.blob(), b.blob()) && a.loop() == b.loop()
                && a.intervalMs() == b.intervalMs() && a.category().equals(b.category()) && a.positional() == b.positional();
    }

    /** The vanilla category with this wire name, such as {@code ambient}. */
    static SoundSource source(final @Nullable String name, final SoundSource fallback) {
        if (name != null) {
            for (final SoundSource s : SoundSource.values()) {
                if (s.getName().equals(name)) {
                    return s;
                }
            }
        }
        return fallback;
    }
}
