/**
 * The command engine: one
 * game-free parser, validator and completer behind {@code /bridge}, the palette argument form and
 * {@code @bot} chat commands. Game state reaches it through {@link
 * io.github.pix3lpirat3.mfbridge.core.command.ArgContext}, {@link
 * io.github.pix3lpirat3.mfbridge.core.command.EntityQuery} and {@link
 * io.github.pix3lpirat3.mfbridge.core.command.CompletionSource} implementations the client registers.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.core.command;

import org.jspecify.annotations.NullMarked;
