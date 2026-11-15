package io.github.pix3lpirat3.mfbridge.core.remote;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One device as the devices screen shows it.
 *
 * @param id device id
 * @param name the player's label when set, else the name from {@code hello}
 * @param reportedName the last display name from {@code hello}, untrusted
 * @param local whether it authenticates with the local token ({@code local}) rather than a paired
 *     secret ({@code device})
 * @param remembered whether it is stored in {@code devices.json}; a local device allowed once is not
 * @param firstSeenMs wall-clock ms of approval or pairing, or 0 when unknown
 * @param lastSeenMs wall-clock ms of the last successful authentication, or 0
 * @param lastAddress last peer address, or null
 * @param connections open connections
 * @param sessions display names of the live sessions on those connections
 * @param bots bot identities that belong to this device, were seen on it in this run or hold an
 *     override, sorted
 * @param permissions the device-level granted set
 * @param botOverrides per bot identity, permission to granted, overriding the device-level set
 * @param quotas effective quotas
 */
public record DeviceInfo(
        String id,
        String name,
        String reportedName,
        boolean local,
        boolean remembered,
        long firstSeenMs,
        long lastSeenMs,
        @Nullable String lastAddress,
        int connections,
        List<String> sessions,
        List<String> bots,
        PermissionSet permissions,
        Map<String, Map<Permission, Boolean>> botOverrides,
        Quotas quotas) {
    /** Copies the collections. */
    public DeviceInfo {
        sessions = List.copyOf(sessions);
        bots = List.copyOf(bots);
        botOverrides = Map.copyOf(botOverrides);
    }
}
