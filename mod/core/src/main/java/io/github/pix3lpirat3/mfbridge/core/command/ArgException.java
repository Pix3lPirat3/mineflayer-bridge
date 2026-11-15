package io.github.pix3lpirat3.mfbridge.core.command;

/**
 * Input that does not fit its argument, with the range of the offending text so the UI can
 * underline it in red with the reason.
 */
public final class ArgException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Offset of the first offending char. */
    private final int start;

    /** Offset just past the last offending char. */
    private final int end;

    /**
     * Creates the exception.
     *
     * @param message the reason, fit to show the player
     * @param start offset of the first offending char in the text that was parsed
     * @param end offset just past the last offending char; at least {@code start}
     */
    public ArgException(String message, int start, int end) {
        super(message);
        this.start = start;
        this.end = Math.max(start, end);
    }

    /** Returns the offset of the first offending char. */
    public int start() {
        return start;
    }

    /** Returns the offset just past the last offending char. */
    public int end() {
        return end;
    }

    /**
     * Returns a copy whose range is moved by {@code delta} chars.
     *
     * @param delta chars to add to both ends
     * @return the moved exception
     */
    public ArgException shifted(int delta) {
        return new ArgException(getMessage(), start + delta, end + delta);
    }

    @Override
    public String getMessage() {
        String m = super.getMessage();
        return m == null ? "" : m;
    }
}
