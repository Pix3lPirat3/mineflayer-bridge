/**
 * The client side of mod extensions: what an extension adds to the game,
 * such as Bridge home tabs, bot buttons, keys, tick, world render and HUD hooks, and the switches
 * that turn extensions off. Client thread only, except world render hooks, which run on the render thread.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.ext;

import org.jspecify.annotations.NullMarked;
