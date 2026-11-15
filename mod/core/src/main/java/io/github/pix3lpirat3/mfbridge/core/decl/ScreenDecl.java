package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import org.jspecify.annotations.Nullable;

/**
 * A declared screen.
 *
 * @param key object key of the {@code core:screen}
 * @param ownerName display name of the owning session
 * @param ownerIdentity bot identity of the owner, or null for a controller
 * @param data the declaration
 */
public record ScreenDecl(String key, String ownerName, @Nullable String ownerIdentity, ScreenData data) {

    /** {@code home} value of a screen that adds its tabs to Bridge home. */
    public static final String HOME_TAB = "tab";

    /** {@code home} value of a screen offered as the whole primary menu. */
    public static final String HOME_MENU = "menu";
}
