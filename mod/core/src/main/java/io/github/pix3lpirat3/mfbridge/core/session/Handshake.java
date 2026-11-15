package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ApprovalDecision;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.core.BridgeConfig;
import io.github.pix3lpirat3.mfbridge.core.DeviceRequest;
import io.github.pix3lpirat3.mfbridge.core.HostEnvironment;
import io.github.pix3lpirat3.mfbridge.core.auth.DeviceRegistry;
import io.github.pix3lpirat3.mfbridge.core.auth.Proofs;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.remote.DevicePaired;
import io.github.pix3lpirat3.mfbridge.core.remote.InstanceIdentity;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.core.remote.Quotas;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthChallenge;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.Budget;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Hello;
import io.github.pix3lpirat3.mfbridge.protocol.core.Limits;
import io.github.pix3lpirat3.mfbridge.protocol.core.PairResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import io.github.pix3lpirat3.mfbridge.protocol.core.Pong;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * A connection's way to {@code welcome}: hello, the auth challenge, local approval, device proofs
 * and pairing.
 */
@ClientThread
final class Handshake {
    static final SecureRandom RANDOM = new SecureRandom();
    private static final long U53_MASK = (1L << 53) - 1;
    private static final int MAX_DEVICE_ID = 128;
    private static final int SECRET_BYTES = 32;
    /** Put before the device id in the proof the mod sends back in {@code welcome}. */
    static final String MOD_PROOF_PREFIX = "mod:";
    private final byte[] localToken;
    private boolean askLocalApproval;

    private final BridgeConfig config;
    private final HostEnvironment host;
    private final Logger log;
    private final BridgeClock clock;
    private final ExtensionHost extensions;
    private final EventBus events;
    private final DeviceRegistry devices;
    private final InstanceIdentity identity;
    private final Devices access;
    private final Pairing pairing;
    private final SessionTable table;
    private final Outbox outbox;

    Handshake(BridgeConfig config, HostEnvironment host, Logger log, BridgeClock clock, ExtensionHost extensions,
            EventBus events, DeviceRegistry devices, InstanceIdentity identity, Devices access, Pairing pairing,
            SessionTable table, Outbox outbox,
            byte[] localToken) {
        this.localToken = localToken.clone();
        this.askLocalApproval = config.askLocalApproval();
        this.config = config;
        this.host = host;
        this.log = log;
        this.clock = clock;
        this.extensions = extensions;
        this.events = events;
        this.devices = devices;
        this.identity = identity;
        this.access = access;
        this.pairing = pairing;
        this.table = table;
        this.outbox = outbox;
    }

    /**
     * Sets whether new local devices ask the player before they connect; when
     * off they are approved and remembered automatically.
     */
    void setAskLocalApproval(boolean ask) {
        askLocalApproval = ask;
    }

    void pong(Connection c, Pong p) {
        long rtt = clock.currentTimeMillis() - p.time();
        // Plugins answer their own pings with any time value; only plausible round trips count.
        if (rtt >= 0 && rtt < 120_000) {
            c.rttMs = rtt;
        }
    }

    void dispatchHandshake(Connection c, Envelope env, byte[] payload, long now) {
        int type = env.type();
        if (c.state == Connection.State.CLOSING) {
            return;
        }
        if (type == CoreMessages.PING.id()) {
            Ping p = Encoding.decode(Ping.CODEC, payload);
            outbox.sendControl(c, env.session(), null, CoreMessages.PONG, new Pong(p.time()), 0, Envelope.NONE);
            return;
        }
        if (type == CoreMessages.PONG.id()) {
            pong(c, Encoding.decode(Pong.CODEC, payload));
            return;
        }
        if (type == CoreMessages.GOODBYE.id()) {
            c.net.abort();
            return;
        }
        if (env.session() == 0 && type == CoreMessages.HELLO.id() && c.state == Connection.State.AWAIT_HELLO) {
            onHello(c, Encoding.decode(Hello.CODEC, payload));
        } else if (env.session() == 0
                && type == CoreMessages.AUTH_RESPONSE.id()
                && c.state == Connection.State.AWAIT_AUTH) {
            onAuthResponse(c, Encoding.decode(AuthResponse.CODEC, payload), now);
        } else {
            // Nothing but ping, pong and goodbye before welcome.
            outbox.goodbye(c, "invalid_message", "message type 0x" + Integer.toHexString(type) + " before welcome", false);
        }
    }

    private void onHello(Connection c, Hello h) {
        if (h.protocolMajor() != CoreMessages.PROTOCOL_MAJOR) {
            outbox.goodbye(c, "protocol_mismatch", "mod speaks protocol " + CoreMessages.PROTOCOL_MAJOR + "."
                    + CoreMessages.PROTOCOL_MINOR + ", plugin speaks " + h.protocolMajor() + "." + h.protocolMinor(), false);
            return;
        }
        c.hello = h;
        if (c.replay) {
            // A replay is authenticated as an allowed-once local device.
            String id = "replay" + c.net.id();
            access.allowOnce(id, h.deviceName() + " (replay)", c.net.remoteAddress(), clock.currentTimeMillis());
            authenticated(c, id);
            return;
        }
        c.connectionId = RANDOM.nextLong() & U53_MASK;
        c.nonce = new byte[32];
        RANDOM.nextBytes(c.nonce);
        // Local only for loopback peers, device always, pair while a code is
        // active; pairing proves the code in clear, so it is only offered over TLS.
        List<AuthMethod> offered = new ArrayList<>(3);
        if (c.net.loopback()) {
            offered.add(AuthMethod.LOCAL);
        }
        offered.add(AuthMethod.DEVICE);
        if (c.net.secure() && pairing.active() != null) {
            offered.add(AuthMethod.PAIR);
        }
        c.offered = List.copyOf(offered);
        outbox.sendControl(c, 0, null, CoreMessages.AUTH_CHALLENGE,
                new AuthChallenge(c.connectionId, c.nonce, c.offered), 0, Envelope.NONE);
        c.state = Connection.State.AWAIT_AUTH;
    }

    private void onAuthResponse(Connection c, AuthResponse r, long now) {
        String id = r.deviceId();
        // Pair answers go to pair() even when not offered, so the plugin learns why it was refused.
        boolean offered = c.offered.contains(r.method()) || r.method() == AuthMethod.PAIR;
        if (!offered || id.isEmpty() || id.length() > MAX_DEVICE_ID) {
            unauthorized(c);
            return;
        }
        c.peerNonce = r.nonce();
        switch (r.method()) {
            case LOCAL -> {
                if (!Proofs.verify(localToken, c.nonce, c.connectionId, id, r.proof())) {
                    unauthorized(c);
                    return;
                }
                c.proofKey = localToken;
                // The token is proven; only the player's approval may still be missing.
                c.net.authenticated();
                DeviceRegistry.Device d = devices.get(id);
                if (d != null && d.local()) {
                    authenticated(c, id);
                    return;
                }
                c.deviceId = id;
                if (!askLocalApproval) {
                    // A proven local token is approved without asking.
                    rememberLocal(c, id);
                    return;
                }
                c.state = Connection.State.AWAIT_APPROVAL;
                try {
                    c.approval = host.approver().requestApproval(
                            new DeviceRequest(id, c.deviceName(), c.net.remoteAddress(), "local"));
                } catch (RuntimeException e) {
                    log.warn("Device approver failed: {}", e.toString());
                    unauthorized(c);
                }
            }
            case DEVICE -> {
                DeviceRegistry.Device d = devices.get(id);
                String secret = d == null || d.local() ? null : d.secret();
                byte[] key;
                try {
                    key = secret == null ? null : Encoding.fromBase64Url(secret);
                } catch (IllegalArgumentException e) {
                    key = null;
                }
                if (key == null || !Proofs.verify(key, c.nonce, c.connectionId, id, r.proof())) {
                    unauthorized(c);
                    return;
                }
                c.proofKey = key;
                authenticated(c, id);
            }
            case PAIR -> pair(c, r);
        }
    }

    /** A correct code over TLS issues a credential, then {@code welcome}. */
    void pair(Connection c, AuthResponse r) {
        if (!c.net.secure()) {
            outbox.goodbye(c, "unauthorized", "pairing requires TLS", false);
            return;
        }
        // Readable causes match what the Node plugin shows its user.
        String refused = switch (pairing.attempt(r.proof())) {
            case ACCEPTED -> null;
            case INACTIVE -> "no active code";
            case EXPIRED -> "code expired";
            case WRONG -> "code refused";
        };
        if (refused != null) {
            outbox.goodbye(c, "unauthorized", refused, false);
            return;
        }
        String id = r.deviceId();
        if (devices.get(id) != null) {
            // Never overwrite another device's credential; the plugin stores whatever id is issued.
            id = UUID.randomUUID().toString();
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        long wall = clock.currentTimeMillis();
        access.put(new DeviceRegistry.Device(id, c.deviceName(), DeviceRegistry.PAIRED, Encoding.base64Url(secret),
                wall, wall, c.net.remoteAddress()));
        outbox.sendControl(c, 0, null, CoreMessages.PAIR_RESULT, new PairResult(id, secret, identity.fingerprint()), 0,
                Envelope.NONE);
        c.proofKey = secret;
        authenticated(c, id);
        if (events.hasListeners(DevicePaired.TYPE)) {
            events.post(DevicePaired.TYPE, new DevicePaired(id, c.deviceName(), c.net.remoteAddress()));
        }
    }

    /** Lets in or turns away the connections whose approval the player has answered. */
    void pollApprovals() {
        for (Connection c : table.connections) {
            CompletableFuture<ApprovalDecision> f = c.approval;
            if (c.state != Connection.State.AWAIT_APPROVAL || f == null || !f.isDone()) {
                continue;
            }
            c.approval = null;
            ApprovalDecision d = f.isCompletedExceptionally() || f.isCancelled() ? ApprovalDecision.DENY : f.join();
            String id = Objects.requireNonNull(c.deviceId);
            switch (d) {
                case ALLOW -> rememberLocal(c, id);
                case ALLOW_ONCE -> {
                    access.allowOnce(id, c.deviceName(), c.net.remoteAddress(), clock.currentTimeMillis());
                    authenticated(c, id);
                }
                case DENY -> unauthorized(c);
            }
        }
    }

    private void rememberLocal(Connection c, String id) {
        long wall = clock.currentTimeMillis();
        access.put(new DeviceRegistry.Device(id, c.deviceName(), DeviceRegistry.LOCAL, null, wall, wall,
                c.net.remoteAddress()));
        authenticated(c, id);
    }

    private void unauthorized(Connection c) {
        outbox.goodbye(c, "unauthorized", "authentication failed", false);
    }

    void authenticated(Connection c, String deviceId) {
        Hello h = Objects.requireNonNull(c.hello);
        c.deviceId = deviceId;
        c.net.authenticated();
        // Proves the mod knows the secret too: the same proof over the plugin's nonce, for "mod:<device id>".
        byte[] key = c.proofKey;
        byte[] peerNonce = c.peerNonce;
        byte[] proof = key == null || peerNonce == null ? null
                : Proofs.proof(key, peerNonce, c.connectionId, MOD_PROOF_PREFIX + deviceId);
        c.proofKey = null;
        c.peerNonce = null;
        access.touch(deviceId, h.deviceName(), c.net.remoteAddress(), clock.currentTimeMillis());
        ConnRegistry reg = extensions.negotiate(h.messages(), h.kinds());
        c.registry = reg;
        Quotas q = access.quotas(deviceId);
        Welcome w = new Welcome(
                CoreMessages.PROTOCOL_MAJOR,
                CoreMessages.PROTOCOL_MINOR,
                host.modVersion(),
                host.gameVersion(),
                host.loader(),
                reg.entries(),
                extensions.refs(),
                limits(q),
                new Budget(q.get(Quota.MESSAGES_PER_SECOND), q.get(Quota.BYTES_PER_SECOND), q.get(Quota.BLOB_IN_FLIGHT)),
                access.deviceSet(deviceId).keys(),
                proof);
        outbox.sendControl(c, 0, null, CoreMessages.WELCOME, w, 0, Envelope.NONE);
        c.state = Connection.State.READY;
    }

    /** The device's effective values, as {@code welcome} advertises them. */
    Limits limits(Quotas q) {
        return new Limits(config.maxFrameBytes(), q.get(Quota.OBJECTS), q.get(Quota.BLOB_BYTES),
                (int) Math.min(Integer.MAX_VALUE, config.resumeWindowMs()), (int) Math.min(Integer.MAX_VALUE, config.heartbeatMs()))
                .withMaxDisplays(q.get(Quota.DISPLAYS))
                .withMaxEntityDisplays(q.get(Quota.ENTITY_DISPLAYS))
                .withMaxEmitters(q.get(Quota.EMITTERS))
                .withMaxSoundEmitters(q.get(Quota.SOUND_EMITTERS))
                .withMaxInteractive(q.get(Quota.INTERACTIVE))
                .withEffectsPerSecond(q.get(Quota.EFFECTS_PER_SECOND))
                .withBlobInFlightBytes(q.get(Quota.BLOB_IN_FLIGHT));
    }
}
