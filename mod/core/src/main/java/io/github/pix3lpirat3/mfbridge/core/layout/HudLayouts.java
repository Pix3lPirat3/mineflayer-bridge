package io.github.pix3lpirat3.mfbridge.core.layout;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Saved HUD panel placements, kept under {@code player} preferences. A
 * placement is saved per owner (the bot identity) and panel, and also as a fallback per extension
 * and panel, so a bot that reconnects tomorrow, or another bot running the same extension, finds
 * its panels where the player left them.
 *
 * <p>A panel key is {@code <owner>/<extension>/~hud/<panel>}; the layout keys are {@code
 * <owner>|<extension>|<panel>} and {@code *|<extension>|<panel>}.
 */
@ClientThread
public final class HudLayouts {
    private static final String ANY_OWNER = "*";

    private final PlayerPrefs prefs;
    private long version;

    /** Creates the layouts over the player's preferences. */
    public HudLayouts(PlayerPrefs prefs) {
        this.prefs = prefs;
    }

    /**
     * Returns the layout keys of a panel key: the owner-specific one first, then the extension
     * fallback; null when the key is not a HUD panel key.
     */
    public static String @Nullable [] keys(String panelKey) {
        ObjectKeys.Parts parts;
        try {
            parts = ObjectKeys.parse(panelKey);
        } catch (BridgeException e) {
            return null;
        }
        String local = parts.localId();
        String panel = local.startsWith("~hud/") ? local.substring("~hud/".length()) : local;
        String tail = '|' + parts.extension() + '|' + panel;
        return new String[] {parts.owner() + tail, ANY_OWNER + tail};
    }

    /**
     * Returns the saved layout of a panel: the owner-specific one, else the extension fallback, else
     * null when the player never moved it.
     */
    public @Nullable HudLayout get(String panelKey) {
        String[] keys = keys(panelKey);
        if (keys == null) {
            return null;
        }
        Map<String, PlayerPrefs.HudPlacement> hud = prefs.hud();
        PlayerPrefs.HudPlacement p = hud.get(keys[0]);
        if (p == null) {
            p = hud.get(keys[1]);
        }
        return p == null ? null : fromPlacement(p);
    }

    /** Saves a panel's layout under both of its keys. Ignored for a key that is not a panel key. */
    public void set(String panelKey, HudLayout layout) {
        String[] keys = keys(panelKey);
        if (keys == null) {
            return;
        }
        PlayerPrefs.HudPlacement p = toPlacement(layout);
        prefs.hud().put(keys[0], p);
        prefs.hud().put(keys[1], p);
        version++;
        prefs.save();
    }

    /** Forgets a panel's layout, owner-specific and fallback, so it returns to its declared anchor. */
    public void reset(String panelKey) {
        String[] keys = keys(panelKey);
        if (keys == null) {
            return;
        }
        boolean a = prefs.hud().remove(keys[0]) != null;
        boolean b = prefs.hud().remove(keys[1]) != null;
        if (a || b) {
            version++;
            prefs.save();
        }
    }

    /** Forgets every saved layout. */
    public void resetAll() {
        if (!prefs.hud().isEmpty()) {
            prefs.hud().clear();
            version++;
            prefs.save();
        }
    }

    /** Returns a counter that changes whenever a layout changes, for layout caches. */
    public long version() {
        return version;
    }

    private static HudLayout fromPlacement(PlayerPrefs.HudPlacement p) {
        HudAnchor anchor = null;
        String name = p.anchor();
        if (name != null) {
            for (HudAnchor a : HudAnchor.values()) {
                if (a.name().equalsIgnoreCase(name)) {
                    anchor = a;
                }
            }
        }
        return new HudLayout(anchor, p.x(), p.y(), p.width(), p.hidden());
    }

    private static PlayerPrefs.HudPlacement toPlacement(HudLayout l) {
        HudAnchor a = l.anchor();
        return new PlayerPrefs.HudPlacement(a == null ? null : a.name().toLowerCase(Locale.ROOT), l.x(), l.y(),
                l.width(), l.hidden());
    }
}
