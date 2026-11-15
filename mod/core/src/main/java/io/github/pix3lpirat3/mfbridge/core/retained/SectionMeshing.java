package io.github.pix3lpirat3.mfbridge.core.retained;

/**
 * Builds the game-free section meshes of the retained tier: heatmap cubes, merged outlines and
 * boundary faces. Vertex positions are relative to the section's minimum corner.
 *
 * <p>Stateless; meant for worker threads.
 */
public final class SectionMeshing {
    /**
     * Outward offset of highlight faces and heatmap side faces, in blocks. Faces exactly on a block
     * surface z-fight with it; this keeps them in front at every distance the tier draws.
     */
    public static final float FACE_MARGIN = 0.0025f;

    /**
     * How far heatmap cubes are lifted, in blocks, so their top and bottom faces never share a plane
     * with the top surface of the block they mark or the ground below.
     */
    public static final float HEATMAP_LIFT = 0.015f;

    private SectionMeshing() {}

    /**
     * Heatmap cells of one section as translucent cubes ({@link MeshWriter.Format#TEX}). Each vertex
     * carries the cell's raw value in u, so colour mapping happens in the shader. With unit cells,
     * faces between neighbouring cells are dropped so the inside of a solid region is not layered.
     * Cubes are lifted by {@link #HEATMAP_LIFT} and their sides pushed out by {@link #FACE_MARGIN},
     * so no face lies on a block surface.
     *
     * @param cells all cells of the heatmap
     * @param values one value per triple of {@code cells}; non-finite values are not drawn
     * @param indexes triple indexes of this section's cells, from {@link CellSet#sections()}
     * @param cellSize cube edge length, in blocks
     * @return the number of faces written
     */
    public static int heatmap(CellSet cells, float[] values, int[] indexes, long sectionKey, float cellSize,
            MeshWriter out) {
        int ox = SectionKeys.x(sectionKey) << 4;
        int oy = SectionKeys.y(sectionKey) << 4;
        int oz = SectionKeys.z(sectionKey) << 4;
        boolean cull = cellSize == 1f;
        float size = cellSize > 0f && Float.isFinite(cellSize) ? cellSize : 1f;
        int[] xyz = cells.xyz();
        float[] q = new float[12];
        int faces = 0;
        for (int i : indexes) {
            if (i >= values.length || !Float.isFinite(values[i])) {
                continue;
            }
            float value = values[i];
            int x = xyz[i * 3];
            int y = xyz[i * 3 + 1];
            int z = xyz[i * 3 + 2];
            for (int dir = 0; dir < 6; dir++) {
                int nx = x + FaceMesher.dx(dir);
                int ny = y + FaceMesher.dy(dir);
                int nz = z + FaceMesher.dz(dir);
                if (cull && cells.test(nx, ny, nz)) {
                    continue;
                }
                float bx = x - ox;
                float by = y - oy + HEATMAP_LIFT;
                float bz = z - oz;
                FaceMesher.corners(bx - FACE_MARGIN, by, bz - FACE_MARGIN, bx + size + FACE_MARGIN, by + size,
                        bz + size + FACE_MARGIN, dir, q);
                for (int v = 0; v < 4; v++) {
                    out.tex(q[v * 3], q[v * 3 + 1], q[v * 3 + 2], value, 0f);
                }
                faces++;
            }
        }
        return faces;
    }

    /**
     * Merged outline segments owned by one section ({@link MeshWriter.Format#LINE}).
     *
     * @param argb segment colour
     * @param width line width, in screen pixels
     * @return the number of segments written
     */
    public static int outline(long sectionKey, CellPredicate cells, int argb, float width, MeshWriter out) {
        int sx = SectionKeys.x(sectionKey);
        int sy = SectionKeys.y(sectionKey);
        int sz = SectionKeys.z(sectionKey);
        int ox = sx << 4;
        int oy = sy << 4;
        int oz = sz << 4;
        return OutlineMesher.mesh(sx, sy, sz, cells, (ax, ay, az, bx, by, bz) ->
                out.line(ax - ox, ay - oy, az - oz, bx - ox, by - oy, bz - oz, argb, width));
    }

    /**
     * Boundary faces of the cells in one section ({@link MeshWriter.Format#COLOR}), inflated by {@link
     * #FACE_MARGIN} so they do not z-fight with the blocks they cover.
     *
     * @return the number of faces written
     */
    public static int faces(long sectionKey, CellPredicate cells, int argb, MeshWriter out) {
        int sx = SectionKeys.x(sectionKey);
        int sy = SectionKeys.y(sectionKey);
        int sz = SectionKeys.z(sectionKey);
        int ox = sx << 4;
        int oy = sy << 4;
        int oz = sz << 4;
        float[] q = new float[12];
        return FaceMesher.mesh(sx, sy, sz, cells, (x, y, z, dir) -> {
            FaceMesher.corners(x - ox - FACE_MARGIN, y - oy - FACE_MARGIN, z - oz - FACE_MARGIN, 1f + 2 * FACE_MARGIN,
                    dir, q);
            for (int v = 0; v < 4; v++) {
                out.color(q[v * 3], q[v * 3 + 1], q[v * 3 + 2], argb);
            }
        });
    }
}
