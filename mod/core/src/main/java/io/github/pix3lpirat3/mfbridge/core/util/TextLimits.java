package io.github.pix3lpirat3.mfbridge.core.util;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;

/** Bounds on text a bot shows the player. */
@AnyThread
public final class TextLimits {
    /** Longest message shown, in characters. */
    public static final int MAX_CHARS = 4096;
    /** Most lines of one message shown. */
    public static final int MAX_LINES = 20;

    private TextLimits() {}

    /** Cuts a message to {@link #MAX_CHARS} characters and {@link #MAX_LINES} lines. */
    public static String clamp(String text) {
        String out = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        int lines = 1;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n' && ++lines > MAX_LINES) {
                return out.substring(0, i);
            }
        }
        return out;
    }
}
