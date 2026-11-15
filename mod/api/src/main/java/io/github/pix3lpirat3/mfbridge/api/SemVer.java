package io.github.pix3lpirat3.mfbridge.api;

/**
 * Semantic version {@code major.minor.patch} with an optional suffix that {@link #compareTo(SemVer)}
 * ignores.
 *
 * @param major major version, non-negative
 * @param minor minor version, non-negative
 * @param patch patch version, non-negative
 * @param suffix text after the patch number including its leading {@code -} or {@code +}, or empty
 */
public record SemVer(int major, int minor, int patch, String suffix) implements Comparable<SemVer> {
    /** Rejects negative components. */
    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("negative version component");
        }
    }

    /**
     * Parses {@code 1.2.3}, {@code 1.2.3-beta.1} or {@code 1.2.3+build}.
     *
     * @throws IllegalArgumentException when the text is not a semantic version
     */
    public static SemVer parse(String text) {
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '+') {
                end = i;
                break;
            }
        }
        String[] parts = text.substring(0, end).split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a semantic version: '" + text + "'");
        }
        try {
            return new SemVer(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    text.substring(end));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a semantic version: '" + text + "'", e);
        }
    }

    /** Orders by major, minor, then patch. */
    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    /** Returns the version text, including any suffix. */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch + suffix;
    }

    /**
     * Returns whether this version is in a range, with the plugin's range syntax: space-separated
     * comparators that must all hold, each {@code *}, an exact version, a partial version such as
     * {@code 1} or {@code 1.2}, or one prefixed with {@code ^}, {@code ~}, {@code >=}, {@code <=},
     * {@code >}, {@code <} or {@code =}.
     *
     * @param range the range, such as {@code ^1.2} or {@code >=1.0.0 <2.0.0}
     * @return false for a malformed range
     */
    public boolean satisfies(String range) {
        for (String part : range.trim().split("\\s+")) {
            if (!satisfiesOne(part)) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfiesOne(String range) {
        if (range.isEmpty() || range.equals("*") || range.equals("x")) {
            return true;
        }
        int opEnd = 0;
        while (opEnd < range.length() && "^~<>=".indexOf(range.charAt(opEnd)) >= 0) {
            opEnd++;
        }
        String op = range.substring(0, opEnd);
        String[] parts = range.substring(opEnd).split("[-+]", 2)[0].split("\\.");
        int given = 0;
        int[] t = new int[3];
        for (int i = 0; i < parts.length && i < 3; i++) {
            if (parts[i].isEmpty() || parts[i].equals("x") || parts[i].equals("*")) {
                break;
            }
            try {
                t[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            given++;
        }
        if (given == 0) {
            return op.isEmpty();
        }
        SemVer target = new SemVer(t[0], t[1], t[2], "");
        int c = compareTo(target);
        return switch (op) {
            case "^" -> c >= 0 && (target.major > 0 || given == 1 ? major == target.major
                    : target.minor > 0 || given == 2 ? major == 0 && minor == target.minor
                    : major == 0 && minor == 0 && patch == target.patch);
            case "~" -> c >= 0 && (given == 1 ? major == target.major : major == target.major && minor == target.minor);
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            case "", "=" -> given == 1 ? major == target.major : given == 2 ? major == target.major && minor == target.minor : c == 0;
            default -> false;
        };
    }
}
