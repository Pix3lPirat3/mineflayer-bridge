/**
 * Vanilla input surfaces for prompts and forms: dialog, anvil, sign,
 * book and chat. Each is the bridge's own screen or connection adapter and never sends the packet its
 * vanilla counterpart sends. {@code BotSurfaces} decides when what a bot asks for may open.
 * Client thread only.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import org.jspecify.annotations.NullMarked;
