package io.github.pix3lpirat3.mfbridge.common.render.retained;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import io.github.pix3lpirat3.mfbridge.core.retained.RangeAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Vertex memory of the retained tier: section meshes live in ranges of a few large GPU
 * buffers, never in per-section buffer objects. Arenas of {@link #ARENA_BYTES} are created on
 * demand; a mesh larger than that gets an arena of its own size. Empty arenas beyond the first are
 * released. Render thread only.
 */
final class GpuArena implements AutoCloseable {
    /** Size of one arena buffer, in bytes. */
    static final long ARENA_BYTES = 16L << 20;

    /** Allocation granularity, in bytes; meshes bind as slices, so offsets need no vertex alignment. */
    private static final long ALIGNMENT = 256;

    private static final int USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;

    /** A live range of one arena. */
    record Slot(int arena, long offset, int bytes) {}

    private static final class Arena {
        final GpuBuffer buffer;
        final RangeAllocator allocator;

        Arena(final GpuBuffer buffer, final RangeAllocator allocator) {
            this.buffer = buffer;
            this.allocator = allocator;
        }
    }

    private final List<@Nullable Arena> arenas = new ArrayList<>();
    private long usedBytes;

    /** Returns the bytes allocated across all arenas. */
    long usedBytes() {
        return usedBytes;
    }

    /**
     * Uploads vertex bytes into a new range.
     *
     * @param data bytes from position to limit; not retained
     * @return the slot, to draw from and to free
     */
    Slot upload(final ByteBuffer data) {
        final int bytes = data.remaining();
        for (int i = 0; i < arenas.size(); i++) {
            final Arena a = arenas.get(i);
            if (a == null) {
                continue;
            }
            final long offset = a.allocator.allocate(bytes);
            if (offset != RangeAllocator.NONE) {
                return write(i, a, offset, data, bytes);
            }
        }
        final long size = Math.max(ARENA_BYTES, (bytes + ALIGNMENT - 1) & -ALIGNMENT);
        final GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Bridge retained arena", USAGE, size);
        final Arena a = new Arena(buffer, new RangeAllocator(size, ALIGNMENT));
        int index = arenas.indexOf(null);
        if (index < 0) {
            index = arenas.size();
            arenas.add(a);
        } else {
            arenas.set(index, a);
        }
        return write(index, a, a.allocator.allocate(bytes), data, bytes);
    }

    private Slot write(final int index, final Arena a, final long offset, final ByteBuffer data, final int bytes) {
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(a.buffer.slice(offset, bytes), data);
        usedBytes += bytes;
        return new Slot(index, offset, bytes);
    }

    /** Returns the vertex buffer slice of a slot. */
    GpuBufferSlice slice(final Slot slot) {
        final Arena a = arenas.get(slot.arena());
        if (a == null) {
            throw new IllegalStateException("slot of a released arena");
        }
        return a.buffer.slice(slot.offset(), slot.bytes());
    }

    /** Frees a slot; an arena other than the first is released once empty. */
    void free(final Slot slot) {
        final Arena a = arenas.get(slot.arena());
        if (a == null) {
            return;
        }
        a.allocator.free(slot.offset());
        usedBytes -= slot.bytes();
        if (slot.arena() > 0 && a.allocator.allocations() == 0) {
            a.buffer.close();
            arenas.set(slot.arena(), null);
        }
    }

    @Override
    public void close() {
        for (final Arena a : arenas) {
            if (a != null) {
                a.buffer.close();
            }
        }
        arenas.clear();
        usedBytes = 0;
    }
}
