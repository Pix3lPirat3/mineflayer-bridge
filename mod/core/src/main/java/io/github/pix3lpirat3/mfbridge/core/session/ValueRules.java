package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingDef;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Validates and normalizes player-supplied values against declared constraints (command args, form
 * fields, settings), producing values {@code WireWriter.writeAny} can encode: integers become
 * {@code Long}, floats {@code Double}, positions maps with {@code x}, {@code y}, {@code z}. Every
 * failure is an {@link IllegalArgumentException} with a message fit to show the player.
 */
final class ValueRules {
    private static final double LONG_LIMIT = 9.223372036854775807E18;

    private ValueRules() {}

    static long integral(Object v, String what) {
        switch (v) {
            case Long l -> {
                return l;
            }
            case Integer i -> {
                return i;
            }
            case Short s -> {
                return s;
            }
            case Byte b -> {
                return b;
            }
            case BigInteger big when big.bitLength() <= 63 -> {
                return big.longValue();
            }
            case Double d when d == Math.rint(d) && Math.abs(d) < LONG_LIMIT -> {
                return d.longValue();
            }
            case Float f when f == Math.rint(f) && Math.abs(f) < LONG_LIMIT -> {
                return f.longValue();
            }
            default -> throw new IllegalArgumentException(what + " must be a whole number");
        }
    }

    static double number(Object v, String what) {
        if (v instanceof Number n && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(what + " must be a number");
    }

    static void range(double v, @Nullable Double min, @Nullable Double max, String what) {
        if (min != null && v < min) {
            throw new IllegalArgumentException(what + " must be at least " + plain(min));
        }
        if (max != null && v > max) {
            throw new IllegalArgumentException(what + " must be at most " + plain(max));
        }
    }

    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < LONG_LIMIT ? Long.toString((long) d) : Double.toString(d);
    }

    static String string(Object v, String what) {
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(what + " must be text");
    }

    static boolean bool(Object v, String what) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException(what + " must be true or false");
    }

    static String option(Object v, @Nullable List<String> options, String what) {
        String s = string(v, what);
        if (options != null && !options.contains(s)) {
            throw new IllegalArgumentException(what + " must be one of " + String.join(", ", options));
        }
        return s;
    }

    /** A block position: {@code Vec3i}, a list of three whole numbers, or a map with x, y, z. */
    static Map<String, Object> blockPos(Object v, String what) {
        if (v instanceof Vec3i p) {
            return xyz(p.x(), p.y(), p.z());
        }
        Object[] c = components(v, what);
        return xyz(integral(c[0], what + ".x"), integral(c[1], what + ".y"), integral(c[2], what + ".z"));
    }

    /** An exact position: {@code Vec3d}, a list of three numbers, or a map with x, y, z. */
    static Map<String, Object> vec3(Object v, String what) {
        if (v instanceof Vec3d p) {
            return xyz(p.x(), p.y(), p.z());
        }
        Object[] c = components(v, what);
        return xyz(number(c[0], what + ".x"), number(c[1], what + ".y"), number(c[2], what + ".z"));
    }

    private static Object[] components(Object v, String what) {
        if (v instanceof List<?> l && l.size() == 3 && l.get(0) != null && l.get(1) != null && l.get(2) != null) {
            return new Object[] {l.get(0), l.get(1), l.get(2)};
        }
        if (v instanceof Map<?, ?> m && m.get("x") != null && m.get("y") != null && m.get("z") != null) {
            return new Object[] {m.get("x"), m.get("y"), m.get("z")};
        }
        throw new IllegalArgumentException(what + " must be a position with x, y and z");
    }

    private static Map<String, Object> xyz(Object x, Object y, Object z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    /**
     * Validates command arguments. Unknown names are rejected; a missing
     * argument takes its default, and fails when it is neither optional nor defaulted.
     *
     * @return normalized arguments in declaration order
     */
    static Map<String, Object> commandArgs(List<CommandArg> declared, Map<String, Object> args) {
        for (String name : args.keySet()) {
            if (find(declared, name) == null) {
                throw new IllegalArgumentException("unknown argument '" + name + "'");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (CommandArg a : declared) {
            Object v = args.get(a.name());
            if (v == null) {
                Object def = a.defaultValue();
                if (def != null) {
                    out.put(a.name(), def);
                } else if (!a.optional()) {
                    throw new IllegalArgumentException("missing argument '" + a.name() + "'");
                }
                continue;
            }
            out.put(a.name(), commandArg(a, v));
        }
        return out;
    }

    private static @Nullable CommandArg find(List<CommandArg> declared, String name) {
        for (CommandArg a : declared) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        return null;
    }

    private static Object commandArg(CommandArg a, Object v) {
        String what = "'" + a.name() + "'";
        ArgType type = a.type();
        return switch (type) {
            case STRING, GREEDY, PLAYER, BOT, ZONE, POINT -> {
                String s = string(v, what);
                range(s.length(), a.min(), a.max(), what + " length");
                yield s;
            }
            case BLOCK_ID, ITEM_ID -> {
                String s = string(v, what);
                if (s.isEmpty() || s.indexOf(' ') >= 0) {
                    throw new IllegalArgumentException(what + " must be a registry id such as minecraft:stone");
                }
                yield s;
            }
            case ENTITY -> v instanceof String s ? s : integral(v, what);
            case ENTITY_LIST -> {
                if (!(v instanceof List<?> list)) {
                    throw new IllegalArgumentException(what + " must be a list of entity ids");
                }
                List<Object> ids = new ArrayList<>(list.size());
                for (Object o : list) {
                    if (o == null) {
                        throw new IllegalArgumentException(what + " must be a list of entity ids");
                    }
                    ids.add(integral(o, what + " entry"));
                }
                range(ids.size(), a.min(), a.max(), what + " size");
                yield List.copyOf(ids);
            }
            case INT -> {
                long l = integral(v, what);
                range(l, a.min(), a.max(), what);
                yield l;
            }
            case FLOAT -> {
                double d = number(v, what);
                range(d, a.min(), a.max(), what);
                yield d;
            }
            case BOOL -> bool(v, what);
            case CHOICE -> option(v, a.choices(), what);
            case BLOCK_POS -> blockPos(v, what);
            case VEC3 -> vec3(v, what);
        };
    }

    /**
     * Validates submitted form values. Unknown ids are rejected, missing fields
     * take their default, and required fields must end up with a non-empty value.
     */
    static Map<String, @Nullable Object> formValues(List<FormField> fields, Map<String, Object> values) {
        for (String id : values.keySet()) {
            if (field(fields, id) == null) {
                throw new IllegalArgumentException("unknown field '" + id + "'");
            }
        }
        Map<String, @Nullable Object> out = new LinkedHashMap<>();
        for (FormField f : fields) {
            Object v = values.containsKey(f.id()) ? values.get(f.id()) : f.defaultValue();
            String what = "'" + (f.label() != null ? f.label() : f.id()) + "'";
            if (v == null || (v instanceof String s && s.isEmpty())) {
                if (f.required()) {
                    throw new IllegalArgumentException(what + " is required");
                }
                if (v != null) {
                    out.put(f.id(), v);
                }
                continue;
            }
            out.put(f.id(), formValue(f, v, what));
        }
        return out;
    }

    private static @Nullable FormField field(List<FormField> fields, String id) {
        for (FormField f : fields) {
            if (f.id().equals(id)) {
                return f;
            }
        }
        return null;
    }

    private static Object formValue(FormField f, Object v, String what) {
        return switch (f.type()) {
            case TEXT, ITEM, ZONE -> string(v, what);
            case NUMBER, SLIDER -> {
                double d = number(v, what);
                range(d, f.min(), f.max(), what);
                yield d == Math.rint(d) && Math.abs(d) < LONG_LIMIT && !(v instanceof Double || v instanceof Float)
                        ? (Object) (long) d
                        : (Object) d;
            }
            case TOGGLE -> bool(v, what);
            case SELECT -> option(v, f.options(), what);
            case MULTI_SELECT -> {
                if (!(v instanceof List<?> list)) {
                    throw new IllegalArgumentException(what + " must be a list of options");
                }
                List<String> picked = new ArrayList<>(list.size());
                for (Object o : list) {
                    picked.add(option(o == null ? "" : o, f.options(), what));
                }
                yield List.copyOf(picked);
            }
            case BLOCK_POS -> blockPos(v, what);
        };
    }

    /**
     * Validates one setting value. Types are {@code bool}, {@code int}, {@code
     * float}, {@code string}, {@code color} (a packed {@code 0xAARRGGBB} number or {@code #rrggbb} /
     * {@code #rrggbbaa}) and {@code choice}; unknown types accept text, numbers and booleans.
     */
    static Object setting(SettingDef def, Object v) {
        String what = "'" + (def.label() != null ? def.label() : def.key()) + "'";
        return switch (def.type().toLowerCase(Locale.ROOT)) {
            case "bool", "boolean" -> bool(v, what);
            case "int", "integer" -> {
                long l = integral(v, what);
                range(l, def.min(), def.max(), what);
                yield l;
            }
            case "float", "number", "double" -> {
                double d = number(v, what);
                range(d, def.min(), def.max(), what);
                yield d;
            }
            case "string", "text" -> {
                String s = string(v, what);
                range(s.length(), def.min(), def.max(), what + " length");
                yield s;
            }
            case "color", "colour" -> color(v, what);
            case "choice", "enum" -> option(v, def.options(), what);
            default -> {
                if (v instanceof String || v instanceof Boolean) {
                    yield v;
                }
                if (v instanceof Number n) {
                    yield n instanceof Double || n instanceof Float ? (Object) n.doubleValue() : (Object) integral(n, what);
                }
                throw new IllegalArgumentException(what + " must be text, a number or a boolean");
            }
        };
    }

    // Packed u32 0xAARRGGBB; strings are #rrggbb (opaque) or #rrggbbaa.
    private static long color(Object v, String what) {
        if (v instanceof String s && s.startsWith("#") && (s.length() == 7 || s.length() == 9)) {
            try {
                long rgb = Long.parseLong(s.substring(1, 7), 16);
                long alpha = s.length() == 9 ? Long.parseLong(s.substring(7, 9), 16) : 0xFF;
                return (alpha << 24) | rgb;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + " must be a colour such as #3b82f6", e);
            }
        }
        if (v instanceof String) {
            throw new IllegalArgumentException(what + " must be a colour such as #3b82f6");
        }
        long l = integral(v, what);
        if (l < 0 || l > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(what + " must be a colour such as #3b82f6");
        }
        return l;
    }
}
