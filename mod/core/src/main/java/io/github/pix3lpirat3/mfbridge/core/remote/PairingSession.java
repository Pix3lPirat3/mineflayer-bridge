package io.github.pix3lpirat3.mfbridge.core.remote;

/**
 * An active pairing code: valid until {@code expiresAtMs} and for one
 * successful pairing.
 *
 * @param uri {@code mfb://<host>:<port>?fp=<prefix>&code=<code>}, to hand to the bot process
 * @param code the 8-character code, upper-case RFC 4648 base32 letters and digits
 * @param host the advertised host in the URI
 * @param port the advertised port in the URI
 * @param expiresAtMs wall-clock expiry in ms since the epoch
 */
public record PairingSession(String uri, String code, String host, int port, long expiresAtMs) {}
