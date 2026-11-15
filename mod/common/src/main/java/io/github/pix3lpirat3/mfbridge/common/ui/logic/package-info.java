/**
 * Game-free logic behind the in-game UI: fuzzy search, command argument parsing, target
 * filters, pick geometry, keybind lookup and form field validation. Nothing here touches Minecraft
 * classes, so it is unit tested without a game launch. Every type is safe to use from any thread
 * unless stated otherwise; mutable types are confined to the thread that owns them.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import org.jspecify.annotations.NullMarked;
