package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Matches a target against a {@link TargetSpec}. An absent or
 * empty {@code ids} or {@code tags} list accepts anything; when both are given, both must match.
 */
public final class TargetMatch {

    private TargetMatch() {
    }

    /**
     * Returns whether a target of {@code type} with registry id {@code id} matches {@code spec}.
     *
     * @param spec the declaration
     * @param type the target type, for example {@code block}
     * @param id the target's registry id or entity type id, or null when it has none
     * @param hasTag whether the target carries a namespaced tag id, given without {@code #}
     * @return whether the target spec accepts the target
     */
    public static boolean matches(final TargetSpec spec, final String type, final @Nullable String id, final Predicate<String> hasTag) {
        return spec.type().equals(type) && matchesFilter(spec.ids(), spec.tags(), id, hasTag);
    }

    /**
     * Returns whether an id passes an id and tag filter, ignoring the target type, as used by pick filters.
     *
     * @param ids accepted ids, or null or empty for any
     * @param tags accepted tags (any one suffices), with or without {@code #}, or null or empty for any
     * @param id the candidate's id, or null when it has none
     * @param hasTag whether the candidate carries a namespaced tag id, given without {@code #}
     * @return whether the candidate passes
     */
    public static boolean matchesFilter(
            final @Nullable List<String> ids, final @Nullable List<String> tags, final @Nullable String id, final Predicate<String> hasTag) {
        if (ids != null && !ids.isEmpty()) {
            if (id == null) {
                return false;
            }
            final String normalized = CommandArgs.normalizeId(id);
            boolean found = false;
            for (final String candidate : ids) {
                if (CommandArgs.normalizeId(candidate).equals(normalized)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        if (tags != null && !tags.isEmpty()) {
            for (final String tag : tags) {
                if (hasTag.test(normalizeTag(tag))) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Strips a leading {@code #} and adds the {@code minecraft} namespace to a bare tag id.
     *
     * @param tag a tag such as {@code #logs} or {@code c:chests}
     * @return the namespaced tag id without {@code #}
     */
    public static String normalizeTag(final String tag) {
        final String t = tag.strip().toLowerCase(Locale.ROOT);
        return CommandArgs.normalizeId(t.startsWith("#") ? t.substring(1) : t);
    }
}
