package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.persist.BotSettingsStore;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingDef;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Bot-declared settings: merges declared defaults with the player's stored
 * values and sends them as {@code settings.values} when a session declares a {@code core:settings}
 * object, when the player changes a value, and after a resume.
 */
@ClientThread
final class BotSettings {
    private final SessionHost host;
    private final ObjectStore store;
    private final BotSettingsStore stored;
    private long version;

    BotSettings(SessionHost host, ObjectStore store, BotSettingsStore stored) {
        this.host = host;
        this.store = store;
        this.stored = stored;
    }

    /** Returns a counter that increases whenever a stored value changes. */
    long version() {
        return version;
    }

    /**
     * Returns the effective values of a settings group: a stored value that still fits the current
     * declaration, else the declared default. Unmodifiable, in declaration order.
     */
    Map<String, Object> values(String settingsKey, SettingsData data) {
        Map<String, Object> saved = stored.get(settingsKey);
        Map<String, Object> out = new LinkedHashMap<>();
        for (SettingDef def : data.settings()) {
            Object v = saved.get(def.key());
            Object checked = v == null ? null : fits(def, v);
            if (checked == null) {
                Object d = def.defaultValue();
                checked = d == null ? null : Objects.requireNonNullElse(fits(def, d), d);
            }
            if (checked != null) {
                out.put(def.key(), checked);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static @Nullable Object fits(SettingDef def, Object v) {
        try {
            return ValueRules.setting(def, v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Validates and stores a value, then tells the owning session. A value equal to the default is
     * not kept, so a later change of the default reaches the player.
     *
     * @throws IllegalArgumentException when the settings group or key is unknown or the value does
     *     not fit the declaration
     */
    void set(String settingsKey, String key, Object value) {
        StoredObject o = store.get(settingsKey);
        if (o == null || !(o.state().decoded() instanceof SettingsData data)) {
            throw new IllegalArgumentException("unknown settings group '" + settingsKey + "'");
        }
        SettingDef def = null;
        for (SettingDef d : data.settings()) {
            if (d.key().equals(key)) {
                def = d;
            }
        }
        if (def == null) {
            throw new IllegalArgumentException("unknown setting '" + key + "' in " + settingsKey);
        }
        Object checked = ValueRules.setting(def, value);
        Object d = def.defaultValue();
        boolean isDefault = d != null && checked.equals(fits(def, d));
        stored.set(settingsKey, key, isDefault ? null : checked);
        version++;
        send(o, data);
    }

    /** A {@code core:settings} object was written: its owner gets the merged values. */
    void declared(StoredObject o) {
        if (o.state().decoded() instanceof SettingsData data) {
            send(o, data);
        }
    }

    /** Re-sends every group of a resumed session, since latest messages are not logged. */
    void resumed(Session s) {
        for (StoredObject o : store.under(s.ownerPrefix)) {
            declared(o);
        }
    }

    private void send(StoredObject o, SettingsData data) {
        Session s = host.writer(o.parts().owner());
        if (s != null && !s.superseded) {
            host.sendLatest(s, CoreMessages.SETTINGS_VALUES, new SettingsValues(o.key(), values(o.key(), data)), o.key());
        }
    }
}
