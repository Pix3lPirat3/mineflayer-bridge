package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.display.logic.Animation;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayBlockData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTransform;
import io.github.pix3lpirat3.mfbridge.protocol.core.GroupData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.ParticleEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SoundEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import org.jspecify.annotations.Nullable;

/**
 * The fields every display kind, {@code core:group} and the emitters share, read once
 * per revision so the frame loop does not switch over record types.
 *
 * @param pos position, world blocks or relative to the group
 * @param scale the kind's {@code scale} field
 * @param group shape id of the parent group in the same layer, or null
 * @param transform the transform, or null
 * @param billboard {@code fixed}, {@code vertical}, {@code horizontal} or {@code center}
 * @param interpolateMs interpolation length, ms
 * @param animation the client-side loop
 * @param amplitude loop amplitude
 * @param periodMs loop period, ms
 * @param brightness fixed light 0-15, or null for world light
 * @param glow outline colour {@code 0xAARRGGBB}, or null
 * @param hover style while hovered, or null
 * @param interactive hit box and inputs, or null
 * @param maxDistance render distance, blocks
 */
record DisplayFields(
        Vec3d pos,
        float scale,
        @Nullable String group,
        @Nullable DisplayTransform transform,
        String billboard,
        int interpolateMs,
        Animation animation,
        float amplitude,
        int periodMs,
        @Nullable Integer brightness,
        @Nullable Integer glow,
        @Nullable HoverStyle hover,
        @Nullable Interactive interactive,
        float maxDistance) {

    /**
     * Reads the shared fields of a display or group with its traits applied:
     * {@code traits.interactive} and {@code traits.hover} win over the kind's own fields.
     *
     * @return the fields, or null for other data
     */
    static @Nullable DisplayFields of(final @Nullable Object data, final @Nullable ObjectTraits traits) {
        final DisplayFields f = of(data);
        if (f == null || traits == null || data instanceof GroupData) {
            return f;
        }
        return new DisplayFields(f.pos, f.scale, f.group, f.transform, f.billboard, f.interpolateMs, f.animation, f.amplitude,
                f.periodMs, f.brightness, f.glow, Traits.hover(traits, data), Traits.interactive(traits, data), f.maxDistance);
    }

    /** Emitters follow groups: an emitter is placed like a display, with nothing else to draw. */
    private static DisplayFields placement(final Vec3d pos, final @Nullable String group) {
        return new DisplayFields(pos, 1f, group, null, "fixed", 0, Animation.NONE, 0f, 1, null, null, null, null, Float.MAX_VALUE);
    }

    /** Reads the shared fields of a display, group or emitter, or returns null for other data. */
    static @Nullable DisplayFields of(final @Nullable Object data) {
        return switch (data) {
            case DisplayTextData d -> new DisplayFields(d.pos(), d.scale(), d.group(), d.transform(), d.billboard(),
                    d.interpolateMs(), Animation.of(d.animate()), d.animateAmplitude(), d.animatePeriodMs(), d.brightness(),
                    d.glow(), d.hover(), d.interactive(), d.maxDistance());
            case DisplayItemData d -> new DisplayFields(d.pos(), d.scale(), d.group(), d.transform(), d.billboard(),
                    d.interpolateMs(), Animation.of(d.animate()), d.animateAmplitude(), d.animatePeriodMs(), d.brightness(),
                    d.glow(), d.hover(), d.interactive(), d.maxDistance());
            case DisplayBlockData d -> new DisplayFields(d.pos(), d.scale(), d.group(), d.transform(), d.billboard(),
                    d.interpolateMs(), Animation.of(d.animate()), d.animateAmplitude(), d.animatePeriodMs(), d.brightness(),
                    d.glow(), d.hover(), d.interactive(), d.maxDistance());
            case DisplayEntityData d -> new DisplayFields(d.pos(), d.scale(), d.group(), d.transform(), d.billboard(),
                    d.interpolateMs(), Animation.of(d.animate()), d.animateAmplitude(), d.animatePeriodMs(), d.brightness(),
                    d.glow(), d.hover(), d.interactive(), d.maxDistance());
            case GroupData g -> new DisplayFields(g.pos(), 1f, null, g.transform(), g.billboard(), g.interpolateMs(),
                    Animation.NONE, 0f, 1, null, null, null, null, Float.MAX_VALUE);
            case ParticleEmitterData e -> placement(e.pos(), e.group());
            case SoundEmitterData s -> placement(s.pos(), s.group());
            case null, default -> null;
        };
    }
}
