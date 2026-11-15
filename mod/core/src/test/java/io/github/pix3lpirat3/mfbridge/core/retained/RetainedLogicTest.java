package io.github.pix3lpirat3.mfbridge.core.retained;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.core.world.DetailPolicy;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeSection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetainedLogicTest {

    @Test
    void sectionKeysRoundTripNegativeCoordinates() {
        int[][] cases = {{0, 0, 0}, {-1, -4, -1}, {1875000, 127, -1875000}, {-7, 19, 3}};
        for (int[] c : cases) {
            long k = SectionKeys.pack(c[0], c[1], c[2]);
            assertEquals(c[0], SectionKeys.x(k));
            assertEquals(c[1], SectionKeys.y(k));
            assertEquals(c[2], SectionKeys.z(k));
        }
        assertEquals(SectionKeys.pack(-1, 0, 0), SectionKeys.ofBlock(-1, 5, 15));
        assertEquals(0x123, SectionKeys.index(3, 1, 2));
    }

    @Test
    void rangeAllocatorCoalescesFreedRanges() {
        RangeAllocator a = new RangeAllocator(1024, 64);
        long x = a.allocate(100);
        long y = a.allocate(64);
        long z = a.allocate(200);
        assertEquals(0, x);
        assertEquals(128, y);
        assertEquals(192, z);
        assertEquals(128 + 64 + 256, a.usedBytes());
        assertEquals(RangeAllocator.NONE, a.allocate(1000));
        a.free(y);
        assertEquals(128, a.allocate(1), "first fit reuses the hole");
        a.free(128);
        a.free(x);
        assertEquals(2, a.freeRanges(), "x and y merged into one hole before z");
        a.free(z);
        assertEquals(1, a.freeRanges());
        assertEquals(1024, a.largestFree());
        assertEquals(0, a.allocations());
        assertThrows(IllegalArgumentException.class, () -> a.free(64));
    }

    @Test
    void rangeAllocatorSurvivesRandomChurn() {
        RangeAllocator a = new RangeAllocator(1 << 20, 256);
        Random r = new Random(1);
        List<Long> live = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            if (!live.isEmpty() && r.nextBoolean()) {
                a.free(live.remove(r.nextInt(live.size())));
            } else {
                long off = a.allocate(1 + r.nextInt(8192));
                if (off != RangeAllocator.NONE) {
                    assertEquals(0, off % 256);
                    live.add(off);
                }
            }
        }
        for (long off : live) {
            a.free(off);
        }
        assertEquals(1, a.freeRanges());
        assertEquals(0, a.usedBytes());
    }

    static VolumeSection section(int sx, int sy, int sz, List<String> palette, int... setIndexPairs) {
        short[] idx = new short[4096];
        for (int i = 0; i < setIndexPairs.length; i += 2) {
            idx[setIndexPairs[i]] = (short) setIndexPairs[i + 1];
        }
        return new VolumeSection(sx, sy, sz, palette, idx);
    }

    @Test
    void ghostVolumeRebucketsByOriginAndMergesPalettes() {
        VolumeSection a = section(0, 0, 0, List.of("", "minecraft:stone", "minecraft:dirt"),
                SectionKeys.index(0, 0, 0), 1, SectionKeys.index(15, 0, 0), 2, SectionKeys.index(3, 3, 3), 9);
        VolumeSection b = section(1, 0, 0, List.of("", "minecraft:dirt", ""), SectionKeys.index(0, 0, 0), 1,
                SectionKeys.index(1, 0, 0), 2);
        GhostVolume v = GhostVolume.build(new VolumeBlob(List.of(a, b)), 8, 0, -1);
        assertEquals(List.of("", "minecraft:stone", "minecraft:dirt"), v.palette());
        assertEquals(3, v.blocks(), "out-of-palette and empty-string entries are unset");
        assertEquals(1, v.get(8, 0, -1));
        assertEquals(2, v.get(23, 0, -1));
        assertEquals(2, v.get(24, 0, -1));
        assertEquals(GhostVolume.UNSET, v.get(25, 0, -1));
        assertEquals(2, v.sections().size(), "x 8..24 at z -1 lands in sections 0 and 1 of z -1");
        assertNotNull(v.section(SectionKeys.pack(1, 0, -1)));
        assertEquals(new ObjectBounds.Box(8, 0, -1, 25, 1, 0), v.bounds(), "bounds cover set blocks, not the origin alone");
    }

    @Test
    void diffRuleAndSectionClassification() {
        assertEquals(DiffClassifier.MISSING, DiffClassifier.rule(false, true, false, false));
        assertEquals(DiffClassifier.MATCH, DiffClassifier.rule(false, false, true, true));
        assertEquals(DiffClassifier.WRONG, DiffClassifier.rule(false, false, true, false));
        assertEquals(DiffClassifier.WRONG, DiffClassifier.rule(false, false, false, true));
        assertEquals(DiffClassifier.EXTRA, DiffClassifier.rule(true, false, false, false));
        assertEquals(DiffClassifier.MATCH, DiffClassifier.rule(true, true, false, false));

        short[] vol = new short[4096];
        int[] world = new int[4096];
        vol[0] = 1;
        vol[1] = 1;
        vol[2] = 2;
        world[1] = 7;
        world[2] = 7;
        int[] calls = {0};
        // Palette 1 wants block 7, palette 2 wants air; world id 0 is air.
        DiffClassifier.StateMatcher m = (p, w) -> {
            calls[0]++;
            return DiffClassifier.rule(p == 2, w == 0, p == 1 && w == 7, true);
        };
        byte[] out = new byte[4096];
        assertEquals(3, DiffClassifier.classify(vol, world, m, out));
        assertEquals(DiffClassifier.MISSING, out[0]);
        assertEquals(DiffClassifier.MATCH, out[1]);
        assertEquals(DiffClassifier.EXTRA, out[2]);
        assertEquals(DiffClassifier.HIDDEN, out[3]);
        assertEquals(3, calls[0], "one matcher call per distinct pair");
        assertEquals(3, DiffClassifier.classify(vol, null, m, out));
        assertEquals(DiffClassifier.PLAIN, out[0]);

        assertTrue(DiffClassifier.facesDrawn(DiffClassifier.MISSING, true));
        assertFalse(DiffClassifier.facesDrawn(DiffClassifier.MATCH, true));
        assertTrue(DiffClassifier.facesDrawn(DiffClassifier.MATCH, false));
        assertFalse(DiffClassifier.facesDrawn(DiffClassifier.EXTRA, false));
        assertTrue(DiffClassifier.outlined(DiffClassifier.EXTRA, true, false));
        assertTrue(DiffClassifier.outlined(DiffClassifier.WRONG, true, true));
        assertFalse(DiffClassifier.outlined(DiffClassifier.WRONG, true, false));
    }

    static Set<String> outline(CellPredicate cells, int... sections) {
        Set<String> edges = new HashSet<>();
        for (int i = 0; i < sections.length; i += 3) {
            OutlineMesher.mesh(sections[i], sections[i + 1], sections[i + 2], cells, (ax, ay, az, bx, by, bz) -> {
                String e = ax + "," + ay + "," + az + "-" + bx + "," + by + "," + bz;
                assertTrue(edges.add(e), "edge emitted twice: " + e);
            });
        }
        return edges;
    }

    @Test
    void singleBlockHasTwelveEdges() {
        assertEquals(12, outline((x, y, z) -> x == 3 && y == 4 && z == 5, 0, 0, 0).size());
    }

    @Test
    void neighboursMergeIntoOneBoxOutline() {
        // A 3x2x1 slab: internal edges vanish and collinear runs merge, so it outlines like one box.
        CellPredicate slab = (x, y, z) -> x >= 0 && x < 3 && y >= 0 && y < 2 && z == 0;
        Set<String> e = outline(slab, 0, 0, 0);
        assertEquals(12, e.size(), e.toString());
        assertTrue(e.contains("0,0,0-3,0,0"));
        assertTrue(e.contains("0,0,0-0,2,0"));
    }

    @Test
    void outlinesAcrossSectionBordersAreEmittedOnce() {
        // Two blocks on both sides of the x = 16 section border, plus an L shape for a concave edge.
        CellPredicate cells = (x, y, z) -> z == 0 && y == 0 && (x == 15 || x == 16) || x == 15 && y == 1 && z == 0;
        Set<String> e = outline(cells, 0, 0, 0, 1, 0, 0);
        assertTrue(e.contains("15,0,0-16,0,0") && e.contains("16,0,0-17,0,0"), "runs end at section borders: " + e);
        assertFalse(e.contains("16,0,0-16,0,1"), "the shared face's edges vanish");
        // Concave L edge at the inner corner (16, 1) along z.
        assertTrue(e.contains("16,1,0-16,1,1"), e.toString());
    }

    @Test
    void diagonalNeighboursKeepTheirSharedEdgeOnce() {
        CellPredicate cells = (x, y, z) -> z == 0 && (x == 0 && y == 0 || x == 1 && y == 1);
        Set<String> e = outline(cells, 0, 0, 0);
        assertEquals(19, e.size(), "23 unit edges, four collinear pairs merged: " + e);
        assertTrue(e.contains("1,1,0-1,1,1"));
    }

    @Test
    void faceMesherDropsSharedFaces() {
        int[] count = {0};
        int faces = FaceMesher.mesh(0, 0, 0, (x, y, z) -> y == 0 && z == 0 && (x == 0 || x == 1), (x, y, z, d) -> count[0]++);
        assertEquals(10, faces);
        assertEquals(10, count[0]);
        float[] q = new float[12];
        FaceMesher.corners(0, 0, 0, 1, FaceMesher.UP, q);
        for (int v = 0; v < 4; v++) {
            assertEquals(1f, q[v * 3 + 1]);
        }
    }

    @Test
    void heatmapMeshCullsInnerFacesAndSkipsNonFinite() {
        CellSet cells = CellSet.of(new int[] {0, 0, 0, 1, 0, 0, 5, 5, 5, 20, 0, 0});
        float[] values = {1f, 2f, Float.NaN, 4f};
        long key = SectionKeys.pack(0, 0, 0);
        int[] idx = cells.sections().get(key);
        assertEquals(3, idx.length);
        MeshWriter w = new MeshWriter(MeshWriter.Format.TEX, 16);
        assertEquals(10, SectionMeshing.heatmap(cells, values, idx, key, 1f, w));
        assertEquals(40, w.vertices());
        ByteBuffer b = w.finish();
        assertEquals(40 * 20, b.remaining());
        assertEquals(1f, b.getFloat(12), "u carries the raw value");
        MeshWriter all = new MeshWriter(MeshWriter.Format.TEX, 16);
        assertEquals(12, SectionMeshing.heatmap(cells, values, idx, key, 0.5f, all), "no culling for non-unit cells");
    }

    /** Asserts that no vertex of a mesh lies on an integer plane, so no face is coplanar with a block face. */
    private static void assertOffBlockPlanes(MeshWriter w, int stride) {
        ByteBuffer b = w.finish();
        for (int v = 0; v < w.vertices(); v++) {
            for (int axis = 0; axis < 3; axis++) {
                float c = b.getFloat(v * stride + axis * 4);
                assertTrue(Math.abs(c - Math.round(c)) > 0.001f, "vertex " + v + " axis " + axis + " at " + c);
            }
        }
    }

    @Test
    void heatmapAndHighlightFacesStayOffBlockSurfaces() {
        CellSet cells = CellSet.of(new int[] {0, 0, 0, 1, 0, 0});
        long key = SectionKeys.pack(0, 0, 0);
        MeshWriter heat = new MeshWriter(MeshWriter.Format.TEX, 16);
        SectionMeshing.heatmap(cells, new float[] {1f, 2f}, cells.sections().get(key), key, 1f, heat);
        assertOffBlockPlanes(heat, 20);
        ByteBuffer b = heat.finish();
        float minY = Float.MAX_VALUE;
        for (int v = 0; v < heat.vertices(); v++) {
            minY = Math.min(minY, b.getFloat(v * 20 + 4));
        }
        assertEquals(SectionMeshing.HEATMAP_LIFT, minY, 1e-6f, "heatmap cubes are lifted off the ground");
        MeshWriter faces = new MeshWriter(MeshWriter.Format.COLOR, 16);
        SectionMeshing.faces(key, cells, 0xFFFFFFFF, faces);
        assertOffBlockPlanes(faces, 16);
    }

    @Test
    void meshWriterMatchesGameLayouts() {
        MeshWriter w = new MeshWriter(MeshWriter.Format.LINE, 1);
        w.line(0, 0, 0, 0, 2, 0, 0x80FF2010, 3f);
        assertEquals(4, w.vertices());
        ByteBuffer b = w.finish();
        assertEquals(96, b.remaining());
        assertEquals((byte) 0xFF, b.get(12), "red first");
        assertEquals((byte) 0x20, b.get(13));
        assertEquals((byte) 0x10, b.get(14));
        assertEquals((byte) 0x80, b.get(15), "alpha last");
        assertEquals(0, b.get(16));
        assertEquals(127, b.get(17), "normal along +y");
        assertEquals(3f, b.getFloat(20));
        assertEquals(0f, b.getFloat(24 + 4), "second vertex repeats the start");
        assertEquals(2f, b.getFloat(48 + 4), "third vertex is the end");
        MeshWriter t = new MeshWriter(MeshWriter.Format.TEX_COLOR, 1);
        for (int i = 0; i < 100; i++) {
            t.texColor(i, 0, 0, 0.5f, 0.25f, 0xFFFFFFFF);
        }
        assertEquals(2400, t.bytes(), "grows past the initial capacity");
        w.line(1, 1, 1, 1, 1, 1, -1, 1f);
        assertEquals(4, w.vertices(), "degenerate segments are dropped");
    }

    @Test
    void colormapsAndUvTransform() {
        assertEquals(0, Colormaps.row("viridis"));
        assertEquals(2, Colormaps.row("TURBO"));
        assertEquals(0, Colormaps.row("unknown"));
        assertEquals(0xFF440154, Colormaps.argb(0, 0f));
        assertEquals(0xFFFDE725, Colormaps.argb(0, 1f));
        assertEquals(0xFF000000, Colormaps.argb(4, -3f));
        assertEquals(0xFFFFFFFF, Colormaps.argb(4, 2f));
        assertEquals(0xFF808080, Colormaps.argb(4, 0.5f));
        int[] tex = Colormaps.texture();
        assertEquals(Colormaps.WIDTH * Colormaps.ROWS, tex.length);
        assertEquals(0xFFFCFDBF, tex[Colormaps.WIDTH + Colormaps.WIDTH - 1], "magma row ends pale yellow");

        float[] uv = Colormaps.uvTransform(10f, 20f, 3);
        float first = uv[0] * 10f + uv[1];
        float last = uv[0] * 20f + uv[1];
        assertEquals(0.5f / Colormaps.WIDTH, first, 1e-6f);
        assertEquals((Colormaps.WIDTH - 0.5f) / Colormaps.WIDTH, last, 1e-6f);
        assertEquals(3.5f / Colormaps.ROWS, uv[2], 1e-6f);
        assertEquals(0f, Colormaps.uvTransform(5f, 5f, 0)[0], "degenerate range");

        float[] data = {3f, Float.NaN, -2f, 7f, Float.POSITIVE_INFINITY};
        float[] r = Colormaps.range(data, null, null);
        assertEquals(-2f, r[0]);
        assertEquals(7f, r[1]);
        float[] fixed = Colormaps.range(data, 9f, 1f);
        assertEquals(1f, fixed[0]);
        assertEquals(9f, fixed[1]);
        assertEquals(0f, Colormaps.range(new float[0], null, 4f)[0]);
    }

    @Test
    void cellSetDeduplicatesAndBuckets() {
        CellSet s = CellSet.of(new int[] {0, 0, 0, 0, 0, 0, -1, 0, 0, 16, 0, 0, 1});
        assertEquals(3, s.size());
        assertEquals(3, s.sections().size());
        assertTrue(s.test(-1, 0, 0));
        assertFalse(s.test(1, 0, 0));
        assertNotEquals(CellSet.pack(0, -1, 0), CellSet.pack(0, 1, 0));
        assertNull(s.sections().get(SectionKeys.pack(5, 5, 5)));
        assertEquals(new ObjectBounds.Box(-1, 0, 0, 18, 2, 2), s.bounds(2));
        assertNull(CellSet.of(new int[0]).bounds(1));
    }

    @Test
    void detailPolicyCountsAndDistanceWithHysteresis() {
        double far = 300 * 300;
        double between = 240 * 240;
        double near = 100 * 100;
        assertFalse(DetailPolicy.reduced(false, 5, true, near));
        assertTrue(DetailPolicy.reduced(false, 21, true, near), "many attached bots");
        assertFalse(DetailPolicy.reduced(false, 20, true, Double.NaN));
        assertTrue(DetailPolicy.reduced(false, 1, true, far));
        assertFalse(DetailPolicy.reduced(false, 1, true, between), "not yet far");
        assertTrue(DetailPolicy.reduced(true, 1, true, between), "stays reduced until near");
        assertFalse(DetailPolicy.reduced(true, 1, true, near));
        assertFalse(DetailPolicy.reduced(true, 50, false, far), "detached sessions keep full detail");
    }
}
