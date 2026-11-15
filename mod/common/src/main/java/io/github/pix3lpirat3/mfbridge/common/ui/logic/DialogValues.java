package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The game-free half of the vanilla dialog surface ({@code dialog}):
 * which dialog inputs a prompt or form needs, and how the values a dialog action carries map back to
 * a prompt answer or raw form input.
 *
 * <p>Payload values are what vanilla dialog inputs put into the action's tag: a {@code String} for
 * text and single-option inputs, a {@code Boolean} for booleans and a {@code Number} for number
 * ranges. Dialog input keys must be template variable names, so fields get generated keys instead of
 * their ids.
 */
public final class DialogValues {

    /** Payload key of the button a dialog was closed with. */
    public static final String BUTTON = "button";
    /** Payload key of a prompt's single input. */
    public static final String VALUE = "value";
    /** {@link #BUTTON} value of the submit, yes and choice buttons' common prefix. */
    public static final String SUBMIT = "submit";
    /** {@link #BUTTON} value of a confirm prompt's yes button. */
    public static final String YES = "yes";
    /** {@link #BUTTON} value of a confirm prompt's no button. */
    public static final String NO = "no";
    /** {@link #BUTTON} value prefix of choice buttons, followed by the choice index. */
    public static final String CHOICE = "choice";

    private DialogValues() {
    }

    /** The vanilla input control a dialog input uses. */
    public enum Control {
        /** A one-line text input. */
        TEXT,
        /** A multi-line text input. */
        MULTILINE,
        /** A checkbox. */
        TOGGLE,
        /** A slider between the field's min and max. */
        SLIDER,
        /** A cycling button over the field's options. */
        OPTIONS
    }

    /**
     * One dialog input.
     *
     * @param key the dialog input key
     * @param field the form field it belongs to
     * @param control the vanilla control
     * @param option for multi-select checkboxes, the option it toggles; else null
     */
    public record Input(String key, FieldSpec field, Control control, @Nullable String option) {
    }

    /**
     * Plans the dialog inputs of a form: one per field, and one checkbox per option of a multi-select.
     * Positions are typed as {@code x y z}.
     *
     * @param fields the form's fields
     * @return the inputs in field order. Immutable.
     */
    public static List<Input> formInputs(final List<FieldSpec> fields) {
        final List<Input> out = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            final FieldSpec f = fields.get(i);
            final String key = "f" + i;
            switch (f.kind()) {
                case TEXT, NUMBER, INTEGER, ITEM, BLOCK, ZONE, BLOCK_POS, VEC3 -> out.add(new Input(key, f, Control.TEXT, null));
                case SLIDER -> out.add(new Input(key, f, Control.SLIDER, null));
                case TOGGLE -> out.add(new Input(key, f, Control.TOGGLE, null));
                case SELECT -> out.add(new Input(key, f, f.options().isEmpty() ? Control.TEXT : Control.OPTIONS, null));
                case MULTI_SELECT -> {
                    for (int j = 0; j < f.options().size(); j++) {
                        out.add(new Input(key + "_" + j, f, Control.TOGGLE, f.options().get(j)));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Returns the text a text input starts with for a field.
     *
     * @param field the field
     * @return its default as typed text
     */
    public static String initialText(final FieldSpec field) {
        final Object raw = FormValues.initial(field);
        if (raw instanceof List<?> parts) {
            final List<String> strings = new ArrayList<>(parts.size());
            for (final Object p : parts) {
                strings.add(String.valueOf(p));
            }
            return String.join(" ", strings).strip();
        }
        return String.valueOf(raw);
    }

    /**
     * Converts a submitted form dialog's payload into raw form input for {@link FormValues#validate}.
     *
     * @param inputs the planned inputs
     * @param payload dialog input key to value
     * @return field id to raw input
     */
    public static Map<String, Object> formRaw(final List<Input> inputs, final Map<String, Object> payload) {
        final Map<String, Object> raw = new LinkedHashMap<>();
        for (final Input in : inputs) {
            final FieldSpec f = in.field();
            final Object v = payload.get(in.key());
            switch (f.kind()) {
                case MULTI_SELECT -> {
                    @SuppressWarnings("unchecked")
                    final List<String> picked = (List<String>) raw.computeIfAbsent(f.id(), k -> new ArrayList<String>());
                    if (Boolean.TRUE.equals(v) && in.option() != null) {
                        picked.add(in.option());
                    }
                }
                case TOGGLE -> raw.put(f.id(), Boolean.TRUE.equals(v));
                case SLIDER -> raw.put(f.id(), v instanceof Number n ? n.doubleValue() : FormValues.initial(f));
                case BLOCK_POS, VEC3 -> raw.put(f.id(), coordinates(v == null ? "" : v.toString()));
                case TEXT, NUMBER, INTEGER, ITEM, BLOCK, ZONE, SELECT -> raw.put(f.id(), v == null ? "" : v.toString());
            }
        }
        return raw;
    }

    /** Splits {@code x y z} (spaces or commas) into three parts, or blanks when nothing was typed. */
    static List<String> coordinates(final String typed) {
        final String s = typed.strip();
        if (s.isEmpty()) {
            return List.of("", "", "");
        }
        final String[] parts = s.split("[\\s,]+");
        final List<String> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            out.add(i < parts.length ? parts[i] : "");
        }
        return out;
    }

    /**
     * Returns whether a number prompt is shown as a slider: it needs both bounds.
     *
     * @param request the prompt
     * @return true for a slider, false for a text input
     */
    public static boolean slider(final PromptRequest request) {
        return request.kind() == PromptKind.NUMBER && request.min() != null && request.max() != null
                && request.min() < request.max();
    }

    /**
     * Converts a prompt dialog's payload into the answer for {@code InputControls.resolvePrompt}.
     *
     * @param t resolves the text
     * @param request the prompt
     * @param payload the action payload: {@link #BUTTON} and, for text, number, lines and pages, {@link #VALUE}
     * @return the answer
     * @throws IllegalArgumentException with a message for the player when the payload does not answer the prompt
     */
    public static Object promptAnswer(final Text t, final PromptRequest request, final Map<String, Object> payload) {
        final String button = String.valueOf(payload.get(BUTTON));
        final Object value = payload.get(VALUE);
        return switch (request.kind()) {
            case CONFIRM -> switch (button) {
                case YES -> Boolean.TRUE;
                case NO -> Boolean.FALSE;
                default -> throw new IllegalArgumentException(t.get("mineflayer_bridge.prompt.choose_yes_no"));
            };
            case CHOICE -> {
                final List<String> choices = request.choices() == null ? List.of() : request.choices();
                final int index = button.startsWith(CHOICE) ? parseIndex(button.substring(CHOICE.length())) : -1;
                if (index < 0 || index >= choices.size()) {
                    throw new IllegalArgumentException(t.get("mineflayer_bridge.prompt.choose_option"));
                }
                yield choices.get(index);
            }
            case TEXT -> value == null ? "" : value.toString();
            case NUMBER -> number(t, value);
            case LINES -> lines(value == null ? "" : value.toString());
            case PAGES -> List.of(value == null ? "" : value.toString());
        };
    }

    private static int parseIndex(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static Object number(final Text t, final @Nullable Object value) {
        final double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else {
            try {
                d = Double.parseDouble(value == null ? "" : value.toString().strip());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(t.get("mineflayer_bridge.prompt.enter_number"), e);
            }
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(t.get("mineflayer_bridge.prompt.enter_number"));
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
    }

    /**
     * Splits multi-line text into lines, dropping trailing empty lines.
     *
     * @param text the typed text
     * @return the lines. Immutable.
     */
    public static List<String> lines(final String text) {
        final List<String> out = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        while (out.size() > 1 && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return List.copyOf(out);
    }
}
