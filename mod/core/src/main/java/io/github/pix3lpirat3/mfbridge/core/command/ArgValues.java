package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Converts the typed tokens of one argument into the value {@code command.invoked} carries:
 * {@code Long} for {@code int} and entity ids, {@code Double} for
 * {@code float}, {@code Boolean}, {@code String}, a list of {@code Long} for {@code entity_list},
 * and maps with {@code x}, {@code y}, {@code z} for positions. Selectors are resolved here, through
 * the context's {@link EntityQuery}. Client thread only, because resolution reads game state.
 *
 * <p>Positions accept three coordinates, each absolute or {@code ~}/{@code ~offset} relative to the
 * player, the word {@code here} for the crosshair target, or one selector.
 */
public final class ArgValues {
    /** The shortcut word for the crosshair target in position arguments. */
    public static final String HERE = "here";

    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private ArgValues() {}

    /**
     * Returns how many tokens an argument consumes starting at {@code start}.
     *
     * @param arg the declaration
     * @param tokens all tokens
     * @param start index of the argument's first token
     * @return 1 for most types; 3 for a position unless it starts with {@code here} or a selector;
     *     the rest of the line for {@code greedy}
     */
    public static int width(CommandArg arg, List<Token> tokens, int start) {
        return switch (arg.type()) {
            case GREEDY -> Math.max(tokens.size() - start, 1);
            case BLOCK_POS, VEC3 -> start < tokens.size() && single(tokens.get(start).text()) ? 1 : 3;
            case STRING, INT, FLOAT, BOOL, CHOICE, BLOCK_ID, ITEM_ID, ENTITY, ENTITY_LIST, PLAYER, BOT, ZONE, POINT -> 1;
        };
    }

    /** Whether a position token stands for the whole position. */
    static boolean single(String text) {
        return text.equalsIgnoreCase(HERE) || Selector.looksLike(text);
    }

    /**
     * Converts one argument.
     *
     * @param arg the declaration
     * @param parts its tokens: one, three for an explicit position, or the rest of the line for greedy
     * @param raw for {@code greedy}, the line text the tokens span, as typed; ignored otherwise
     * @param ctx the game context
     * @param bot the bot that owns the command, for {@code @self} and {@code from=bot}
     * @return the value
     * @throws ArgException with a message for the player and the offending range, in the
     *     coordinates of the tokens
     */
    public static Object parse(CommandArg arg, List<Token> parts, String raw, ArgContext ctx, BotCommands bot) {
        String name = "'" + arg.name() + "'";
        Token first = parts.getFirst();
        int start = first.start();
        int end = parts.getLast().end();
        String text = first.text();
        return switch (arg.type()) {
            case STRING, PLAYER, BOT, ZONE, POINT -> {
                range(text.length(), arg, name + " length", start, end);
                yield text;
            }
            case GREEDY -> {
                range(raw.length(), arg, name + " length", start, end);
                yield raw;
            }
            case INT -> {
                long value;
                try {
                    value = Long.parseLong(text.strip());
                } catch (NumberFormatException e) {
                    throw new ArgException(name + " must be a whole number, not '" + text + "'", start, end);
                }
                range(value, arg, name, start, end);
                yield value;
            }
            case FLOAT -> {
                double value = number(text, name, start, end);
                range(value, arg, name, start, end);
                yield value;
            }
            case BOOL -> bool(text, name, start, end);
            case CHOICE -> choice(text, arg.choices(), name, start, end);
            case BLOCK_ID -> {
                String id = normalizeId(text);
                if (!RESOURCE_ID.matcher(id).matches() || !ctx.blockExists(id)) {
                    throw new ArgException(name + ": unknown block '" + text + "'", start, end);
                }
                yield id;
            }
            case ITEM_ID -> {
                String id = normalizeId(text);
                if (!RESOURCE_ID.matcher(id).matches() || !ctx.itemExists(id)) {
                    throw new ArgException(name + ": unknown item '" + text + "'", start, end);
                }
                yield id;
            }
            case ENTITY -> entity(first, name, ctx, bot);
            case ENTITY_LIST -> {
                List<Long> ids = entities(first, name, ctx, bot);
                range(ids.size(), arg, name + " count", start, end);
                yield ids;
            }
            case BLOCK_POS -> blockPos(parts, ctx, bot, name);
            case VEC3 -> vec3(parts, ctx, bot, name);
        };
    }

    /**
     * Adds the {@code minecraft} namespace to a bare id and lower-cases it.
     *
     * @param id an id such as {@code stone} or {@code minecraft:stone}
     * @return the namespaced id
     */
    public static String normalizeId(String id) {
        String lower = id.strip().toLowerCase(Locale.ROOT);
        return lower.indexOf(':') < 0 ? "minecraft:" + lower : lower;
    }

    /**
     * Formats a number without a trailing {@code .0} for whole values.
     *
     * @param value the number
     * @return the text
     */
    public static String plain(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    private static Selector selector(Token t) {
        try {
            return Selector.parse(t.text());
        } catch (ArgException e) {
            throw e.shifted(t.start());
        }
    }

    private static long entity(Token t, String name, ArgContext ctx, BotCommands bot) {
        String text = t.text();
        if (Selector.looksLike(text)) {
            Selector s = selector(t);
            if (s instanceof Selector.All) {
                throw new ArgException(name + " takes one entity; use @nearest instead of @all", t.start(), t.end());
            }
            List<Long> ids = resolve(s, t, name, ctx, bot);
            if (ids.isEmpty()) {
                throw new ArgException(name + ": no entity matches " + text, t.start(), t.end());
            }
            return ids.getFirst();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            Integer id = ctx.entities().named(text);
            if (id == null) {
                throw new ArgException(name + ": no loaded entity or player '" + text + "'", t.start(), t.end());
            }
            return id;
        }
    }

    private static List<Long> entities(Token t, String name, ArgContext ctx, BotCommands bot) {
        List<Long> ids = new ArrayList<>();
        for (Token part : listEntries(t)) {
            String text = part.text();
            if (text.isEmpty()) {
                throw new ArgException(name + ": empty entry in the list", part.start(), part.end());
            }
            if (Selector.looksLike(text)) {
                Selector s = selector(part);
                List<Long> found = resolve(s, part, name, ctx, bot);
                if (found.isEmpty() && !(s instanceof Selector.All)) {
                    throw new ArgException(name + ": no entity matches " + text, part.start(), part.end());
                }
                for (Long id : found) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
                continue;
            }
            long id;
            try {
                id = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                Integer named = ctx.entities().named(text);
                if (named == null) {
                    throw new ArgException(name + ": no loaded entity or player '" + text + "'", part.start(), part.end());
                }
                id = named;
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Splits an {@code entity_list} token at commas outside selector brackets, so {@code
     * 123,@all[type=cow,distance=..10]} is two entries.
     *
     * @param t the token
     * @return the entries with their ranges in the token's coordinates
     */
    static List<Token> listEntries(Token t) {
        String text = t.text();
        List<Token> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : ',';
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && (depth == 0 || i == text.length())) {
                out.add(new Token(text.substring(from, i), t.start() + from, t.start() + i));
                from = i + 1;
            }
        }
        return out;
    }

    /** Entity ids a selector selects, nearest first. */
    private static List<Long> resolve(Selector s, Token t, String name, ArgContext ctx, BotCommands bot) {
        EntityQuery q = ctx.entities();
        return switch (s) {
            case Selector.Me _ -> one(q.player(), name + ": you are not in a world", t);
            case Selector.Self _ -> one(q.bot(bot.name()), name + ": " + bot.name() + " is not loaded nearby", t);
            case Selector.Looked _ -> one(q.looked(), name + ": the crosshair is not on an entity", t);
            case Selector.Nearest n -> ids(q.find(n.filter(), origin(n.filter(), t, name, ctx, bot), 1));
            case Selector.All a -> {
                Integer limit = a.filter().limit();
                yield ids(q.find(a.filter(), origin(a.filter(), t, name, ctx, bot), limit == null ? Integer.MAX_VALUE : limit));
            }
        };
    }

    private static List<Long> one(@Nullable Integer id, String missing, Token t) {
        if (id == null) {
            throw new ArgException(missing, t.start(), t.end());
        }
        return List.of((long) id);
    }

    private static List<Long> ids(List<Integer> ids) {
        List<Long> out = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            out.add((long) id);
        }
        return List.copyOf(out);
    }

    private static Vec3d origin(Selector.Filter f, Token t, String name, ArgContext ctx, BotCommands bot) {
        if (!f.fromBot()) {
            return ctx.originExact();
        }
        Vec3d p = botPosition(ctx, bot);
        if (p == null) {
            throw new ArgException(name + ": the position of " + bot.name() + " is unknown", t.start(), t.end());
        }
        return p;
    }

    /**
     * Returns where a bot is: its loaded entity, else its last {@code world.report} position.
     *
     * @param ctx the game context
     * @param bot the bot
     * @return the position, or null when neither is known
     */
    public static @Nullable Vec3d botPosition(ArgContext ctx, BotCommands bot) {
        Integer id = ctx.entities().bot(bot.name());
        Vec3d p = id == null ? null : ctx.entities().position(id);
        return p != null ? p : bot.position();
    }

    /** The exact position a single position token names: {@code here} or a selector. */
    private static Vec3d place(Token t, ArgContext ctx, BotCommands bot, String name, boolean block) {
        String text = t.text();
        if (text.equalsIgnoreCase(HERE)) {
            return crosshair(t, ctx, name, block);
        }
        Selector s = selector(t);
        EntityQuery q = ctx.entities();
        return switch (s) {
            case Selector.Me _ -> ctx.originExact();
            case Selector.Self _ -> {
                Vec3d p = botPosition(ctx, bot);
                if (p == null) {
                    throw new ArgException(name + ": the position of " + bot.name() + " is unknown", t.start(), t.end());
                }
                yield p;
            }
            case Selector.Looked _ -> {
                Integer id = q.looked();
                Vec3d p = id == null ? null : q.position(id);
                yield p != null ? p : crosshair(t, ctx, name, block);
            }
            case Selector.Nearest n -> {
                List<Integer> found = q.find(n.filter(), origin(n.filter(), t, name, ctx, bot), 1);
                Vec3d p = found.isEmpty() ? null : q.position(found.getFirst());
                if (p == null) {
                    throw new ArgException(name + ": no entity matches " + text, t.start(), t.end());
                }
                yield p;
            }
            case Selector.All _ -> throw new ArgException(name + " takes one position; use @nearest instead of @all",
                    t.start(), t.end());
        };
    }

    private static Vec3d crosshair(Token t, ArgContext ctx, String name, boolean block) {
        if (block) {
            Vec3i b = ctx.crosshairBlock();
            if (b == null) {
                throw new ArgException(name + ": the crosshair is not on a block", t.start(), t.end());
            }
            return new Vec3d(b.x(), b.y(), b.z());
        }
        Vec3d p = ctx.crosshairPos();
        if (p == null) {
            throw new ArgException(name + ": the crosshair is not on anything", t.start(), t.end());
        }
        return p;
    }

    private static Map<String, Object> blockPos(List<Token> parts, ArgContext ctx, BotCommands bot, String name) {
        if (parts.size() == 1) {
            Vec3d p = place(parts.getFirst(), ctx, bot, name, true);
            return xyz((long) Math.floor(p.x()), (long) Math.floor(p.y()), (long) Math.floor(p.z()));
        }
        Vec3i origin = ctx.originBlock();
        return xyz(
                (long) Math.floor(coordinate(parts.get(0), origin.x(), name)),
                (long) Math.floor(coordinate(parts.get(1), origin.y(), name)),
                (long) Math.floor(coordinate(parts.get(2), origin.z(), name)));
    }

    private static Map<String, Object> vec3(List<Token> parts, ArgContext ctx, BotCommands bot, String name) {
        if (parts.size() == 1) {
            Vec3d p = place(parts.getFirst(), ctx, bot, name, false);
            return xyz(p.x(), p.y(), p.z());
        }
        Vec3d origin = ctx.originExact();
        return xyz(
                coordinate(parts.get(0), origin.x(), name),
                coordinate(parts.get(1), origin.y(), name),
                coordinate(parts.get(2), origin.z(), name));
    }

    private static Map<String, Object> xyz(Object x, Object y, Object z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private static double coordinate(Token t, double origin, String name) {
        String text = t.text().strip();
        if (text.startsWith("~")) {
            String rest = text.substring(1);
            return rest.isEmpty() ? origin : origin + number(rest, name, t.start(), t.end());
        }
        return number(text, name, t.start(), t.end());
    }

    private static double number(String text, String name, int start, int end) {
        try {
            double value = Double.parseDouble(text.strip());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ArgException(name + " must be a number, not '" + text + "'", start, end);
        }
    }

    private static boolean bool(String text, String name, int start, int end) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new ArgException(name + " must be true or false, not '" + text + "'", start, end);
        };
    }

    private static String choice(String text, @Nullable List<String> choices, String name, int start, int end) {
        if (choices == null || choices.isEmpty()) {
            return text;
        }
        for (String c : choices) {
            if (c.equalsIgnoreCase(text.strip())) {
                return c;
            }
        }
        throw new ArgException(name + " must be one of " + String.join(", ", choices), start, end);
    }

    private static void range(double value, CommandArg arg, String name, int start, int end) {
        Double min = arg.min();
        Double max = arg.max();
        if (min != null && value < min) {
            throw new ArgException(name + " must be at least " + plain(min), start, end);
        }
        if (max != null && value > max) {
            throw new ArgException(name + " must be at most " + plain(max), start, end);
        }
    }

    /**
     * Returns whether the argument's type is a position.
     *
     * @param arg the declaration
     * @return whether it is {@code block_pos} or {@code vec3}
     */
    public static boolean isPosition(CommandArg arg) {
        return arg.type() == ArgType.BLOCK_POS || arg.type() == ArgType.VEC3;
    }
}
