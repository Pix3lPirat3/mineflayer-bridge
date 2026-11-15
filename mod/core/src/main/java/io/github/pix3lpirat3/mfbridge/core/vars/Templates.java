package io.github.pix3lpirat3.mfbridge.core.vars;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Template text: {@code {name}} or {@code {name:fmt}} with {@code
 * fmt} one of {@code int}, {@code 1f}, {@code 2f} and {@code pct}; doubled braces are literal braces.
 * Names are 1 to {@value #MAX_NAME} letters, digits, underscores, hyphens and dots. A name no scope
 * defines, a malformed placeholder or an unknown format stays exactly as written. Pure functions.
 */
@AnyThread
public final class Templates {
    private Templates() {}

    /** Most characters in a variable name. */
    public static final int MAX_NAME = 64;

    /** Returns whether {@code text} may change when resolved: it has a brace. */
    public static boolean templated(String text) {
        return text.indexOf('{') >= 0 || text.indexOf('}') >= 0;
    }

    /**
     * Resolves every placeholder; doubled braces are literal braces.
     *
     * @param lookup returns a name's value, null for a present nil, or {@link Variables#MISSING}
     * @return the text with placeholders replaced; {@code text} itself when it has no brace
     */
    public static String resolve(String text, Function<String, @Nullable Object> lookup) {
        if (!templated(text)) {
            return text;
        }
        int n = text.length();
        StringBuilder out = new StringBuilder(n + 16);
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            boolean doubled = i + 1 < n && text.charAt(i + 1) == c;
            if ((c == '{' || c == '}') && doubled) {
                out.append(c);
                i += 2;
                continue;
            }
            int close = c == '{' ? text.indexOf('}', i + 1) : -1;
            if (close < 0 || text.indexOf('{', i + 1) >= 0 && text.indexOf('{', i + 1) < close) {
                out.append(c);
                i++;
                continue;
            }
            String inner = text.substring(i + 1, close);
            int colon = inner.indexOf(':');
            String name = colon < 0 ? inner : inner.substring(0, colon);
            String fmt = colon < 0 ? null : inner.substring(colon + 1);
            Object value = validName(name) && (fmt == null || validFormat(fmt)) ? lookup.apply(name) : Variables.MISSING;
            if (value == Variables.MISSING) {
                out.append(text, i, close + 1);
            } else {
                out.append(format(value, fmt));
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * Formats one value.
     *
     * @param value a variable value: number, string, boolean, nil or a structure
     * @param fmt {@code int} (rounded), {@code 1f} and {@code 2f} (fixed decimals), {@code pct} (a
     *     0..1 fraction as a whole percentage), or null for the plain form; formats apply to numbers
     *     only, whole doubles print without a fraction
     */
    public static String format(@Nullable Object value, @Nullable String fmt) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof Number n)) {
            return String.valueOf(value);
        }
        double d = n.doubleValue();
        if (fmt == null) {
            if (n instanceof Double || n instanceof Float) {
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : String.valueOf(d);
            }
            return n.toString();
        }
        return switch (fmt) {
            case "int" -> Long.toString(Math.round(d));
            case "1f" -> String.format(Locale.ROOT, "%.1f", d);
            case "2f" -> String.format(Locale.ROOT, "%.2f", d);
            case "pct" -> Math.round(d * 100d) + "%";
            default -> String.valueOf(value);
        };
    }

    private static boolean validFormat(String fmt) {
        return fmt.equals("int") || fmt.equals("1f") || fmt.equals("2f") || fmt.equals("pct");
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }
}
