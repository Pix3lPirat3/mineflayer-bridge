/**
 * Game-free logic behind displays, element interactions and world menus: transform interpolation, client-side
 * animation loops, world menu layout and paging, element hit testing and emitter timing. Nothing
 * here touches Minecraft classes, so it is unit tested without a game launch. Mutable types are
 * confined to the thread that owns them (the client thread in the mod).
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.display.logic;

import org.jspecify.annotations.NullMarked;
