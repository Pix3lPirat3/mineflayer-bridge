package io.github.pix3lpirat3.mfbridge.common.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * One playing sound emitter. {@link BotSounds} sets its position and volume, the Bot sounds setting
 * included, every tick, so both apply while it plays. A blob sound resolves to a streamed file the sound engine
 * opens through {@link BridgeAudio}; an event sound resolves through the game's sound registry.
 */
final class EmitterSound extends AbstractTickableSoundInstance {

    /** How far a custom file is heard at volume 1, blocks, as for most vanilla sounds. */
    private static final int ATTENUATION = 16;

    private final boolean blob;

    /**
     * Creates the sound. With {@code seamless} the engine loops it; an interval loop plays once, and
     * {@link BotSounds} starts it again after the interval, so the pause is exact.
     */
    EmitterSound(final Identifier id, final boolean blob, final SoundSource source, final boolean positional, final boolean seamless) {
        super(SoundEvent.createVariableRangeEvent(id), source, SoundInstance.createUnseededRandom());
        this.blob = blob;
        this.looping = seamless;
        this.relative = !positional;
        this.attenuation = positional ? Attenuation.LINEAR : Attenuation.NONE;
    }

    /** Sets where and how loud it plays; called every tick while it plays. */
    void set(final double x, final double y, final double z, final float volume, final float pitch) {
        if (relative) {
            this.x = 0d;
            this.y = 0d;
            this.z = 0d;
        } else {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        this.volume = volume;
        this.pitch = pitch;
    }

    void end() {
        stop();
    }

    @Override
    public void tick() {
    }

    @Override
    public WeighedSoundEvents getOrResolve(final SoundManager manager) {
        if (!blob) {
            return super.getOrResolve(manager);
        }
        final Identifier id = getIdentifier();
        final WeighedSoundEvents events = new WeighedSoundEvents(id, null);
        sound = new Sound(id, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, true, false, ATTENUATION);
        events.addSound(sound);
        soundEvent = events;
        return events;
    }
}
