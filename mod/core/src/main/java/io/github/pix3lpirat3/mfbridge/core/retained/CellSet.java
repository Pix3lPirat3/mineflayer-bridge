package io.github.pix3lpirat3.mfbridge.core.retained;

import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

/**
 * A set of block cells from {@code x, y, z} triples (heatmap cells, highlight positions), bucketed
 * by world section. Immutable once built and safe to share between workers; building belongs on a
 * worker.
 */
public final class CellSet implements CellPredicate {
    private final int[] xyz;
    private final LongOpenHashSet cells;
    private final Long2ObjectOpenHashMap<int[]> bySection;

    private CellSet(int[] xyz, LongOpenHashSet cells, Long2ObjectOpenHashMap<int[]> bySection) {
        this.xyz = xyz;
        this.cells = cells;
        this.bySection = bySection;
    }

    /**
     * Builds a set. A trailing partial triple is ignored.
     *
     * @param xyz block coordinates as triples; kept by reference and never modified
     */
    public static CellSet of(int[] xyz) {
        int n = xyz.length / 3;
        LongOpenHashSet cells = new LongOpenHashSet(n);
        Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) {
            int x = xyz[i * 3];
            int y = xyz[i * 3 + 1];
            int z = xyz[i * 3 + 2];
            if (!cells.add(pack(x, y, z))) {
                continue;
            }
            buckets.computeIfAbsent(SectionKeys.ofBlock(x, y, z), k -> new IntArrayList()).add(i);
        }
        Long2ObjectOpenHashMap<int[]> bySection = new Long2ObjectOpenHashMap<>(buckets.size());
        for (Long2ObjectMap.Entry<IntArrayList> e : buckets.long2ObjectEntrySet()) {
            bySection.put(e.getLongKey(), e.getValue().toIntArray());
        }
        return new CellSet(xyz, cells, bySection);
    }

    /** Packs a block position: 26 bits x, 26 bits z, 12 bits y, like the game's block positions. */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    @Override
    public boolean test(int x, int y, int z) {
        return cells.contains(pack(x, y, z));
    }

    /** Returns the number of distinct cells. */
    public int size() {
        return cells.size();
    }

    /**
     * Returns the box around every cell, in world blocks, or null when the set is empty.
     *
     * @param cellSize the edge of one cell's cube, blocks
     */
    public ObjectBounds.@Nullable Box bounds(double cellSize) {
        if (cells.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 2 < xyz.length; i += 3) {
            minX = Math.min(minX, xyz[i]);
            minY = Math.min(minY, xyz[i + 1]);
            minZ = Math.min(minZ, xyz[i + 2]);
            maxX = Math.max(maxX, xyz[i]);
            maxY = Math.max(maxY, xyz[i + 1]);
            maxZ = Math.max(maxZ, xyz[i + 2]);
        }
        return new ObjectBounds.Box(minX, minY, minZ, maxX + cellSize, maxY + cellSize, maxZ + cellSize);
    }

    /** Returns the triples this set was built from. */
    public int[] xyz() {
        return xyz;
    }

    /** Returns, per section key, the triple indexes of the distinct cells in that section. */
    public Long2ObjectMap<int[]> sections() {
        return Long2ObjectMaps.unmodifiable(bySection);
    }
}
