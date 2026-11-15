package io.github.pix3lpirat3.mfbridge.core.retained;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit sub-allocator over one arena of bytes: section meshes live in
 * ranges of large GPU buffers, never in per-section buffer objects. Freed ranges coalesce with
 * free neighbours, so long-running use does not fragment into unusable slivers.
 *
 * <p>Not thread-safe: owned by the render thread.
 */
public final class RangeAllocator {
    /** Returned by {@link #allocate} when no free range is large enough. */
    public static final long NONE = -1;

    private final long capacity;
    private final long alignment;
    /** Free ranges by offset, values are lengths; never adjacent to each other. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private final Long2LongOpenHashMap used = new Long2LongOpenHashMap();
    private long usedBytes;

    /**
     * Creates an allocator.
     *
     * @param capacity arena size, in bytes; positive
     * @param alignment every offset and length is a multiple of this, in bytes; a positive power of two
     */
    public RangeAllocator(long capacity, long alignment) {
        if (capacity <= 0 || alignment <= 0 || Long.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("capacity must be positive and alignment a power of two");
        }
        this.capacity = capacity - capacity % alignment;
        this.alignment = alignment;
        this.free.put(0L, this.capacity);
        this.used.defaultReturnValue(-1);
    }

    /** Returns the arena size, in bytes. */
    public long capacity() {
        return capacity;
    }

    /** Returns the allocated bytes, alignment padding included. */
    public long usedBytes() {
        return usedBytes;
    }

    /** Returns the number of live allocations. */
    public int allocations() {
        return used.size();
    }

    /** Returns the number of free ranges; 1 when nothing is allocated. */
    public int freeRanges() {
        return free.size();
    }

    /** Returns the length of the largest free range, in bytes. */
    public long largestFree() {
        long best = 0;
        for (long len : free.values()) {
            best = Math.max(best, len);
        }
        return best;
    }

    /**
     * Allocates a range.
     *
     * @param size requested bytes; positive
     * @return the offset of the range, or {@link #NONE} when no free range fits
     */
    public long allocate(long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        long len = (size + alignment - 1) & -alignment;
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            long rangeLen = e.getValue();
            if (rangeLen < len) {
                continue;
            }
            long offset = e.getKey();
            free.remove(offset);
            if (rangeLen > len) {
                free.put(offset + len, rangeLen - len);
            }
            used.put(offset, len);
            usedBytes += len;
            return offset;
        }
        return NONE;
    }

    /**
     * Frees a range returned by {@link #allocate}.
     *
     * @throws IllegalArgumentException when the offset is not a live allocation
     */
    public void free(long offset) {
        long len = used.remove(offset);
        if (len < 0) {
            throw new IllegalArgumentException("not an allocation: " + offset);
        }
        usedBytes -= len;
        long start = offset;
        long end = offset + len;
        Map.Entry<Long, Long> before = free.floorEntry(offset);
        if (before != null && before.getKey() + before.getValue() == start) {
            start = before.getKey();
            free.remove(start);
        }
        Long afterLen = free.get(end);
        if (afterLen != null) {
            free.remove(end);
            end += afterLen;
        }
        free.put(start, end - start);
    }
}
