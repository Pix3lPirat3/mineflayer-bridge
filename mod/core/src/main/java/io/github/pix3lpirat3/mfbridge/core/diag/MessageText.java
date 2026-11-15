package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Renders decoded protocol values as readable JSON-like text for the inspector: records become
 * objects keyed by component name (null components are left out), lists become arrays and byte
 * arrays a length with a hex prefix. Output is cut at a length limit, so a large volume never
 * builds a megabyte string.
 */
@AnyThread
public final class MessageText {
    /** Default output limit in characters. */
    public static final int DEFAULT_LIMIT = 4_000;
    private static final int MAX_DEPTH = 12;
    private static final int HEX_PREFIX = 16;
    private static final int MAX_ITEMS = 64;

    private MessageText() {}

    /** Renders a value with {@link #DEFAULT_LIMIT}. */
    public static String render(@Nullable Object value) {
        return render(value, DEFAULT_LIMIT);
    }

    /**
     * Renders a value.
     *
     * @param limit most characters returned; longer output ends with an ellipsis
     */
    public static String render(@Nullable Object value, int limit) {
        StringBuilder out = new StringBuilder(64);
        append(out, value, 0, limit);
        if (out.length() > limit) {
            out.setLength(Math.max(0, limit - 3));
            out.append("...");
        }
        return out.toString();
    }

    private static void append(StringBuilder out, @Nullable Object value, int depth, int limit) {
        if (out.length() > limit) {
            return;
        }
        if (depth > MAX_DEPTH) {
            out.append("...");
            return;
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(out, s);
            case Character c -> quote(out, c.toString());
            case Float f -> out.append(number(f.doubleValue()));
            case Double d -> out.append(number(d));
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            case Enum<?> e -> quote(out, e.name().toLowerCase(Locale.ROOT));
            case byte[] bytes -> bytes(out, bytes);
            case float[] floats -> {
                out.append('[');
                int n = Math.min(floats.length, MAX_ITEMS);
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(number(floats[i]));
                }
                more(out, floats.length - n);
                out.append(']');
            }
            case int[] ints -> {
                out.append('[');
                int n = Math.min(ints.length, MAX_ITEMS);
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(ints[i]);
                }
                more(out, ints.length - n);
                out.append(']');
            }
            case Optional<?> o -> append(out, o.orElse(null), depth, limit);
            case Collection<?> c -> {
                out.append('[');
                int i = 0;
                for (Object item : c) {
                    if (i == MAX_ITEMS) {
                        more(out, c.size() - i);
                        break;
                    }
                    if (i++ > 0) {
                        out.append(", ");
                    }
                    append(out, item, depth + 1, limit);
                    if (out.length() > limit) {
                        break;
                    }
                }
                out.append(']');
            }
            case Map<?, ?> m -> {
                out.append('{');
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ > 0) {
                        out.append(", ");
                    }
                    out.append(e.getKey()).append(": ");
                    append(out, e.getValue(), depth + 1, limit);
                    if (out.length() > limit) {
                        break;
                    }
                }
                out.append('}');
            }
            case Record r -> record(out, r, depth, limit);
            default -> quote(out, value.toString());
        }
    }

    private static void record(StringBuilder out, Record r, int depth, int limit) {
        out.append('{');
        boolean first = true;
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            Object v;
            try {
                c.getAccessor().setAccessible(true);
                v = c.getAccessor().invoke(r);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                v = "<" + e.getClass().getSimpleName() + ">";
            }
            if (v == null) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(c.getName()).append(": ");
            append(out, v, depth + 1, limit);
            if (out.length() > limit) {
                break;
            }
        }
        out.append('}');
    }

    private static void bytes(StringBuilder out, byte[] bytes) {
        int n = Math.min(bytes.length, HEX_PREFIX);
        out.append('<').append(bytes.length).append(" bytes");
        if (n > 0) {
            out.append(' ').append(HexFormat.of().formatHex(bytes, 0, n));
            if (n < bytes.length) {
                out.append("...");
            }
        }
        out.append('>');
    }

    private static void more(StringBuilder out, int remaining) {
        if (remaining > 0) {
            out.append(", ... ").append(remaining).append(" more");
        }
    }

    private static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
