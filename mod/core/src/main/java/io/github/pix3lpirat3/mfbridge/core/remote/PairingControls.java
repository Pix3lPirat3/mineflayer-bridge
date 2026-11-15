package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import org.jspecify.annotations.Nullable;

/**
 * Remote pairing. While a code is active, {@code
 * auth.challenge} offers {@code pair}; a plugin connected over TLS that answers with the code gets a
 * device credential in {@code pair.result}. A code is single use, expires after {@link #VALIDITY_MS}
 * and is cancelled after {@link #MAX_FAILED_ATTEMPTS} wrong answers. {@link DevicePaired#TYPE} is
 * posted on the bridge event bus after a successful pairing. Client thread only.
 */
@ClientThread
public interface PairingControls {
    /** How long a code stays valid, in ms. */
    long VALIDITY_MS = 5 * 60_000L;
    /** Wrong codes accepted before the active code is cancelled. */
    int MAX_FAILED_ATTEMPTS = 5;

    /**
     * Starts pairing with a fresh code, replacing any active one.
     *
     * @param advertiseHost host to put in the URI, or null or blank for {@link #defaultAdvertiseHost()}
     * @return the new pairing session
     */
    PairingSession begin(@Nullable String advertiseHost);

    /** Cancels the active code, if any. */
    void cancel();

    /** Returns the active code, or null when none is active or it expired. */
    @Nullable PairingSession active();

    /**
     * Returns the host the URI uses by default: the configured advertise host, else the first
     * site-local IPv4 address of an interface that is up when the bind address is a wildcard, else
     * the bind address.
     */
    String defaultAdvertiseHost();
}
