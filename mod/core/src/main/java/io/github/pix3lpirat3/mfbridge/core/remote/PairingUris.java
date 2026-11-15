package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Pairing codes, pairing URIs and the default advertised host. */
@AnyThread
public final class PairingUris {
    /** Code length in characters. */
    public static final int CODE_LENGTH = 8;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private PairingUris() {}

    /** Returns a random code of {@link #CODE_LENGTH} base32 characters, 40 bits of entropy. */
    public static String newCode(SecureRandom random) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Formats {@code mfb://<host>:<port>?fp=<prefix>&code=<code>}; IPv6 literals get brackets.
     *
     * @param prefix the base32 fingerprint prefix from {@link InstanceIdentity#pairingPrefix()}
     */
    public static String uri(String host, int port, String prefix, String code) {
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return "mfb://" + h + ":" + port + "?fp=" + prefix + "&code=" + code;
    }

    /**
     * Normalizes a typed code for comparison: trimmed and upper-case.
     *
     * @return the normalized code
     */
    public static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Returns the default advertised host: {@code configured} when not blank; for a wildcard bind
     * the first site-local IPv4 address of an interface that is up, else any non-loopback IPv4
     * address, else {@code 127.0.0.1}; otherwise the bind address itself.
     */
    public static String defaultHost(@Nullable String configured, String bindAddress) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        if (!"0.0.0.0".equals(bindAddress) && !"::".equals(bindAddress) && !"[::]".equals(bindAddress)) {
            return bindAddress;
        }
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                        continue;
                    }
                    if (a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = a.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            return "127.0.0.1";
        }
        return fallback != null ? fallback : "127.0.0.1";
    }
}
