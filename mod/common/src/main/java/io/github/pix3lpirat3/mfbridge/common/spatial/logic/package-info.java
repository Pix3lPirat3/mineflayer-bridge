/**
 * Game-free logic behind spatial editing: draw
 * commands for zones, points and routes, gizmo handles with their hit tests, drag and nudge edits,
 * and the ruler's measurements. Nothing here touches Minecraft classes, so it is unit tested without
 * a game launch. Mutable types are confined to the client thread.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.spatial.logic;

import org.jspecify.annotations.NullMarked;
