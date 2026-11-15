package io.github.pix3lpirat3.mfbridge.core.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable set of granted permissions. Thread-safe.
 *
 * @param granted the granted permissions; copied, unmodifiable
 */
public record PermissionSet(Set<Permission> granted) {
    /** Copies the set. */
    public PermissionSet {
        EnumSet<Permission> copy = EnumSet.noneOf(Permission.class);
        copy.addAll(granted);
        granted = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns the default permissions.
     *
     * @param local whether the device authenticated with the local token
     */
    public static PermissionSet defaults(boolean local) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (p.defaultFor(local)) {
                set.add(p);
            }
        }
        return new PermissionSet(set);
    }

    /**
     * Applies stored overrides on top of this set. Unknown names are ignored, so files written by a
     * newer version still load.
     *
     * @param overrides wire name to granted
     */
    public PermissionSet with(Map<String, Boolean> overrides) {
        EnumSet<Permission> set = granted.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(granted);
        for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
            Permission p = Permission.byKey(e.getKey());
            if (p == null || e.getValue() == null) {
                continue;
            }
            if (e.getValue()) {
                set.add(p);
            } else {
                set.remove(p);
            }
        }
        return new PermissionSet(set);
    }

    /** Returns whether a permission is granted. */
    public boolean has(Permission p) {
        return granted.contains(p);
    }

    /** Returns the wire names of the granted permissions, in declaration order. */
    public List<String> keys() {
        List<String> out = new ArrayList<>(granted.size());
        for (Permission p : granted) {
            out.add(p.key());
        }
        return List.copyOf(out);
    }
}
