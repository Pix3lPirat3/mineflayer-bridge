package io.github.pix3lpirat3.mfbridge.core.world;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server address normalization. */
@AnyThread
public final class ServerKeys {
    /** Default Minecraft port, written out when an address has none. */
    public static final int DEFAULT_PORT = 25565;

    /** Store key for objects that belong to no server. */
    public static final String GLOBAL = "global";

    private ServerKeys() {}

    /**
     * Normalizes a server address: trims, lowercases the host and appends {@code :25565} when no port
     * is given. Bare IPv6 literals are bracketed. SRV resolution is the caller's business.
     */
    public static String normalize(String server) {
        String trimmed = server.trim();
        if (trimmed.regionMatches(true, 0, SINGLEPLAYER, 0, SINGLEPLAYER.length())) {
            // A save folder name; its case is kept, since two folders may differ only by case.
            return SINGLEPLAYER + trimmed.substring(SINGLEPLAYER.length());
        }
        String s = trimmed.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return s;
        }
        String host;
        String port;
        if (s.charAt(0) == '[') {
            int end = s.indexOf(']');
            if (end < 0) {
                return s;
            }
            host = s.substring(1, end);
            port = end + 1 < s.length() && s.charAt(end + 1) == ':' ? s.substring(end + 2) : "";
        } else {
            int colon = s.indexOf(':');
            if (colon >= 0 && s.indexOf(':', colon + 1) >= 0) {
                host = s;
                port = "";
            } else if (colon >= 0) {
                host = s.substring(0, colon);
                port = s.substring(colon + 1);
            } else {
                host = s;
                port = "";
            }
        }
        host = canonicalHost(host);
        String p = port.isEmpty() ? String.valueOf(DEFAULT_PORT) : port;
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + p : host + ":" + p;
    }

    /** Starts the key of a singleplayer world, followed by the name of its save folder. */
    public static final String SINGLEPLAYER = "singleplayer:";

    private static final String LOCALHOST = "localhost";
    private static final Map<String, String> CANONICAL_HOSTS = new ConcurrentHashMap<>();

    /**
     * Maps every name for this machine to {@code localhost}: loopback literals, the wildcard address and
     * IP literals bound to a local interface. Bots usually join a local server through one of these
     * while the player's client reports another. Only IP literals are inspected, so this never does DNS.
     */
    static String canonicalHost(String host) {
        if (host.equals(LOCALHOST)) {
            return host;
        }
        if (!isIpLiteral(host)) {
            return host;
        }
        return CANONICAL_HOSTS.computeIfAbsent(host, h -> {
            try {
                InetAddress address = InetAddress.getByName(h);
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                        || NetworkInterface.getByInetAddress(address) != null) {
                    return LOCALHOST;
                }
            } catch (IOException e) {
                // Not resolvable as a literal or interfaces unavailable: keep the name as given.
            }
            return h;
        });
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return !host.isEmpty();
    }

    /** Longest readable part of a store directory name. */
    private static final int MAX_READABLE = 80;

    /**
     * Turns a normalized server key into a safe directory name that no other key shares: a readable
     * part, where characters outside {@code [a-z0-9._-]} become {@code _}, then {@code -} and the
     * first 16 hex digits of the key's SHA-256.
     */
    public static String directoryName(String serverKey) {
        StringBuilder out = new StringBuilder(MAX_READABLE + 17);
        for (int i = 0; i < serverKey.length() && out.length() < MAX_READABLE; i++) {
            char c = serverKey.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            out.append(ok ? c : '_');
        }
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(serverKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        out.append('-');
        for (int i = 0; i < 8; i++) {
            out.append(Character.forDigit((hash[i] >> 4) & 0xF, 16)).append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return out.toString();
    }
}
