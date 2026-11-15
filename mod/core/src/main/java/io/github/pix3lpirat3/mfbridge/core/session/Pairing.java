package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingControls;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingUris;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/** The active pairing code behind {@link PairingControls}. */
@ClientThread
final class Pairing implements PairingControls {
    /** Outcome of one {@code pair} answer. */
    enum Attempt {
        ACCEPTED,
        /** No code is active. */
        INACTIVE,
        /** The active code ran out of time. */
        EXPIRED,
        WRONG
    }

    private final BridgeClock clock;
    private final SecureRandom random;
    private final InstanceIdentity identity;
    private final @Nullable String configuredHost;
    private final String bindAddress;
    private final IntSupplier port;
    private @Nullable PairingSession active;
    private int failures;
    /** Whether the last code ran out of time rather than being used or cancelled. */
    private boolean expired;

    Pairing(BridgeClock clock, SecureRandom random, InstanceIdentity identity, @Nullable String configuredHost,
            String bindAddress, IntSupplier port) {
        this.clock = clock;
        this.random = random;
        this.identity = identity;
        this.configuredHost = configuredHost;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    @Override
    public PairingSession begin(@Nullable String advertiseHost) {
        String host = advertiseHost == null || advertiseHost.isBlank() ? defaultAdvertiseHost() : advertiseHost.strip();
        String code = PairingUris.newCode(random);
        int p = port.getAsInt();
        PairingSession s = new PairingSession(PairingUris.uri(host, p, identity.pairingPrefix(), code), code, host, p,
                clock.currentTimeMillis() + VALIDITY_MS);
        active = s;
        failures = 0;
        expired = false;
        return s;
    }

    @Override
    public void cancel() {
        active = null;
        failures = 0;
        expired = false;
    }

    @Override
    public @Nullable PairingSession active() {
        PairingSession s = active;
        if (s != null && clock.currentTimeMillis() >= s.expiresAtMs()) {
            cancel();
            expired = true;
            return null;
        }
        return s;
    }

    @Override
    public String defaultAdvertiseHost() {
        return PairingUris.defaultHost(configuredHost, bindAddress);
    }

    /**
     * Checks a {@code pair} proof against the active code in constant time. A match consumes the
     * code; the {@link #MAX_FAILED_ATTEMPTS}th wrong answer cancels it.
     */
    Attempt attempt(byte[] proof) {
        PairingSession s = active();
        if (s == null) {
            return expired ? Attempt.EXPIRED : Attempt.INACTIVE;
        }
        byte[] typed = PairingUris.normalize(new String(proof, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(typed, s.code().getBytes(StandardCharsets.UTF_8))) {
            cancel();
            return Attempt.ACCEPTED;
        }
        failures++;
        if (failures >= MAX_FAILED_ATTEMPTS) {
            cancel();
        }
        return Attempt.WRONG;
    }
}
