package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.LayerControls;
import io.github.pix3lpirat3.mfbridge.core.LayerInfo;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Layers and their visibility. A layer is identified by its key {@code
 * <owner>/<extension>/<layer>}, which is also the key of the {@code core:layer} object declaring it.
 */
@ClientThread
public final class LayerRegistry implements LayerControls {
    private record Declared(@Nullable String category, boolean defaultVisible) {}

    private final NavigableMap<String, Declared> declared = new TreeMap<>();
    private final PlayerPrefs prefs;
    private final Consumer<String> onChange;

    /**
     * Creates the registry.
     *
     * @param onChange called with the owner (no trailing slash) whose layer visibility changed
     */
    public LayerRegistry(PlayerPrefs prefs, Consumer<String> onChange) {
        this.prefs = prefs;
        this.onChange = onChange;
    }

    /** Builds a layer key. */
    public static String key(String owner, String extension, String layer) {
        return owner + '/' + extension + '/' + layer;
    }

    /**
     * Records a {@code core:layer} object.
     *
     * @return whether the effective declaration changed
     */
    public boolean declare(String layerKey, @Nullable String category, boolean defaultVisible) {
        Declared next = new Declared(category, defaultVisible);
        return !next.equals(declared.put(layerKey, next));
    }

    /**
     * Forgets a {@code core:layer} object.
     *
     * @return whether it was declared
     */
    public boolean undeclare(String layerKey) {
        return declared.remove(layerKey) != null;
    }

    /** Returns the effective visibility of a layer key. Allocation-free. */
    public boolean visible(String layerKey) {
        Boolean override = prefs.layers().get(layerKey);
        if (override != null) {
            return override;
        }
        Declared d = declared.get(layerKey);
        return d == null || d.defaultVisible();
    }

    /** Returns the layers of one owner for {@code interest}, sorted by key. */
    public List<LayerVisibility> forOwner(String owner) {
        String prefix = owner + '/';
        String end = prefix + Character.MAX_VALUE;
        TreeSet<String> keys = new TreeSet<>(declared.subMap(prefix, end).keySet());
        if (prefs.layers() instanceof NavigableMap<String, Boolean> nav) {
            keys.addAll(nav.subMap(prefix, end).keySet());
        }
        List<LayerVisibility> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            String rest = k.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                out.add(new LayerVisibility(owner, rest.substring(0, slash), rest.substring(slash + 1), visible(k)));
            }
        }
        return out;
    }

    @Override
    public List<LayerInfo> layers() {
        TreeSet<String> keys = new TreeSet<>(declared.keySet());
        keys.addAll(prefs.layers().keySet());
        List<LayerInfo> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            LayerInfo info = info(k);
            if (info != null) {
                out.add(info);
            }
        }
        return List.copyOf(out);
    }

    private @Nullable LayerInfo info(String k) {
        String owner = ownerOf(k);
        if (owner == null) {
            return null;
        }
        String rest = k.substring(owner.length() + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        Declared d = declared.get(k);
        return new LayerInfo(
                owner,
                rest.substring(0, slash),
                rest.substring(slash + 1),
                d == null ? null : d.category(),
                d == null || d.defaultVisible(),
                prefs.layers().get(k),
                visible(k));
    }

    private static @Nullable String ownerOf(String key) {
        int first = key.indexOf('/');
        if (first < 0) {
            return null;
        }
        if (key.startsWith("ctl:")) {
            int second = key.indexOf('/', first + 1);
            return second < 0 ? null : key.substring(0, second);
        }
        return key.substring(0, first);
    }

    @Override
    public boolean isVisible(String owner, String extension, String layer) {
        return visible(key(owner, extension, layer));
    }

    @Override
    public void setVisible(String owner, String extension, String layer, @Nullable Boolean visible) {
        String k = key(owner, extension, layer);
        Map<String, Boolean> overrides = prefs.layers();
        Boolean previous = visible == null ? overrides.remove(k) : overrides.put(k, visible);
        if (!Objects.equals(previous, visible)) {
            prefs.save();
            onChange.accept(owner);
        }
    }
}
