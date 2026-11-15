package io.github.pix3lpirat3.mfbridge.core.vars;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import java.util.IdentityHashMap;

/**
 * Caches resolved templates per text instance and {@link Variables#version()},
 * so a steady frame resolves without allocating. Texts come from decoded object
 * data, whose strings stay the same instances until the object changes, so entries are keyed by
 * identity. The cache starts over once it holds {@value #MAX_ENTRIES} texts. Not thread safe: each
 * thread that renders keeps its own.
 */
public final class TemplateCache {
    /** Most texts kept before the cache starts over. */
    public static final int MAX_ENTRIES = 4096;

    private static final class Entry {
        String objectKey = "";
        long version = -1;
        String result = "";
    }

    private final IdentityHashMap<String, Entry> entries = new IdentityHashMap<>();

    /** Creates an empty cache. */
    public TemplateCache() {}

    /**
     * Resolves {@code text} against the variables of the object's owner and extension.
     *
     * @param text template text from the object's data
     * @param objectKey full key {@code <owner>/<extension>/<local id>} of the object the text belongs to
     * @param vars the current variables
     * @return the resolved text; {@code text} itself when it has no placeholder or the key is malformed
     */
    public String resolve(String text, String objectKey, Variables vars) {
        if (!Templates.templated(text)) {
            return text;
        }
        Entry e = entries.get(text);
        if (e != null && e.version == vars.version() && e.objectKey.equals(objectKey)) {
            return e.result;
        }
        ObjectKeys.Parts parts;
        try {
            parts = ObjectKeys.parse(objectKey);
        } catch (BridgeException ex) {
            return text;
        }
        String result = Templates.resolve(text, name -> vars.lookup(parts.owner(), parts.extension(), name));
        if (e == null) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
            e = new Entry();
            entries.put(text, e);
        }
        e.objectKey = objectKey;
        e.version = vars.version();
        e.result = result;
        return result;
    }

    /** Returns the number of cached texts. */
    public int size() {
        return entries.size();
    }
}
