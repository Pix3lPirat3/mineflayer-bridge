package io.github.pix3lpirat3.mfbridge.core.remote;

import java.util.List;

/**
 * The permissions a remote device was denied within one collection window, asked for on a single
 * screen. Local devices never produce a batch.
 *
 * @param deviceId the device
 * @param deviceName the device's display name
 * @param items the requested permissions in request order, never empty; copied
 */
public record PermissionBatch(String deviceId, String deviceName, List<Item> items) {
    /** Copies the items. */
    public PermissionBatch {
        items = List.copyOf(items);
    }

    /** One requested permission. */
    public sealed interface Item permits Grant, InterceptCancel {
        /** Returns the wire name, for example {@code client.sample.position}. */
        String key();

        /** Returns a short label for the prompt. */
        String label();

        /** Returns the display name of the session that asked, untrusted. */
        String requestedBy();
    }

    /**
     * A device-level permission.
     *
     * @param permission the missing permission
     * @param requestedBy display name of the session that asked, untrusted
     */
    public record Grant(Permission permission, String requestedBy) implements Item {
        @Override
        public String key() {
            return permission.key();
        }

        @Override
        public String label() {
            return permission.label();
        }
    }

    /**
     * The {@code input.intercept} cancel mode for one bot, which is granted per bot.
     *
     * @param identity the bot identity key
     * @param requestedBy display name of the bot, untrusted
     * @param detail the input and target of the first cancel rule, for example {@code use on block}
     */
    public record InterceptCancel(String identity, String requestedBy, String detail) implements Item {
        @Override
        public String key() {
            return "input.intercept cancel";
        }

        @Override
        public String label() {
            return "Block interactions (" + detail + ")";
        }
    }
}
