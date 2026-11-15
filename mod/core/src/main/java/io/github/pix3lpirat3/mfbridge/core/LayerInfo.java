package io.github.pix3lpirat3.mfbridge.core;

import org.jspecify.annotations.Nullable;

/**
 * A layer the player can toggle.
 *
 * @param owner owner without trailing slash, for example {@code bot:<identity>}
 * @param extension extension segment
 * @param layer local layer name
 * @param category category from the {@code core:layer} object, or null
 * @param defaultVisible visibility the owner declared
 * @param override the player's choice, or null when the player has not chosen
 * @param visible effective visibility
 */
public record LayerInfo(
        String owner,
        String extension,
        String layer,
        @Nullable String category,
        boolean defaultVisible,
        @Nullable Boolean override,
        boolean visible) {}
