package io.github.pix3lpirat3.mfbridge.core.vars;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of every {@code core:vars} map, by scope {@code
 * <owner>/<extension>}. The core publishes a new instance with a larger {@link #version()} whenever
 * any map changes, so renderers can key caches by version.
 */
@AnyThread
public final class Variables {
    /** Local id of the one variables object of an owner and extension. */
    public static final String LOCAL_ID = "~vars";

    /** Extension whose map is the owner-wide fallback scope. */
    public static final String USER_SCOPE = "user:default";

    /** Marker {@link #lookup} returns for names no scope defines. Compare by identity. */
    public static final Object MISSING = new Object();

    /** No variables, version 0. */
    public static final Variables EMPTY = new Variables(0, Map.of());

    private final long version;
    private final Map<String, Map<String, @Nullable Object>> byScope;

    /**
     * Creates a view.
     *
     * @param version content version; larger is newer
     * @param byScope maps by {@code <owner>/<extension>}; copied one level deep, values are not copied
     */
    public Variables(long version, Map<String, Map<String, @Nullable Object>> byScope) {
        this.version = version;
        Map<String, Map<String, @Nullable Object>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, @Nullable Object>> e : byScope.entrySet()) {
            copy.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        this.byScope = copy;
    }

    /** Returns the content version. */
    public long version() {
        return version;
    }

    /**
     * Looks a name up as templates do: the owner and extension's own map first, then the owner's
     * {@value #USER_SCOPE} map.
     *
     * @return the value, null for a present nil, or {@link #MISSING} when neither map has the name
     */
    public @Nullable Object lookup(String owner, String extension, String name) {
        Map<String, @Nullable Object> own = byScope.get(owner + '/' + extension);
        if (own != null && own.containsKey(name)) {
            return own.get(name);
        }
        Map<String, @Nullable Object> user = byScope.get(owner + '/' + USER_SCOPE);
        if (user != null && user.containsKey(name)) {
            return user.get(name);
        }
        return MISSING;
    }

    /**
     * Returns the first variable of an owner in scope key order, for one-line HUD summaries.
     *
     * @return {@code name value}, or null when the owner has no variables
     */
    public @Nullable String firstOf(String owner) {
        String best = null;
        String bestScope = null;
        String prefix = owner + '/';
        for (Map.Entry<String, Map<String, @Nullable Object>> e : byScope.entrySet()) {
            if (!e.getKey().startsWith(prefix) || e.getValue().isEmpty()
                    || bestScope != null && e.getKey().compareTo(bestScope) >= 0) {
                continue;
            }
            String first = null;
            for (String k : e.getValue().keySet()) {
                if (first == null || k.compareTo(first) < 0) {
                    first = k;
                }
            }
            bestScope = e.getKey();
            best = first + " " + Templates.format(e.getValue().get(first), null);
        }
        return best;
    }
}
