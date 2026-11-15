package io.github.pix3lpirat3.mfbridge.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A client-resolved target selector, parsed from text such as {@code
 * @nearest[type=zombie,distance=..16,from=bot]}. Selectors are resolved by the client through
 * {@link EntityQuery} before {@code command.invoked} is sent, so bots only see entity ids or
 * positions.
 *
 * <p>Grammar: {@code @me}, {@code @self}, {@code @looked}, {@code @nearest[options]} and {@code
 * @all[options]}, where the bracketed options are optional and comma-separated {@code key=value}
 * pairs: {@code type} (an entity type id, repeatable, any of them matches), {@code tag} (repeatable,
 * all must match), {@code distance} ({@code N}, {@code ..N}, {@code N..} or {@code M..N}, in
 * blocks), {@code from} ({@code player} or {@code bot}) and {@code limit} (a positive whole number,
 * for {@code @all}).
 */
public sealed interface Selector {
    /** The selector names, without the {@code @}. */
    List<String> NAMES = List.of("me", "self", "looked", "nearest", "all");

    /** The option keys of {@code @nearest} and {@code @all}. */
    List<String> OPTIONS = List.of("type", "tag", "distance", "from", "limit");

    /** The player. */
    record Me() implements Selector {}

    /** The bot that owns the command, found by username in the client level. */
    record Self() implements Selector {}

    /** The entity under the crosshair, or for positions the block under it. */
    record Looked() implements Selector {}

    /**
     * The nearest matching entity.
     *
     * @param filter the constraints
     */
    record Nearest(Filter filter) implements Selector {}

    /**
     * Every matching entity, nearest first.
     *
     * @param filter the constraints
     */
    record All(Filter filter) implements Selector {}

    /**
     * Entity constraints of {@code @nearest} and {@code @all}.
     *
     * @param types namespaced entity type ids, any of which matches; empty matches every type. Immutable.
     * @param tags entity tags (scoreboard tags), all of which must be present. Immutable.
     * @param minDistance least distance in blocks, or null
     * @param maxDistance greatest distance in blocks, or null
     * @param fromBot whether distances are measured from the bot instead of the player
     * @param limit the most entities {@code @all} selects, or null for no limit
     */
    record Filter(List<String> types, List<String> tags, @Nullable Double minDistance, @Nullable Double maxDistance,
            boolean fromBot, @Nullable Integer limit) {
        /** No constraints, measured from the player. */
        public static final Filter ANY = new Filter(List.of(), List.of(), null, null, false, null);

        /** Copies the lists. */
        public Filter {
            types = List.copyOf(types);
            tags = List.copyOf(tags);
        }

        /**
         * Returns whether a distance satisfies the range.
         *
         * @param distance in blocks
         * @return whether it is within the bounds
         */
        public boolean inRange(double distance) {
            return (minDistance == null || distance >= minDistance) && (maxDistance == null || distance <= maxDistance);
        }
    }

    /**
     * Returns whether text is written as a selector: it starts with {@code @}.
     *
     * @param text a token
     * @return whether to parse it with {@link #parse}
     */
    static boolean looksLike(String text) {
        return text.startsWith("@");
    }

    /**
     * Parses a selector.
     *
     * @param text the whole token, starting with {@code @}
     * @return the selector
     * @throws ArgException when the text is not a valid selector; the range is relative to {@code text}
     */
    static Selector parse(String text) {
        if (!looksLike(text)) {
            throw new ArgException("A selector starts with @", 0, Math.min(1, text.length()));
        }
        int bracket = text.indexOf('[');
        String name = (bracket < 0 ? text.substring(1) : text.substring(1, bracket)).toLowerCase(Locale.ROOT);
        int nameEnd = bracket < 0 ? text.length() : bracket;
        if (!NAMES.contains(name)) {
            throw new ArgException("Unknown selector '@" + name + "'; use @me, @self, @looked, @nearest or @all", 0, nameEnd);
        }
        if (bracket < 0) {
            return switch (name) {
                case "me" -> new Me();
                case "self" -> new Self();
                case "looked" -> new Looked();
                case "nearest" -> new Nearest(Filter.ANY);
                default -> new All(Filter.ANY);
            };
        }
        if (!name.equals("nearest") && !name.equals("all")) {
            throw new ArgException("@" + name + " takes no options", bracket, text.length());
        }
        if (!text.endsWith("]")) {
            throw new ArgException("Expected ']' to close the selector options", text.length(), text.length());
        }
        Filter filter = options(text, bracket + 1, text.length() - 1);
        return name.equals("nearest") ? new Nearest(filter) : new All(filter);
    }

    private static Filter options(String text, int from, int to) {
        List<String> types = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        Double min = null;
        Double max = null;
        boolean fromBot = false;
        Integer limit = null;
        int i = from;
        while (i < to) {
            int comma = text.indexOf(',', i);
            int end = comma < 0 || comma > to ? to : comma;
            String pair = text.substring(i, end);
            if (pair.isBlank()) {
                throw new ArgException("Empty selector option", i, end);
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new ArgException("Expected key=value in selector options", i, end);
            }
            String key = pair.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = pair.substring(eq + 1).strip();
            int valueStart = i + eq + 1;
            if (value.isEmpty()) {
                throw new ArgException("'" + key + "' needs a value", i, end);
            }
            switch (key) {
                case "type" -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    types.add(lower.indexOf(':') < 0 ? "minecraft:" + lower : lower);
                }
                case "tag" -> tags.add(value);
                case "distance" -> {
                    double[] range = range(value, valueStart, end);
                    min = Double.isNaN(range[0]) ? null : range[0];
                    max = Double.isNaN(range[1]) ? null : range[1];
                }
                case "from" -> fromBot = switch (value.toLowerCase(Locale.ROOT)) {
                    case "bot" -> true;
                    case "player" -> false;
                    default -> throw new ArgException("'from' must be player or bot", valueStart, end);
                };
                case "limit" -> {
                    int n;
                    try {
                        n = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new ArgException("'limit' must be a whole number", valueStart, end);
                    }
                    if (n < 1) {
                        throw new ArgException("'limit' must be at least 1", valueStart, end);
                    }
                    limit = n;
                }
                default -> throw new ArgException("Unknown selector option '" + key + "'; use "
                        + String.join(", ", OPTIONS), i, i + eq);
            }
            i = end + 1;
        }
        return new Filter(types, tags, min, max, fromBot, limit);
    }

    /** Parses {@code N}, {@code ..N}, {@code N..} or {@code M..N}; NaN marks an open end. */
    private static double[] range(String value, int start, int end) {
        int dots = value.indexOf("..");
        double lo;
        double hi;
        try {
            if (dots < 0) {
                lo = Double.parseDouble(value);
                hi = lo;
            } else {
                String a = value.substring(0, dots);
                String b = value.substring(dots + 2);
                if (a.isEmpty() && b.isEmpty()) {
                    throw new NumberFormatException(value);
                }
                lo = a.isEmpty() ? Double.NaN : Double.parseDouble(a);
                hi = b.isEmpty() ? Double.NaN : Double.parseDouble(b);
            }
        } catch (NumberFormatException e) {
            throw new ArgException("'distance' must be a range such as ..16, 4.. or 2..8", start, end);
        }
        if (lo < 0 || hi < 0 || Double.isInfinite(lo) || Double.isInfinite(hi) || lo > hi) {
            throw new ArgException("'distance' must be a range of non-negative numbers, lowest first", start, end);
        }
        return new double[] {lo, hi};
    }
}
