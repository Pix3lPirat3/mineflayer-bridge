package io.github.pix3lpirat3.mfbridge.core.remote;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Devices, their permissions and quotas. Changes are saved to
 * {@code devices.json} and take effect at once: permission changes are pushed to the device's open
 * connections as {@code permissions}, and revoking a permission removes the objects it gated from
 * the device's sessions. Quota changes are enforced at once and advertised in the next {@code
 * welcome}. Client thread only.
 */
@ClientThread
public interface DeviceControls {
    /** How long denials of one device are collected before they are asked as one batch, in ms. */
    long PROMPT_COLLECT_MS = 1_500;

    /** Returns every remembered device and every connected one, sorted by name. */
    List<DeviceInfo> list();

    /**
     * Returns one device.
     *
     * @return the device, or null when unknown
     */
    @Nullable DeviceInfo get(String deviceId);

    /**
     * Revokes a device: its connections get {@code goodbye{revoked, retry: false}}, its sessions end,
     * its credential is deleted and the bot identities it held are free again.
     *
     * @return whether the device was known
     */
    boolean revoke(String deviceId);

    /**
     * Returns the device a bot identity belongs to: the one that first opened a session with it.
     * Other devices are refused that identity with {@code identity_taken}.
     *
     * @param identity the bot identity key, as in {@code bot:<identity>/}
     * @return the device id, or null when no device holds it
     */
    @Nullable String botDevice(String identity);

    /**
     * Forgets which device a bot identity belongs to, so the next device that opens a session with
     * it gets it. Sessions that are open stay open.
     *
     * @param identity the bot identity key
     * @return whether a device held it
     */
    boolean forgetBot(String identity);

    /**
     * Sets the player's label for a device.
     *
     * @param label the label, or null or blank to show the name from {@code hello} again
     */
    void rename(String deviceId, @Nullable String label);

    /** Grants or denies a permission at device level, and clears a "never ask" mark for it. */
    void setPermission(String deviceId, Permission permission, boolean granted);

    /**
     * Sets or clears a per-bot override.
     *
     * @param identity the bot identity key, as in {@code bot:<identity>/}
     * @param granted the override, or null to follow the device-level set
     */
    void setBotPermission(String deviceId, String identity, Permission permission, @Nullable Boolean granted);

    /** Restores the default permissions and removes every per-bot override and mark. */
    void resetPermissions(String deviceId);

    /**
     * Sets a quota.
     *
     * @throws IllegalArgumentException for a negative value
     */
    void setQuota(String deviceId, Quota quota, int value);

    /** Restores the default quotas. */
    void resetQuotas(String deviceId);

    /**
     * Returns the next permission batch to show: the permissions one remote device was denied, once
     * {@link #PROMPT_COLLECT_MS} have passed since the first of them, minus those granted since.
     * Repeated calls return the same batch, growing while requests arrive, until it is answered.
     *
     * @return the batch, or null when none is ready
     */
    @Nullable PermissionBatch permissionBatch();

    /**
     * Records the answer to a permission batch. Allowed device permissions are granted at device
     * level and an allowed intercept cancel is saved for its bot; every other item of the batch is
     * denied: a device permission is marked "never ask" for that device, an intercept cancel is
     * refused for this run. Either way those items are not asked again.
     *
     * @param batch the batch from {@link #permissionBatch}
     * @param allowed the items the player kept checked
     */
    void answerPermissions(PermissionBatch batch, Collection<? extends PermissionBatch.Item> allowed);
}
