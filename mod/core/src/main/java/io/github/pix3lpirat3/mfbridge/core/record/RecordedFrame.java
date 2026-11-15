package io.github.pix3lpirat3.mfbridge.core.record;

/**
 * One record of a recording.
 *
 * @param direction {@link RecordingFormat#FROM_MOD} or {@link RecordingFormat#FROM_NODE}
 * @param epochMs capture time, epoch milliseconds
 * @param bytes the whole frame, envelope header plus payload, as on the wire
 */
public record RecordedFrame(int direction, long epochMs, byte[] bytes) {}
