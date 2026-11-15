package io.github.pix3lpirat3.mfbridge.core.record;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.nio.file.Path;

/**
 * A running replay of a recording. Its objects stay in the store after the last frame, so the
 * scene can be inspected, until {@link #stop()} closes the virtual connection; its sessions then
 * end like those of any closed connection.
 */
@ClientThread
public interface ReplayHandle {
    /** Returns the recording being replayed. */
    Path file();

    /** Returns the number of Node frames delivered so far. */
    int delivered();

    /** Returns the number of Node frames in the recording. */
    int total();

    /** Returns whether every frame was delivered. */
    boolean finished();

    /** Returns whether the replay was stopped. */
    boolean stopped();

    /** Closes the virtual connection. Idempotent. */
    void stop();
}
