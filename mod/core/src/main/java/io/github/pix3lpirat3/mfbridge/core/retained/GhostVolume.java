package io.github.pix3lpirat3.mfbridge.core.retained;

import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A ghost-block volume re-bucketed into world-aligned 16x16x16 sections.
 * The blob's sections are in its own coordinates; {@code origin} may shift them by any
 * amount, so every block is moved into the world section it lands in. Palettes are merged into one
 * per volume, where id 0 means unset (no ghost).
 *
 * <p>Immutable once built and safe to share between worker threads. Building allocates and belongs
 * on a worker.
 */
public final class GhostVolume {
    /** Palette id of positions the volume leaves unset. */
    public static final int UNSET = 0;

    private final List<String> palette;
    private final Long2ObjectOpenHashMap<short[]> sections;
    private final int blocks;

    private GhostVolume(List<String> palette, Long2ObjectOpenHashMap<short[]> sections, int blocks) {
        this.palette = palette;
        this.sections = sections;
        this.blocks = blocks;
    }

    /**
     * Buckets a decoded blob.
     *
     * <p>Malformed sections are tolerated rather than rejected, so one bad section never hides the
     * rest: indices beyond the section palette count as unset, and a section with fewer than 4096
     * indices only fills what it has.
     *
     * @param blob the decoded blob
     * @param ox origin x added to every position, in blocks
     * @param oy origin y
     * @param oz origin z
     */
    public static GhostVolume build(VolumeBlob blob, int ox, int oy, int oz) {
        List<String> palette = new ArrayList<>();
        palette.add("");
        Object2IntOpenHashMap<String> ids = new Object2IntOpenHashMap<>();
        Long2ObjectOpenHashMap<short[]> sections = new Long2ObjectOpenHashMap<>();
        int blocks = 0;
        for (VolumeSection vs : blob.sections()) {
            List<String> local = vs.palette();
            int[] map = new int[local.size()];
            for (int i = 1; i < local.size(); i++) {
                String state = local.get(i);
                if (state.isEmpty()) {
                    // Writers fill palette slot 0 with '', and '' anywhere means unset.
                    continue;
                }
                int id = ids.getOrDefault(state, -1);
                if (id < 0 && palette.size() <= Short.MAX_VALUE * 2) {
                    id = palette.size();
                    palette.add(state);
                    ids.put(state, id);
                }
                map[i] = Math.max(id, UNSET);
            }
            short[] indices = vs.indices();
            int n = Math.min(indices.length, 4096);
            int baseX = vs.x() * 16 + ox;
            int baseY = vs.y() * 16 + oy;
            int baseZ = vs.z() * 16 + oz;
            long lastKey = Long.MIN_VALUE;
            short[] target = null;
            for (int i = 0; i < n; i++) {
                int p = Short.toUnsignedInt(indices[i]);
                if (p == 0 || p >= map.length || map[p] == UNSET) {
                    continue;
                }
                int wx = baseX + (i & 15);
                int wy = baseY + (i >> 8);
                int wz = baseZ + ((i >> 4) & 15);
                long key = SectionKeys.ofBlock(wx, wy, wz);
                if (key != lastKey) {
                    target = sections.get(key);
                    if (target == null) {
                        target = new short[4096];
                        sections.put(key, target);
                    }
                    lastKey = key;
                }
                int idx = SectionKeys.index(wx & 15, wy & 15, wz & 15);
                if (target[idx] == UNSET) {
                    blocks++;
                }
                target[idx] = (short) map[p];
            }
        }
        return new GhostVolume(List.copyOf(palette), sections, blocks);
    }

    /** Returns the merged palette; entry 0 is the empty string standing for unset. */
    public List<String> palette() {
        return palette;
    }

    /** Returns the number of set positions. */
    public int blocks() {
        return blocks;
    }

    /** Returns the world sections that hold at least one set position. */
    public Long2ObjectMap<short[]> sections() {
        return Long2ObjectMaps.unmodifiable(sections);
    }

    /** Returns the palette ids of one world section in {@code (y * 16 + z) * 16 + x} order, or null. */
    public short @Nullable [] section(long key) {
        return sections.get(key);
    }

    /** Returns the box around every set position, in world blocks, or null when nothing is set. */
    public ObjectBounds.@Nullable Box bounds() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Long2ObjectMap.Entry<short[]> e : sections.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            int bx = SectionKeys.x(key) << 4;
            int by = SectionKeys.y(key) << 4;
            int bz = SectionKeys.z(key) << 4;
            short[] s = e.getValue();
            for (int i = 0; i < s.length; i++) {
                if (s[i] == UNSET) {
                    continue;
                }
                int x = bx + (i & 15);
                int z = bz + ((i >> 4) & 15);
                int y = by + (i >> 8);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }
        return minX > maxX ? null : new ObjectBounds.Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /** Returns the palette id at a world block position, {@link #UNSET} outside the volume. */
    public int get(int wx, int wy, int wz) {
        short[] s = sections.get(SectionKeys.ofBlock(wx, wy, wz));
        return s == null ? UNSET : Short.toUnsignedInt(s[SectionKeys.index(wx & 15, wy & 15, wz & 15)]);
    }
}
