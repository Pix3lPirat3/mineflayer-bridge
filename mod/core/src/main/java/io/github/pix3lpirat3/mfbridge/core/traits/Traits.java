package io.github.pix3lpirat3.mfbridge.core.traits;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Reads an object's traits. Pure functions. */
@AnyThread
public final class Traits {
    private Traits() {}

    /**
     * Returns the interaction spec.
     *
     * @param traits the object's traits, or null
     * @return {@code traits.interactive}, or null
     */
    public static @Nullable Interactive interactive(@Nullable ObjectTraits traits) {
        return traits == null ? null : traits.interactive();
    }

    /**
     * Returns the hover style.
     *
     * @param traits the object's traits, or null
     * @return {@code traits.hover}, or null
     */
    public static @Nullable HoverStyle hover(@Nullable ObjectTraits traits) {
        return traits == null ? null : traits.hover();
    }

    /** Returns the fields the player may edit; empty when none. Immutable. */
    public static List<EditableField> editable(@Nullable ObjectTraits traits) {
        List<EditableField> e = traits == null ? null : traits.editable();
        return e == null ? List.of() : e;
    }

    /** Returns the action references bound to the object; empty when none. Immutable. */
    public static List<String> actions(@Nullable ObjectTraits traits) {
        List<String> a = traits == null ? null : traits.actions();
        return a == null ? List.of() : a;
    }

    /** Returns the visibility rule, or null when the object is always visible. */
    public static @Nullable Visibility visibleWhen(@Nullable ObjectTraits traits) {
        return traits == null ? null : traits.visibleWhen();
    }

    /**
     * Returns whether the object takes part in the bridge raycast: it is interactive, editable, has
     * bound actions or a hover style, or is only shown while looked at.
     */
    public static boolean targetable(@Nullable ObjectTraits traits) {
        Visibility v = visibleWhen(traits);
        return interactive(traits) != null || !editable(traits).isEmpty() || !actions(traits).isEmpty()
                || hover(traits) != null || v != null && v.lookedAt();
    }

    /**
     * Returns whether a visibility rule hides the object right now ({@code
     * visibleWhen}). {@code focusedOnly} is applied by the core when it builds snapshots; this checks
     * the per-frame parts.
     *
     * @param v the rule, or null for always visible
     * @param distanceSq squared distance from the camera to the object, blocks squared; NaN when
     *     unknown, which passes the distance range
     * @param lookedAt whether the object is under the crosshair
     */
    public static boolean shown(@Nullable Visibility v, double distanceSq, boolean lookedAt) {
        if (v == null) {
            return true;
        }
        if (v.lookedAt() && !lookedAt) {
            return false;
        }
        if (Double.isNaN(distanceSq)) {
            return true;
        }
        Float min = v.minDistance();
        Float max = v.maxDistance();
        return (min == null || distanceSq >= (double) min * min) && (max == null || distanceSq <= (double) max * max);
    }

    /** Returns whether the rule needs a per-frame check: a distance range or {@code lookedAt}. */
    public static boolean perFrame(@Nullable Visibility v) {
        return v != null && (v.lookedAt() || v.minDistance() != null || v.maxDistance() != null);
    }

    /** Returns whether the rule limits the object to the focused bot. */
    public static boolean focusedOnly(@Nullable ObjectTraits traits) {
        Visibility v = visibleWhen(traits);
        return v != null && v.focusedOnly();
    }
}
