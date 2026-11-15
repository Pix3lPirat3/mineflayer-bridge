package io.github.pix3lpirat3.mfbridge.core.history;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjEditResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** What the player writes: shared objects, player objects, and edits proposed to the bot that owns an object. */
@ClientThread
public interface PlayerObjects {
    /**
     * Applies a shared op on behalf of the player. Kind ids in creates must be core kinds.
     *
     * @throws BridgeException with {@code conflict}, {@code not_owner}, {@code invalid_message} or,
     *     while the target world store is still loading, a retryable {@code unsupported}
     */
    SharedOpResult sharedOp(SharedOpBody body);

    /**
     * Writes a player object ({@code player} objects are written by the mod). The revision
     * increments automatically. Persisted in the store of the object's world, else the player's
     * current server, else the global store.
     *
     * @throws IllegalArgumentException when the key is not under {@code player/} or the data does not
     *     decode as {@code kind}
     */
    void put(String key, KindType<?> kind, @Nullable String layer, Scope scope, @Nullable WorldKey world, byte[] data);

    /**
     * Removes a player object.
     *
     * @return whether it existed
     */
    boolean remove(String key);

    /**
     * Sends an {@code obj.edit.propose} to the writer of a bot- or controller-owned object.
     *
     * @return completes with the bot's answer, or fails with {@code BridgeException} codes {@code
     *     timeout}, {@code disconnected}, {@code superseded} or the bot's error
     */
    CompletableFuture<ObjEditResult> proposeEdit(String key, long baseRev, byte[] fields);
}
