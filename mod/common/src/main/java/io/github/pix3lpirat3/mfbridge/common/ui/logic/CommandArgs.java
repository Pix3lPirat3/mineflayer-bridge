package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.core.command.ArgValues;
import io.github.pix3lpirat3.mfbridge.core.command.TreeNode;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;

/**
 * Small helpers over command arguments shared by the UI. Parsing, validation and completion live in
 * the core's {@link io.github.pix3lpirat3.mfbridge.core.command.CommandEngine},
 * one engine for every entry point.
 */
public final class CommandArgs {

    private CommandArgs() {
    }

    /**
     * Adds the {@code minecraft} namespace to a bare id and lower-cases it.
     *
     * @param id an id such as {@code stone} or {@code minecraft:stone}
     * @return the namespaced id
     */
    public static String normalizeId(final String id) {
        return ArgValues.normalizeId(id);
    }

    /**
     * Returns whether an argument must be given: it is not optional and has no default.
     *
     * @param arg the declaration
     * @return whether it is required
     */
    public static boolean required(final CommandArg arg) {
        return TreeNode.required(arg);
    }

    /**
     * Formats a number without a trailing {@code .0} for whole values.
     *
     * @param value the number
     * @return the text
     */
    public static String plain(final double value) {
        return ArgValues.plain(value);
    }
}
