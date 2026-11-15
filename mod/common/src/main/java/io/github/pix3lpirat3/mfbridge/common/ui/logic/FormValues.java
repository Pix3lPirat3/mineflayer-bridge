package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Raw form input and its client-side validation: fields are validated
 * against their declared constraints before the result is sent.
 *
 * <p>Raw input per {@link FieldSpec.Kind}: a {@code String} for text-like kinds, numbers and select,
 * a {@code Double} for sliders, a {@code Boolean} for toggles, a {@code List<String>} of picked
 * options for multi-select, and a {@code List<String>} of three coordinates for positions.
 */
public final class FormValues {

    private FormValues() {
    }

    /**
     * The validated values, or the first problem.
     *
     * @param values field id to value, in the types {@code InputControls.resolveForm} takes; blank
     *     optional fields are absent. Immutable.
     * @param error a message for the player, or null
     * @param fieldId the id of the field with the problem, or null
     */
    public record Result(Map<String, Object> values, @Nullable String error, @Nullable String fieldId) {

        /** Copies the values. */
        public Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /**
         * Returns whether validation passed.
         *
         * @return true when {@link #error} is null
         */
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Returns the raw input a field starts with, from its declared default.
     *
     * @param field the field
     * @return the raw input in the representation described on this class
     */
    public static Object initial(final FieldSpec field) {
        final Object def = field.defaultValue();
        return switch (field.kind()) {
            case TOGGLE -> def instanceof Boolean b ? b : Boolean.FALSE;
            case SLIDER -> {
                final double min = field.min() == null ? 0d : field.min();
                yield def instanceof Number n ? Math.clamp(n.doubleValue(), min, field.max() == null ? min : field.max()) : min;
            }
            case MULTI_SELECT -> {
                final List<String> picked = new ArrayList<>();
                if (def instanceof List<?> list) {
                    for (final Object o : list) {
                        if (o != null && field.options().contains(o.toString())) {
                            picked.add(o.toString());
                        }
                    }
                }
                yield picked;
            }
            case SELECT -> def != null ? def.toString() : field.options().isEmpty() ? "" : field.options().getFirst();
            case BLOCK_POS, VEC3 -> coordinates(def);
            case NUMBER, INTEGER -> def instanceof Number n ? number(n.doubleValue()) : def == null ? "" : def.toString();
            case TEXT, ITEM, BLOCK, ZONE -> def == null ? "" : def.toString();
        };
    }

    /**
     * Validates raw input for a bot form.
     *
     * @param t resolves the messages
     * @param fields the fields in order
     * @param raw field id to raw input
     * @return the values, or the first problem
     */
    public static Result validate(final Text t, final List<FieldSpec> fields, final Map<String, Object> raw) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final FieldSpec field : fields) {
            final Object input = raw.get(field.id());
            final String name = field.label();
            try {
                final @Nullable Object value = value(t, field, input, name);
                if (value == null) {
                    if (field.required()) {
                        return new Result(Map.of(), t.get("mineflayer_bridge.form.required", name), field.id());
                    }
                    continue;
                }
                out.put(field.id(), value);
            } catch (final IllegalArgumentException e) {
                return new Result(Map.of(), e.getMessage(), field.id());
            }
        }
        return new Result(out, null, null);
    }

    private static @Nullable Object value(final Text t, final FieldSpec field, final @Nullable Object input, final String name) {
        return switch (field.kind()) {
            case TEXT, ITEM, BLOCK, ZONE -> {
                final String s = input == null ? "" : input.toString().strip();
                yield s.isEmpty() ? null : s;
            }
            case NUMBER, INTEGER -> {
                final String s = input == null ? "" : input.toString().strip();
                if (s.isEmpty()) {
                    yield null;
                }
                final double d;
                try {
                    d = Double.parseDouble(s);
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(t.get("mineflayer_bridge.form.number", name), e);
                }
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException(t.get("mineflayer_bridge.form.number", name));
                }
                if (field.kind() == FieldSpec.Kind.INTEGER && d != Math.rint(d)) {
                    throw new IllegalArgumentException(t.get("mineflayer_bridge.form.whole_number", name));
                }
                range(t, d, field, name);
                yield d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
            }
            case SLIDER -> {
                final double d = input instanceof Number n ? n.doubleValue() : 0d;
                range(t, d, field, name);
                yield d;
            }
            case TOGGLE -> input instanceof Boolean b ? b : Boolean.FALSE;
            case SELECT -> {
                final String s = input == null ? "" : input.toString();
                if (s.isEmpty()) {
                    yield null;
                }
                if (!field.options().isEmpty() && !field.options().contains(s)) {
                    throw new IllegalArgumentException(t.get("mineflayer_bridge.form.one_of", name, String.join(", ", field.options())));
                }
                yield s;
            }
            case MULTI_SELECT -> {
                final List<String> picked = new ArrayList<>();
                if (input instanceof List<?> list) {
                    for (final String option : field.options()) {
                        if (list.contains(option)) {
                            picked.add(option);
                        }
                    }
                }
                yield picked.isEmpty() && field.required() ? null : List.copyOf(picked);
            }
            case BLOCK_POS -> {
                final List<String> parts = strings(input);
                if (parts.stream().allMatch(String::isBlank)) {
                    yield null;
                }
                final int[] c = new int[3];
                for (int i = 0; i < 3; i++) {
                    try {
                        c[i] = Integer.parseInt(parts.get(i).strip());
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException(t.get("mineflayer_bridge.form.block_pos", name), e);
                    }
                }
                yield new Vec3i(c[0], c[1], c[2]);
            }
            case VEC3 -> {
                final List<String> parts = strings(input);
                if (parts.stream().allMatch(String::isBlank)) {
                    yield null;
                }
                final double[] c = new double[3];
                for (int i = 0; i < 3; i++) {
                    try {
                        c[i] = Double.parseDouble(parts.get(i).strip());
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException(t.get("mineflayer_bridge.form.vec3", name), e);
                    }
                }
                yield new Vec3d(c[0], c[1], c[2]);
            }
        };
    }

    private static void range(final Text t, final double d, final FieldSpec field, final String name) {
        if (field.min() != null && d < field.min()) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.form.at_least", name, CommandArgs.plain(field.min())));
        }
        if (field.max() != null && d > field.max()) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.form.at_most", name, CommandArgs.plain(field.max())));
        }
    }

    private static List<String> strings(final @Nullable Object input) {
        final List<String> out = new ArrayList<>(3);
        if (input instanceof List<?> list) {
            for (final Object o : list) {
                out.add(o == null ? "" : o.toString());
            }
        }
        while (out.size() < 3) {
            out.add("");
        }
        return out;
    }

    /**
     * Formats a number for an input box: whole numbers without a fraction, others rounded to three
     * decimals with trailing zeros removed, like the debug screen's coordinates.
     *
     * @param v the number
     * @return the text
     */
    public static String number(final double v) {
        if (!Double.isFinite(v) || v == Math.rint(v) && Math.abs(v) < 1e15) {
            return CommandArgs.plain(v);
        }
        return java.math.BigDecimal.valueOf(Math.round(v * 1000d) / 1000d).stripTrailingZeros().toPlainString();
    }

    private static List<String> coordinates(final @Nullable Object def) {
        final List<String> out = new ArrayList<>(3);
        if (def instanceof Vec3i v) {
            out.add(Integer.toString(v.x()));
            out.add(Integer.toString(v.y()));
            out.add(Integer.toString(v.z()));
        } else if (def instanceof Map<?, ?> m && m.get("x") instanceof Number x && m.get("y") instanceof Number y
                && m.get("z") instanceof Number z) {
            out.add(number(x.doubleValue()));
            out.add(number(y.doubleValue()));
            out.add(number(z.doubleValue()));
        } else if (def instanceof List<?> l && l.size() == 3) {
            for (final Object o : l) {
                out.add(o instanceof Number n ? number(n.doubleValue()) : String.valueOf(o));
            }
        } else {
            out.add("");
            out.add("");
            out.add("");
        }
        return out;
    }
}
