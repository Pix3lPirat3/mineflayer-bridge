package io.github.pix3lpirat3.mfbridge.core.remote;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Effective quota values of one device. Immutable and thread-safe.
 */
public final class Quotas {
    private final int[] values;

    private Quotas(int[] values) {
        this.values = values;
    }

    /**
     * Returns the defaults: the built-in values, with objects, blob size, blob bytes in flight and the
     * message and byte budgets taken from the bridge configuration.
     */
    public static Quotas defaults(int objects, int blobBytes, int blobInFlight, int messagesPerSecond, int bytesPerSecond) {
        int[] v = new int[Quota.values().length];
        for (Quota q : Quota.values()) {
            v[q.ordinal()] = q.specDefault();
        }
        v[Quota.OBJECTS.ordinal()] = objects;
        v[Quota.BLOB_BYTES.ordinal()] = blobBytes;
        v[Quota.BLOB_IN_FLIGHT.ordinal()] = blobInFlight;
        v[Quota.MESSAGES_PER_SECOND.ordinal()] = messagesPerSecond;
        v[Quota.BYTES_PER_SECOND.ordinal()] = bytesPerSecond;
        return new Quotas(v);
    }

    /** Returns the value of a quota. */
    public int get(Quota q) {
        return values[q.ordinal()];
    }

    /**
     * Applies stored overrides. Unknown keys and negative values are ignored.
     *
     * @param overrides storage key to value
     */
    public Quotas with(Map<String, Integer> overrides) {
        int[] v = values.clone();
        for (Map.Entry<String, Integer> e : overrides.entrySet()) {
            Quota q = Quota.byKey(e.getKey());
            Integer value = e.getValue();
            if (q != null && value != null && value >= 0) {
                v[q.ordinal()] = value;
            }
        }
        return new Quotas(v);
    }

    /** Returns every value by quota, in declaration order. */
    public Map<Quota, Integer> asMap() {
        Map<Quota, Integer> out = new EnumMap<>(Quota.class);
        for (Quota q : Quota.values()) {
            out.put(q, values[q.ordinal()]);
        }
        return out;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Quotas q && Arrays.equals(values, q.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Quotas" + asMap();
    }
}
