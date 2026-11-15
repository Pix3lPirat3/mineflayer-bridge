package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.core.MerchantOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Checks prompt answers that come from the extra vanilla surfaces, whose result
 * depends on the surface rather than the prompt kind: {@code stonecutter} answers an option id,
 * {@code enchanting} an option id or index, {@code merchant} an offer id and {@code crafting} a
 * 3x3 pattern as a list of nine item ids or nulls.
 */
final class PromptSurfaces {
    /** Cells in a crafting pattern. */
    static final int GRID = 9;

    private PromptSurfaces() {}

    /** Returns whether answers from this surface are checked here instead of by prompt kind. */
    static boolean handles(@Nullable String surface) {
        return surface != null && switch (surface) {
            case "stonecutter", "enchanting", "merchant", "crafting" -> true;
            default -> false;
        };
    }

    /**
     * Checks an answer from one of the surfaces {@link #handles} accepts.
     *
     * @return the value to send: a {@code String} id, a {@code Long} index, or an unmodifiable list
     * @throws IllegalArgumentException when the answer does not fit the surface, with a message for
     *     the player
     */
    static Object value(PromptRequest r, String surface, Object value) {
        return switch (surface) {
            case "stonecutter" -> optionId(r.options(), value);
            case "enchanting" -> value instanceof String ? optionId(r.options(), value) : optionIndex(r.options(), value);
            case "merchant" -> offerId(r.offers(), value);
            case "crafting" -> pattern(r.options(), value);
            default -> throw new IllegalArgumentException("surface " + surface + " has no special answers");
        };
    }

    private static String optionId(@Nullable List<WorldMenuEntry> options, Object value) {
        String id = ValueRules.string(value, "the answer");
        if (options != null) {
            for (WorldMenuEntry e : options) {
                if (e.id().equals(id)) {
                    return id;
                }
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not one of the options");
    }

    private static Long optionIndex(@Nullable List<WorldMenuEntry> options, Object value) {
        long index = ValueRules.integral(value, "the answer");
        int size = options == null ? 0 : options.size();
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("option " + index + " does not exist");
        }
        return index;
    }

    private static String offerId(@Nullable List<MerchantOffer> offers, Object value) {
        String id = ValueRules.string(value, "the answer");
        if (offers != null) {
            for (MerchantOffer o : offers) {
                if (o.id().equals(id)) {
                    if (o.disabled()) {
                        throw new IllegalArgumentException("offer '" + id + "' is out of stock");
                    }
                    return id;
                }
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not one of the offers");
    }

    /** A palette from {@code options} limits the pattern to its items; without one any id goes. */
    private static List<@Nullable String> pattern(@Nullable List<WorldMenuEntry> options, Object value) {
        if (!(value instanceof List<?> list) || list.size() != GRID) {
            throw new IllegalArgumentException("the pattern must be a list of " + GRID + " item ids or nulls");
        }
        Set<String> palette = new HashSet<>();
        if (options != null) {
            for (WorldMenuEntry e : options) {
                SlotItem item = e.item();
                if (item != null) {
                    palette.add(item.id());
                }
            }
        }
        List<@Nullable String> out = new ArrayList<>(GRID);
        for (Object cell : list) {
            if (cell == null) {
                out.add(null);
                continue;
            }
            String id = ValueRules.string(cell, "each cell");
            if (!palette.isEmpty() && !palette.contains(id)) {
                throw new IllegalArgumentException("'" + id + "' is not in the palette");
            }
            out.add(id);
        }
        return Collections.unmodifiableList(out);
    }
}
