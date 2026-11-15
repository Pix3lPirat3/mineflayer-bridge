package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.List;

/**
 * What the inspector reads from the core. The list methods build fresh
 * immutable lists on each call, so the UI should call them at most a few times per second.
 */
@ClientThread
public interface InspectorControls {
    /** Returns every open connection, in accept order. */
    List<ConnectionInfo> connections();

    /** Returns every live or detached session, sorted by owner. */
    List<SessionInfo> sessions();

    /** Returns every stored object, sorted by key. */
    List<ObjectInfo> objects();

    /** Returns the traffic log; turn capture on while the Traffic tab is open. */
    TrafficLog traffic();

    /** Returns the per-tick and per-frame cost metrics. */
    BridgeMetrics metrics();
}
