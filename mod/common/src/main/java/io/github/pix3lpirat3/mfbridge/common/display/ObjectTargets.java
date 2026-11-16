package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Objects other than displays that take part in the bridge raycast: boxes,
 * labels, waypoints, zones, points, routes, paths and retained-tier objects that are interactive,
 * editable, have bound actions or are shown only while looked at. Their hit box is the bounds of
 * their positions ({@link ObjectBounds}), or for retained-tier objects where their blocks or cells
 * actually are, at least {@link #MIN_EXTENT} wide on each axis. Rebuilt when the snapshot or the
 * retained bounds change. Client thread only.
 */
final class ObjectTargets {
    /** Smallest hit box edge, blocks, so labels and points can be aimed at. */
    static final double MIN_EXTENT = 0.5d;

    /**
     * One target.
     *
     * @param entry the object
     * @param minX hit box corner, world blocks
     * @param minY hit box corner
     * @param minZ hit box corner
     * @param maxX hit box corner
     * @param maxY hit box corner
     * @param maxZ hit box corner
     */
    record Target(RenderEntry entry, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double cx() {
            return (minX + maxX) / 2d;
        }

        double cy() {
            return (minY + maxY) / 2d;
        }

        double cz() {
            return (minZ + maxZ) / 2d;
        }

        /** Returns the squared distance from a point to the hit box centre, blocks squared. */
        double distanceSq(final double x, final double y, final double z) {
            final double dx = cx() - x;
            final double dy = cy() - y;
            final double dz = cz() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private long version = -1L;
    private long boundsVersion = -1L;
    private List<Target> all = List.of();
    private final Map<String, Target> byKey = new HashMap<>();

    /**
     * Follows the snapshot.
     *
     * @param snapshot the core's snapshot
     * @param retained the retained renderer, for the real extent of its objects, or null
     * @return the targets in key order; the same list until the snapshot or the retained bounds change
     */
    List<Target> sync(final RenderSnapshot snapshot, final @Nullable RetainedRenderer retained) {
        final long bv = retained == null ? 0L : retained.boundsVersion();
        if (snapshot.version() == version && bv == boundsVersion) {
            return all;
        }
        if (bv == boundsVersion) {
            // Most snapshot changes touch no target; then the list stands.
            final RenderSnapshot.Change change = snapshot.changesSince(version);
            if (change != null && !touches(change)) {
                version = snapshot.version();
                return all;
            }
        }
        version = snapshot.version();
        boundsVersion = bv;
        byKey.clear();
        final List<Target> out = new ArrayList<>();
        for (final RenderEntry e : snapshot.entries()) {
            if (!candidate(e)) {
                continue;
            }
            final ObjectBounds.Box placed = retained == null ? null : retained.bounds(e.key());
            final ObjectBounds.Box b = placed != null ? placed : ObjectBounds.of(e.data());
            if (b == null) {
                continue;
            }
            final double px = Math.max(0d, MIN_EXTENT - (b.maxX() - b.minX())) / 2d;
            final double py = Math.max(0d, MIN_EXTENT - (b.maxY() - b.minY())) / 2d;
            final double pz = Math.max(0d, MIN_EXTENT - (b.maxZ() - b.minZ())) / 2d;
            final Target t = new Target(e, b.minX() - px, b.minY() - py, b.minZ() - pz, b.maxX() + px, b.maxY() + py, b.maxZ() + pz);
            out.add(t);
            byKey.put(e.key(), t);
        }
        all = List.copyOf(out);
        return all;
    }

    private static boolean candidate(final RenderEntry e) {
        return DisplayNode.kindOf(e.data()) == null
                && (Traits.targetable(e.traits()) || Traits.perFrame(Traits.visibleWhen(e.traits())));
    }

    private boolean touches(final RenderSnapshot.Change change) {
        for (final RenderEntry e : change.removed()) {
            if (byKey.containsKey(e.key())) {
                return true;
            }
        }
        for (final RenderEntry e : change.upserted()) {
            if (byKey.containsKey(e.key()) || candidate(e)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the target with a key, or null. */
    @Nullable Target get(final String key) {
        return byKey.get(key);
    }

    /** Forgets everything, for example when the core stops. */
    void clear() {
        version = -1L;
        boundsVersion = -1L;
        all = List.of();
        byKey.clear();
    }
}
