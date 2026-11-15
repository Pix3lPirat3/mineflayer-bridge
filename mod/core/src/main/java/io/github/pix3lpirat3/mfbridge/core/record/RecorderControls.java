package io.github.pix3lpirat3.mfbridge.core.record;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Recording and replay. Files go to {@code
 * <config>/mineflayer_bridge/recordings/}, named {@code <yyyyMMdd-HHmmss>-c<connection>.mfbrec},
 * one per connection. Writes happen on a background thread.
 */
@ClientThread
public interface RecorderControls {
    /** Returns the recordings directory. */
    Path directory();

    /** Returns whether a recording is running. */
    boolean recording();

    /**
     * Starts recording every open and every new connection. A connection that was already open
     * starts with a prelude of synthesized frames (its {@code hello}, and per session {@code
     * session.open}, {@code world.report} and the current objects as {@code obj.upsert}), so a replay
     * of the file reproduces its state. Does nothing when already recording.
     *
     * @throws IOException when the directory cannot be created
     */
    void start() throws IOException;

    /**
     * Stops recording and closes the files.
     *
     * @return the files written by this recording, sorted. Immutable.
     */
    List<Path> stop();

    /** Returns the {@code .mfbrec} files in the recordings directory, newest first. */
    List<Path> files();

    /**
     * Writes captured traffic entries to recording files, one per connection.
     *
     * @return the files written, sorted. Immutable.
     * @throws IOException when a file cannot be written
     */
    List<Path> export(List<TrafficEntry> entries) throws IOException;

    /**
     * Replays a recording's Node frames into a fresh virtual connection. The handshake is taken as
     * authenticated (as an allowed-once local device named after the file); sequence checks are
     * relaxed, and session ids are mapped from the recording to the replay.
     *
     * @param speed 1 for recorded timing, 2 for twice as fast; 0 or less delivers everything at once
     * @throws IOException when the file cannot be read or is not a recording
     */
    ReplayHandle replay(Path file, double speed) throws IOException;

    /** Returns the most recent replay, or null. */
    @Nullable ReplayHandle lastReplay();
}
