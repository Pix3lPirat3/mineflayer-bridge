package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One live or detached session as the inspector's Sessions tab shows it.
 *
 * @param sessionId per-connection session id
 * @param connectionId the connection it runs on, or 0 while detached
 * @param kind {@code bot} or {@code controller}
 * @param displayName display name
 * @param identity bot identity key, or null for controllers
 * @param owner owner, for example {@code bot:<identity>}
 * @param world the bot's reported world, or null
 * @param attached whether the player has it attached
 * @param worldMatched whether its world matches the player's
 * @param connected whether it is on a live connection (false while in the resume window)
 * @param superseded whether a newer session took over its identity
 * @param detachedForMs time since it lost its connection, or -1 while connected
 * @param interest the last {@code interest} sent, or null
 * @param extensions the extensions announced in {@code session.open}, as {@code id@version}.
 *     Immutable.
 * @param objects objects stored under its owned prefix
 * @param samples its client sample request, or null when it has none
 * @param unackedMessages reliable messages kept for a resume
 */
public record SessionInfo(
        int sessionId,
        long connectionId,
        String kind,
        String displayName,
        @Nullable String identity,
        String owner,
        @Nullable WorldKey world,
        boolean attached,
        boolean worldMatched,
        boolean connected,
        boolean superseded,
        long detachedForMs,
        @Nullable Interest interest,
        List<String> extensions,
        int objects,
        @Nullable SampleRequest samples,
        int unackedMessages) {

    /** Copies {@code extensions}. */
    public SessionInfo {
        extensions = List.copyOf(extensions);
    }
}
