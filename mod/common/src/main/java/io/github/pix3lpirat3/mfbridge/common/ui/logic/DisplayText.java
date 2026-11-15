package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Short texts and key rules for the Bridge home and device lists:
 * how old a timestamp is, a one-line presence status, and which owner an object key belongs to.
 */
public final class DisplayText {

    /** Most status entries shown on one line. */
    private static final int MAX_STATUS_ENTRIES = 4;

    private DisplayText() {
    }

    /**
     * Formats how long ago something happened.
     *
     * @param t resolves the text
     * @param thenMs wall-clock time in ms, or 0 or less for never
     * @param nowMs the current wall-clock time in ms
     * @return such as {@code just now}, {@code 5 min ago} or {@code never}
     */
    public static String age(final Text t, final long thenMs, final long nowMs) {
        if (thenMs <= 0) {
            return t.get("mineflayer_bridge.age.never");
        }
        final long s = Math.max(0, (nowMs - thenMs) / 1000);
        if (s < 60) {
            return t.get("mineflayer_bridge.age.just_now");
        }
        if (s < 3600) {
            return t.get("mineflayer_bridge.age.minutes", s / 60);
        }
        if (s < 86_400) {
            return t.get("mineflayer_bridge.age.hours", s / 3600);
        }
        return t.get("mineflayer_bridge.age.days", s / 86_400);
    }

    /**
     * Turns a {@code presence.status} value into one line. A map with a string {@code text} entry
     * shows that text; any other map shows its first entries as {@code key: value}; anything else
     * shows as a string.
     *
     * @param status the decoded status, or null when the bot set none
     * @return the line, empty when there is nothing to show
     */
    public static String status(final @Nullable Object status) {
        return switch (status) {
            case null -> "";
            case Map<?, ?> map when map.get("text") instanceof String text -> text;
            case Map<?, ?> map -> {
                final List<String> parts = new ArrayList<>();
                for (final Map.Entry<?, ?> e : map.entrySet()) {
                    if (parts.size() == MAX_STATUS_ENTRIES) {
                        parts.add("...");
                        break;
                    }
                    parts.add(e.getKey() + ": " + scalar(e.getValue()));
                }
                yield String.join(", ", parts);
            }
            default -> scalar(status);
        };
    }

    private static String scalar(final @Nullable Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString(d.longValue());
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return "...";
        }
        return String.valueOf(value);
    }

    /**
     * Returns the owner of a bot identity.
     *
     * @param identity the bot identity
     * @return {@code bot:<identity>}
     */
    public static String botOwner(final String identity) {
        return "bot:" + identity;
    }

    /**
     * Returns whether an object key lies under an owner. Controller owners contain a slash themselves
     * ({@code ctl:<device>/<name>}), so the check is a prefix with the separator, never a split.
     *
     * @param key a full object key
     * @param owner an owner such as {@code bot:<identity>}
     * @return true when the key starts with {@code owner + "/"}
     */
    public static boolean ownedBy(final String key, final String owner) {
        return key.length() > owner.length() && key.startsWith(owner) && key.charAt(owner.length()) == '/';
    }
}
