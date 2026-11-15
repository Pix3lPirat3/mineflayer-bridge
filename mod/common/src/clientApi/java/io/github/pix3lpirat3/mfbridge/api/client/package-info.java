/**
 * The API for mod extensions that add to the game client: Bridge home tabs, bot buttons, keys,
 * input stages, tick, world and HUD hooks ({@link io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar}),
 * and a narrow view of the running bridge ({@link io.github.pix3lpirat3.mfbridge.api.client.ClientBridge}).
 *
 * <p>An extension implements {@link io.github.pix3lpirat3.mfbridge.api.client.ClientExtension} on the
 * same class as its {@code BridgeExtension}. This package and {@code io.github.pix3lpirat3.mfbridge.api}
 * are the supported surface; the mod's other packages change without notice. Everything runs on the
 * client thread, except world render hooks, which run on the render thread.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.api.client;

import org.jspecify.annotations.NullMarked;
