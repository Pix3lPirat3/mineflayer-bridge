package io.github.pix3lpirat3.mfbridge.core.traits;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayBlockData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads an object's traits. The per-kind {@code interactive} and {@code
 * hover} fields of displays are aliases kept for one release; the traits block wins when both are
 * present. Pure functions.
 */
@AnyThread
public final class Traits {
    private Traits() {}

    /**
     * Returns the effective interaction spec.
     *
     * @param traits the object's traits, or null
     * @param data the decoded kind data, or null
     * @return {@code traits.interactive}, else the display's own field, else null
     */
    public static @Nullable Interactive interactive(@Nullable ObjectTraits traits, @Nullable Object data) {
        if (traits != null && traits.interactive() != null) {
            return traits.interactive();
        }
        return switch (data) {
            case DisplayTextData d -> d.interactive();
            case DisplayItemData d -> d.interactive();
            case DisplayBlockData d -> d.interactive();
            case DisplayEntityData d -> d.interactive();
            case null, default -> null;
        };
    }

    /**
     * Returns the effective hover style.
     *
     * @param traits the object's traits, or null
     * @param data the decoded kind data, or null
     * @return {@code traits.hover}, else the display's own field, else null
     */
    public static @Nullable HoverStyle hover(@Nullable ObjectTraits traits, @Nullable Object data) {
        if (traits != null && traits.hover() != null) {
            return traits.hover();
        }
        return switch (data) {
            case DisplayTextData d -> d.hover();
            case DisplayItemData d -> d.hover();
            case DisplayBlockData d -> d.hover();
            case DisplayEntityData d -> d.hover();
            case null, default -> null;
        };
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
    public static boolean targetable(@Nullable ObjectTraits traits, @Nullable Object data) {
        Visibility v = visibleWhen(traits);
        return interactive(traits, data) != null || !editable(traits).isEmpty() || !actions(traits).isEmpty()
                || hover(traits, data) != null || v != null && v.lookedAt();
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
