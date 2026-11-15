package io.github.pix3lpirat3.mfbridge.core.diag;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Finds a world-space box around a decoded object for the inspector highlight: the bounds of every
 * position ({@code Vec3d}) and block ({@code Vec3i}, covering the whole block) found in the value.
 * Kinds without positions, such as HUD panels, have no bounds. Positions relative to an entity or a
 * group are taken as given. Object traits use the box as the hit box of objects that are
 * not displays.
 */
@AnyThread
public final class ObjectBounds {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VALUES = 100_000;
    private static final Set<String> VECTORS = Set.of("size", "spread", "offset", "velocity", "direction", "scale");

    /**
     * An axis-aligned box in world coordinates, in blocks.
     *
     * @param minX smallest x
     * @param minY smallest y
     * @param minZ smallest z
     * @param maxX largest x
     * @param maxY largest y
     * @param maxZ largest z
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        /** Returns this box grown by {@code d} blocks on every side. */
        public Box inflate(double d) {
            return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
        }
    }

    private static final class Acc {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        int visited;

        void add(double x0, double y0, double z0, double x1, double y1, double z1) {
            if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(z0)) {
                return;
            }
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
            maxZ = Math.max(maxZ, z1);
        }
    }

    private ObjectBounds() {}

    /** Returns the bounds of the positions in {@code decoded}, or null when it has none. */
    public static @Nullable Box of(@Nullable Object decoded) {
        Acc acc = new Acc();
        scan(decoded, acc, 0);
        if (acc.minX > acc.maxX) {
            return null;
        }
        return new Box(acc.minX, acc.minY, acc.minZ, acc.maxX, acc.maxY, acc.maxZ);
    }

    private static void scan(@Nullable Object v, Acc acc, int depth) {
        if (v == null || depth > MAX_DEPTH || acc.visited++ > MAX_VALUES) {
            return;
        }
        switch (v) {
            case BoxData b -> {
                Vec3d m = b.min();
                Vec3d sz = b.size();
                acc.add(Math.min(m.x(), m.x() + sz.x()), Math.min(m.y(), m.y() + sz.y()), Math.min(m.z(), m.z() + sz.z()),
                        Math.max(m.x(), m.x() + sz.x()), Math.max(m.y(), m.y() + sz.y()), Math.max(m.z(), m.z() + sz.z()));
            }
            case PathData p -> {
                double[] xyz = p.points();
                for (int i = 0; i + 2 < xyz.length; i += 3) {
                    acc.add(xyz[i], xyz[i + 1], xyz[i + 2], xyz[i], xyz[i + 1], xyz[i + 2]);
                }
            }
            case BlockHighlightData b -> {
                int[] xyz = b.positions() == null ? new int[0] : b.positions();
                for (int i = 0; i + 2 < xyz.length; i += 3) {
                    acc.add(xyz[i], xyz[i + 1], xyz[i + 2], xyz[i] + 1, xyz[i + 1] + 1, xyz[i + 2] + 1);
                }
            }
            case Vec3d p -> acc.add(p.x(), p.y(), p.z(), p.x(), p.y(), p.z());
            case Vec3i b -> acc.add(b.x(), b.y(), b.z(), b.x() + 1, b.y() + 1, b.z() + 1);
            case Optional<?> o -> scan(o.orElse(null), acc, depth);
            case Collection<?> c -> {
                for (Object item : c) {
                    scan(item, acc, depth + 1);
                }
            }
            case Map<?, ?> m -> {
                for (Object item : m.values()) {
                    scan(item, acc, depth + 1);
                }
            }
            case Record r -> {
                for (RecordComponent c : r.getClass().getRecordComponents()) {
                    Class<?> t = c.getType();
                    // Extents and spreads are vectors, not positions.
                    if (t.isPrimitive() || t == String.class || t.isArray() || t.isEnum() || VECTORS.contains(c.getName())) {
                        continue;
                    }
                    Object child;
                    try {
                        c.getAccessor().setAccessible(true);
                        child = c.getAccessor().invoke(r);
                    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                        // An unreadable component contributes nothing to a highlight hint.
                        child = null;
                    }
                    scan(child, acc, depth + 1);
                }
            }
            default -> {}
        }
    }
}
