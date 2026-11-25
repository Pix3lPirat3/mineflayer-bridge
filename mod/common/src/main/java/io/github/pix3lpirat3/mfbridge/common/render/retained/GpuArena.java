package io.github.pix3lpirat3.mfbridge.common.render.retained;

import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import io.github.pix3lpirat3.mfbridge.core.retained.RangeAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Vertex memory of the retained tier: section meshes live in ranges of a few large GPU
 * buffers, never in per-section buffer objects. Each arena holds one vertex size, so a mesh starts
 * at a whole vertex and draws by its first vertex, which every game version's render pass takes.
 * Arenas of {@link #ARENA_BYTES} are created on demand; a mesh larger than that gets an arena of
 * its own size. Empty arenas beyond the first of a size are released. Render thread only.
 */
final class GpuArena implements AutoCloseable {
    /** Size of one arena buffer, in bytes. */
    static final long ARENA_BYTES = 8L << 20;

    /** Allocation granularity, in vertices. */
    private static final long ALIGNMENT = 16;

    /** A live range of one arena. */
    record Slot(int arena, int firstVertex, int bytes) {}

    private static final class Arena {
        final Object buffer;
        final int stride;
        final RangeAllocator allocator;
        final boolean first;

        Arena(final Object buffer, final int stride, final RangeAllocator allocator, final boolean first) {
            this.buffer = buffer;
            this.stride = stride;
            this.allocator = allocator;
            this.first = first;
        }
    }

    private final Gpu gpu;
    private final List<@Nullable Arena> arenas = new ArrayList<>();
    private long usedBytes;

    GpuArena(final Gpu gpu) {
        this.gpu = gpu;
    }

    /** Returns the bytes allocated across all arenas. */
    long usedBytes() {
        return usedBytes;
    }

    /**
     * Uploads vertex bytes into a new range.
     *
     * @param data bytes from position to limit; not retained
     * @param vertices how many vertices the bytes hold
     * @return the slot, to draw from and to free
     */
    Slot upload(final ByteBuffer data, final int vertices) {
        final int bytes = data.remaining();
        final int stride = bytes / vertices;
        boolean firstOfSize = true;
        for (int i = 0; i < arenas.size(); i++) {
            final Arena a = arenas.get(i);
            if (a == null || a.stride != stride) {
                continue;
            }
            firstOfSize = false;
            final long first = a.allocator.allocate(vertices);
            if (first != RangeAllocator.NONE) {
                return write(i, a, first, data, bytes);
            }
        }
        final long count = Math.max(ARENA_BYTES / stride, (vertices + ALIGNMENT - 1) & -ALIGNMENT);
        final Arena a = new Arena(gpu.createVertexBuffer("Bridge retained arena", count * stride), stride,
                new RangeAllocator(count, ALIGNMENT), firstOfSize);
        int index = arenas.indexOf(null);
        if (index < 0) {
            index = arenas.size();
            arenas.add(a);
        } else {
            arenas.set(index, a);
        }
        return write(index, a, a.allocator.allocate(vertices), data, bytes);
    }

    private Slot write(final int index, final Arena a, final long first, final ByteBuffer data, final int bytes) {
        gpu.write(a.buffer, first * a.stride, data);
        usedBytes += bytes;
        return new Slot(index, (int) first, bytes);
    }

    /** Returns the buffer a slot lives in, for {@link Gpu.Pass#draw}. */
    Object buffer(final Slot slot) {
        final Arena a = arenas.get(slot.arena());
        if (a == null) {
            throw new IllegalStateException("slot of a released arena");
        }
        return a.buffer;
    }

    /** Frees a slot; an arena other than the first of its size is released once empty. */
    void free(final Slot slot) {
        final Arena a = arenas.get(slot.arena());
        if (a == null) {
            return;
        }
        a.allocator.free(slot.firstVertex());
        usedBytes -= slot.bytes();
        if (!a.first && a.allocator.allocations() == 0) {
            gpu.close(a.buffer);
            arenas.set(slot.arena(), null);
        }
    }

    @Override
    public void close() {
        for (final Arena a : arenas) {
            if (a != null) {
                gpu.close(a.buffer);
            }
        }
        arenas.clear();
        usedBytes = 0;
    }
}
