package io.github.pix3lpirat3.mfbridge.core.store;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/**
 * Object key structure: {@code <owner>/<extension>/<local id>}. Owners are
 * {@code bot:<identity>}, {@code ctl:<device>/<name>} (two segments), {@code shared} or {@code
 * player}. The local id may contain further slashes.
 */
@AnyThread
public final class ObjectKeys {
    /** Prefix of shared keys. */
    public static final String SHARED_PREFIX = "shared/";

    /** Prefix of player keys. */
    public static final String PLAYER_PREFIX = "player/";

    /** Longest accepted key, in UTF-16 units. */
    public static final int MAX_KEY_LENGTH = 1024;

    /**
     * Parsed key.
     *
     * @param owner owner without trailing slash
     * @param extension extension segment
     * @param localId local id
     */
    public record Parts(String owner, String extension, String localId) {}

    private ObjectKeys() {}

    /**
     * Splits a key.
     *
     * @throws BridgeException with {@code invalid_message} when the key is malformed
     */
    public static Parts parse(String key) {
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw invalid(key);
        }
        int ownerEnd = key.indexOf('/');
        if (ownerEnd <= 0) {
            throw invalid(key);
        }
        if (key.startsWith("ctl:")) {
            ownerEnd = key.indexOf('/', ownerEnd + 1);
            if (ownerEnd < 0) {
                throw invalid(key);
            }
        }
        int extEnd = key.indexOf('/', ownerEnd + 1);
        if (extEnd <= ownerEnd + 1 || extEnd == key.length() - 1) {
            throw invalid(key);
        }
        return new Parts(key.substring(0, ownerEnd), key.substring(ownerEnd + 1, extEnd), key.substring(extEnd + 1));
    }

    private static BridgeException invalid(String key) {
        String shown = key.length() > 64 ? key.substring(0, 64) + "..." : key;
        return new BridgeException("invalid_message", "malformed object key '" + shown + "'");
    }
}
