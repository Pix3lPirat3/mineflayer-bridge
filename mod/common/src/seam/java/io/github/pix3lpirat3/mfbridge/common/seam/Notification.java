package io.github.pix3lpirat3.mfbridge.common.seam;

/**
 * A short message shown to the player as a toast.
 *
 * @param title the first line, kept short
 * @param message the second line; may be empty
 * @param severity how prominent the notice is
 */
public record Notification(String title, String message, Severity severity) {

    /** How prominent a notification is. */
    public enum Severity {
        /** Neutral information. */
        INFO,
        /** Something needs attention but the bridge keeps working. */
        WARNING,
        /** Something failed, such as the endpoint failing to bind its port. */
        ERROR
    }
}
