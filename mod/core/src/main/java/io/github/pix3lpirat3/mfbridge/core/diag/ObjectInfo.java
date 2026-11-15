package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One stored object as the inspector's Objects tab shows it.
 *
 * @param key full object key
 * @param owner owner segment, for example {@code bot:<identity>} or {@code shared}
 * @param extension extension segment
 * @param layer layer name, or null
 * @param kind kind name, for example {@code core:box}
 * @param rev revision
 * @param scope global or world
 * @param world world of a world-scoped object, or null
 * @param dataBytes encoded data size in bytes
 * @param visible whether it is in the current render snapshot
 * @param decoded the decoded kind value, or null when it did not decode
 */
public record ObjectInfo(
        String key,
        String owner,
        String extension,
        @Nullable String layer,
        String kind,
        long rev,
        Scope scope,
        @Nullable WorldKey world,
        int dataBytes,
        boolean visible,
        @Nullable Object decoded) {

    /** Returns the decoded data as readable text. */
    public String dataText() {
        return decoded == null ? "<" + dataBytes + " bytes, not decoded>" : MessageText.render(decoded);
    }

    /**
     * Returns whether the object matches a search: every space-separated term must occur, ignoring
     * case, in the key, owner, extension, layer or kind.
     */
    public boolean matches(String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        String hay = (key + ' ' + owner + ' ' + extension + ' ' + (layer == null ? "" : layer) + ' ' + kind)
                .toLowerCase(Locale.ROOT);
        for (String term : q.split("\\s+")) {
            if (!hay.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
