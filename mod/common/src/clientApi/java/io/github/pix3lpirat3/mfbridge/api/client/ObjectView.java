package io.github.pix3lpirat3.mfbridge.api.client;

/**
 * One object the player currently sees.
 *
 * @param key the full object key, {@code <owner>/<extension>/<local id>}
 * @param owner the owner without a trailing slash, such as {@code bot:<identity>}
 * @param localId the id within owner and extension
 * @param data the decoded data; treat it as read-only
 * @param <T> the data type
 */
public record ObjectView<T>(String key, String owner, String localId, T data) {
}
