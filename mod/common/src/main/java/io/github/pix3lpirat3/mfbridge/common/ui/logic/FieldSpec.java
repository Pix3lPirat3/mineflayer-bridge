package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One field of a form screen, built from a bot's {@link FormField} or a command's {@link CommandArg}.
 *
 * @param id field id or argument name
 * @param label what the player sees
 * @param kind the widget to show
 * @param defaultValue the declared default, or null
 * @param min lower bound for numbers, or null
 * @param max upper bound for numbers, or null
 * @param step slider step, or null for continuous
 * @param options choices for select and multi-select, else empty. Immutable.
 * @param required whether a value must be given
 */
public record FieldSpec(
        String id,
        String label,
        Kind kind,
        @Nullable Object defaultValue,
        @Nullable Double min,
        @Nullable Double max,
        @Nullable Double step,
        List<String> options,
        boolean required) {

    /** Copies the options. */
    public FieldSpec {
        options = List.copyOf(options);
    }

    /** The widget a field is shown with. */
    public enum Kind {
        /** Free text. */
        TEXT,
        /** A number typed as text. */
        NUMBER,
        /** A whole number typed as text. */
        INTEGER,
        /** A slider between min and max. */
        SLIDER,
        /** On or off. */
        TOGGLE,
        /** One of the options. */
        SELECT,
        /** Any subset of the options. */
        MULTI_SELECT,
        /** An item id typed as text. */
        ITEM,
        /** A block id typed as text. */
        BLOCK,
        /** Three whole coordinates with an in-world pick. */
        BLOCK_POS,
        /** Three exact coordinates. */
        VEC3,
        /** A zone key typed as text. */
        ZONE
    }

    /**
     * Converts a bot form field.
     *
     * @param field the declaration
     * @return the field spec
     */
    public static FieldSpec of(final FormField field) {
        final Kind kind = switch (field.type()) {
            case TEXT -> Kind.TEXT;
            case NUMBER -> Kind.NUMBER;
            case SLIDER -> field.min() != null && field.max() != null ? Kind.SLIDER : Kind.NUMBER;
            case TOGGLE -> Kind.TOGGLE;
            case SELECT -> Kind.SELECT;
            case MULTI_SELECT -> Kind.MULTI_SELECT;
            case ITEM -> Kind.ITEM;
            case BLOCK_POS -> Kind.BLOCK_POS;
            case ZONE -> Kind.ZONE;
        };
        final List<String> options = field.options() == null ? List.of() : field.options();
        return new FieldSpec(field.id(), field.label() != null ? field.label() : field.id(), kind, field.defaultValue(),
                field.min(), field.max(), field.step(), options, field.required());
    }

    /**
     * Converts a command argument for the argument form.
     *
     * @param arg the declaration
     * @return the field spec
     */
    public static FieldSpec of(final CommandArg arg) {
        final Kind kind = switch (arg.type()) {
            case STRING, GREEDY, ENTITY, ENTITY_LIST, PLAYER, BOT, POINT -> Kind.TEXT;
            case INT -> Kind.INTEGER;
            case FLOAT -> Kind.NUMBER;
            case BOOL -> Kind.TOGGLE;
            case CHOICE -> arg.choices() == null || arg.choices().isEmpty() ? Kind.TEXT : Kind.SELECT;
            case BLOCK_POS -> Kind.BLOCK_POS;
            case VEC3 -> Kind.VEC3;
            case BLOCK_ID -> Kind.BLOCK;
            case ITEM_ID -> Kind.ITEM;
            case ZONE -> Kind.ZONE;
        };
        final List<String> options = arg.choices() == null ? List.of() : arg.choices();
        final String label = arg.description() == null ? arg.name() : arg.name() + " (" + arg.description() + ")";
        return new FieldSpec(arg.name(), label, kind, arg.defaultValue(), arg.min(), arg.max(), null, options,
                CommandArgs.required(arg));
    }
}
