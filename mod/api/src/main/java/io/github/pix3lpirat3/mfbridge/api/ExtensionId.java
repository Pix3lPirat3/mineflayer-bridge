package io.github.pix3lpirat3.mfbridge.api;

/**
 * Extension identifier {@code namespace:name}.
 *
 * @param namespace namespace such as {@code core}, {@code bridge} or a vendor name
 * @param name kebab-case name within the namespace
 */
public record ExtensionId(String namespace, String name) {
    /** Validates both parts: non-empty, and neither may contain {@code :} or {@code /}. */
    public ExtensionId {
        check(namespace, "namespace");
        check(name, "name");
    }

    private static void check(String part, String what) {
        if (part.isEmpty() || part.indexOf(':') >= 0 || part.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid extension " + what + ": '" + part + "'");
        }
    }

    /**
     * Parses {@code namespace:name}.
     *
     * @throws IllegalArgumentException when the text is not of that form
     */
    public static ExtensionId parse(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("extension id must be namespace:name, got '" + text + "'");
        }
        return new ExtensionId(text.substring(0, colon), text.substring(colon + 1));
    }

    /** Returns {@code namespace:name}. */
    @Override
    public String toString() {
        return namespace + ':' + name;
    }
}
