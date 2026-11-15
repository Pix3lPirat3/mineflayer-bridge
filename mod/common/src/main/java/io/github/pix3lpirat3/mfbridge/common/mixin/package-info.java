/**
 * Mixins shared by both loaders, applied through {@code mineflayer_bridge.mixins.json}. They feed raw
 * input to {@link io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi} before vanilla sees it and expose the few private fields the UI reads. Code outside this package only
 * references the accessor interfaces.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.common.mixin;

import org.jspecify.annotations.NullMarked;
