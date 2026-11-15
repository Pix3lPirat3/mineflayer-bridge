package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Field editing of decoded object data (Shift+click edits). Generated kind
 * records list their components in schema field order, so a component's position is its field index
 * in object data and in patches. Free of Minecraft types.
 */
public final class FieldEditors {

    /** The editor a field opens with; wire names are the lower-case constant names. */
    public enum Editor {
        /** An inline text editor. */
        TEXT,
        /** The anvil rename surface, for text. */
        ANVIL,
        /** The move gizmo, for positions. */
        GIZMO,
        /** The colour picker, for {@code 0xAARRGGBB} colours. */
        COLOR,
        /** Cycle to the next value on click, for enums and booleans. */
        CYCLE,
        /** A number field that also takes scroll steps. */
        NUMBER,
        /** Flip on click, for booleans. */
        TOGGLE;

        /**
         * Returns the editor with a wire name.
         *
         * @param name {@code text}, {@code anvil}, {@code gizmo}, {@code color}, {@code cycle},
         *     {@code number} or {@code toggle}
         * @return the editor, or null for other names
         */
        public static @Nullable Editor named(final @Nullable String name) {
            if (name == null) {
                return null;
            }
            for (final Editor e : values()) {
                if (e.name().toLowerCase(Locale.ROOT).equals(name)) {
                    return e;
                }
            }
            return null;
        }
    }

    /**
     * One editable field.
     *
     * @param name schema field name
     * @param index field index in the object data
     * @param label what the player sees
     * @param editor the editor to open
     * @param type the component type of the generated record
     * @param value the current value, or null
     */
    public record Field(String name, int index, String label, Editor editor, Class<?> type, @Nullable Object value) {
    }

    /** Fields owners answer {@code unsupported} for, so no editor is offered. */
    private static final Set<String> UNSUPPORTED = Set.of("group", "transform");

    private FieldEditors() {
    }

    /**
     * Picks the editor of a field: the requested one when it suits the type, else the type's default.
     *
     * @param name field name, which marks {@code int} colours ({@code color}, {@code background},
     *     {@code glow}, {@code outline}, or a name ending in {@code Color})
     * @param type the component type
     * @param requested the declared {@code editor}, or null
     * @return the editor, or null when the type cannot be edited
     */
    public static @Nullable Editor choose(final String name, final Class<?> type, final @Nullable String requested) {
        final Editor fallback = defaultEditor(name, type);
        final Editor wanted = Editor.named(requested);
        if (fallback == null || wanted == null) {
            return fallback;
        }
        final boolean fits = switch (wanted) {
            case TEXT, ANVIL -> type == String.class;
            case GIZMO -> type == Vec3d.class;
            case COLOR -> type == int.class || type == Integer.class;
            case CYCLE -> type.isEnum() || bool(type);
            case TOGGLE -> bool(type);
            case NUMBER -> number(type);
        };
        return fits ? wanted : fallback;
    }

    private static @Nullable Editor defaultEditor(final String name, final Class<?> type) {
        if (type == String.class) {
            return Editor.TEXT;
        }
        if (type == Vec3d.class) {
            return Editor.GIZMO;
        }
        if (bool(type)) {
            return Editor.TOGGLE;
        }
        if (type.isEnum()) {
            return Editor.CYCLE;
        }
        if ((type == int.class || type == Integer.class) && colourName(name)) {
            return Editor.COLOR;
        }
        return number(type) ? Editor.NUMBER : null;
    }

    private static boolean colourName(final String name) {
        return name.equals("color") || name.equals("colour") || name.equals("background") || name.equals("glow")
                || name.equals("outline") || name.endsWith("Color");
    }

    private static boolean bool(final Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static boolean number(final Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class || type == float.class
                || type == Float.class || type == double.class || type == Double.class;
    }

    /** Returns whether a number field takes whole numbers only. */
    public static boolean whole(final Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class;
    }

    /**
     * Resolves the declared editable fields against the data's record components.
     *
     * @param data a generated kind record
     * @param editable the declared fields; unknown names, {@code group}, {@code transform} and types
     *     without an editor are skipped
     * @return the fields in declared order
     */
    public static List<Field> fields(final Object data, final List<EditableField> editable) {
        final List<Field> out = new ArrayList<>(editable.size());
        if (!(data instanceof Record)) {
            return out;
        }
        final RecordComponent[] components = data.getClass().getRecordComponents();
        for (final EditableField f : editable) {
            for (int i = 0; i < components.length; i++) {
                final RecordComponent c = components[i];
                if (!c.getName().equals(f.field()) || UNSUPPORTED.contains(c.getName())) {
                    continue;
                }
                final Editor editor = choose(c.getName(), c.getType(), f.editor());
                if (editor != null) {
                    out.add(new Field(c.getName(), i, f.label() != null ? f.label() : c.getName(), editor, c.getType(),
                            read(c, data)));
                }
            }
        }
        return out;
    }

    private static @Nullable Object read(final RecordComponent c, final Object data) {
        try {
            return c.getAccessor().invoke(data);
        } catch (final IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + c.getName(), e);
        }
    }

    /**
     * Returns a copy of a record with some components replaced.
     *
     * @param t resolves the message
     * @param data a generated kind record
     * @param changes component name to new value, already of the component's type (see {@link #convert})
     * @return the new record
     * @throws IllegalArgumentException when a value does not fit its component
     */
    public static Object with(final Text t, final Object data, final Map<String, @Nullable Object> changes) {
        final RecordComponent[] components = data.getClass().getRecordComponents();
        final Object[] args = new Object[components.length];
        final Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            final RecordComponent c = components[i];
            types[i] = c.getType();
            args[i] = changes.containsKey(c.getName()) ? changes.get(c.getName()) : read(c, data);
        }
        try {
            final Constructor<?> ctor = data.getClass().getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (final ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.edit.cannot_apply", String.valueOf(e.getMessage())), e);
        }
    }

    /**
     * Converts an editor's result to a field's component type.
     *
     * @param t resolves the messages
     * @param field the field
     * @param value a {@code String}, {@code Boolean}, {@code Number}, enum constant name, colour text
     *     or {@link Vec3d}
     * @return the value as the component type
     * @throws IllegalArgumentException when the value does not convert
     */
    public static Object convert(final Text t, final Field field, final Object value) {
        final Class<?> type = field.type();
        if (field.editor() == Editor.COLOR && value instanceof String s) {
            return parseColor(t, s);
        }
        if (type.isEnum()) {
            for (final Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(value.toString()) || constant.equals(value)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(t.get("mineflayer_bridge.edit.no_value", field.label(), value));
        }
        if (number(type)) {
            final double d;
            try {
                d = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().strip());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(t.get("mineflayer_bridge.form.number", field.label()), e);
            }
            if (whole(type) && d != Math.rint(d)) {
                throw new IllegalArgumentException(t.get("mineflayer_bridge.form.whole_number", field.label()));
            }
            if (type == int.class || type == Integer.class) {
                return (int) d;
            }
            if (type == long.class || type == Long.class) {
                return (long) d;
            }
            return type == float.class || type == Float.class ? (Object) (float) d : (Object) d;
        }
        if (bool(type)) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
        }
        if (type == String.class) {
            return value.toString();
        }
        if (type.isInstance(value)) {
            return value;
        }
        throw new IllegalArgumentException(t.get("mineflayer_bridge.edit.cannot_take", field.label(), value));
    }

    /**
     * Returns the next value of a cycled or toggled field.
     *
     * @param field an enum or boolean field
     * @return the following enum constant (wrapping) or the negated boolean
     */
    public static Object next(final Field field) {
        final Object v = field.value();
        if (field.type().isEnum()) {
            final Object[] all = field.type().getEnumConstants();
            final int at = v == null ? -1 : ((Enum<?>) v).ordinal();
            return all[(at + 1) % all.length];
        }
        return !Boolean.TRUE.equals(v);
    }

    /**
     * Parses colour text: {@code #rrggbb}, {@code #aarrggbb} or the same without {@code #}.
     *
     * @param t resolves the message
     * @param text the text
     * @return {@code 0xAARRGGBB}; six digits are opaque
     * @throws IllegalArgumentException for other text
     */
    public static int parseColor(final Text t, final String text) {
        final String hex = text.strip().startsWith("#") ? text.strip().substring(1) : text.strip();
        if (hex.length() != 6 && hex.length() != 8) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.color.invalid"));
        }
        try {
            final long v = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.color.invalid"), e);
        }
    }

    /**
     * Formats a colour for the picker and forms.
     *
     * @param argb {@code 0xAARRGGBB}
     * @return {@code #rrggbb} when opaque, else {@code #aarrggbb}
     */
    public static String colorText(final int argb) {
        return (argb >>> 24) == 0xFF ? String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF)
                : String.format(Locale.ROOT, "#%08x", argb);
    }

    /**
     * Applies scroll steps to a number field's text, for the number editor.
     *
     * @param text the current text
     * @param steps whole scroll steps, positive up
     * @param whole whether the field takes whole numbers; others step by 0.1
     * @return the new text, or {@code text} when it is not a number
     */
    public static String scrolled(final String text, final int steps, final boolean whole) {
        final double d;
        try {
            d = Double.parseDouble(text.strip());
        } catch (final NumberFormatException e) {
            return text;
        }
        if (whole) {
            return Long.toString(Math.round(d) + steps);
        }
        return CommandArgs.plain(Math.round((d + steps * 0.1d) * 10d) / 10d);
    }
}
