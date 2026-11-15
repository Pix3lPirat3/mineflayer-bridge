package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.InterceptData;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A {@code core:intercept} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~intercept/<id>}
 * @param ownerName display name of the owning session
 * @param ownerIdentity bot identity of the owner, or null for a controller; the cancel permission is
 *     granted per identity, so the rules of a controller only observe
 * @param data decoded declaration
 */
public record InterceptDecl(String key, String ownerName, @Nullable String ownerIdentity, InterceptData data) {
    /** Rank of {@code monitor}, the last priority; rules at it never cancel. */
    public static final int MONITOR = 5;

    /**
     * Returns the run rank: {@code highest} 0, {@code high} 1, {@code normal} 2 (also for unknown
     * names), {@code low} 3, {@code lowest} 4, {@code monitor} 5. Rules run from low to high rank, so
     * the highest priority decides first and monitor rules hear the outcome last.
     *
     * @return the rank
     */
    public int rank() {
        return switch (data.priority().toLowerCase(Locale.ROOT)) {
            case "highest" -> 0;
            case "high" -> 1;
            case "low" -> 3;
            case "lowest" -> 4;
            case "monitor" -> MONITOR;
            default -> 2;
        };
    }

    /**
     * Returns whether the rule asks to cancel; {@code monitor} rules never do.
     *
     * @return true for mode {@code cancel} below monitor priority
     */
    public boolean wantsCancel() {
        return "cancel".equalsIgnoreCase(data.mode()) && rank() != MONITOR;
    }

    /**
     * Returns the modifier bit the rule needs: bit 0 shift, bit 1 control, bit 2 alt.
     *
     * @return the bit, 0 when the rule has no modifier, or -1 for an unknown modifier, which never
     *     matches
     */
    public int modifierBit() {
        final String m = data.modifier();
        if (m == null) {
            return 0;
        }
        return switch (m.toLowerCase(Locale.ROOT)) {
            case "shift" -> 1;
            case "control", "ctrl" -> 2;
            case "alt" -> 4;
            default -> -1;
        };
    }
}
