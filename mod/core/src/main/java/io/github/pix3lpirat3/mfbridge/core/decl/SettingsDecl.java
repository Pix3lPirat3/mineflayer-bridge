package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import java.util.Map;

/**
 * A {@code core:settings} group with the player's current values.
 *
 * @param key full object key, {@code <owner>/<extension>/~settings/<group>}; pass it to {@code
 *     BridgeCore.setSettingValue}
 * @param ownerName display name of the owning session
 * @param data decoded declaration
 * @param values effective value per setting key: the player's stored value, else the declared
 *     default; settings with neither are absent. Values are {@code Boolean}, {@code Long} (int and
 *     color), {@code Double} (float) or {@code String}. Immutable.
 */
public record SettingsDecl(String key, String ownerName, SettingsData data, Map<String, Object> values) {
    /** Copies {@code values}. */
    public SettingsDecl {
        values = Map.copyOf(values);
    }
}
