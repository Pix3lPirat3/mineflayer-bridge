package io.github.pix3lpirat3.mfbridge.common.render.retained;

import io.github.pix3lpirat3.mfbridge.common.version.Versions;
import io.github.pix3lpirat3.mfbridge.core.retained.MeshWriter;

/** Checks that the core's vertex layouts match the game's formats, so raw buffers upload unconverted. */
final class VertexLayouts {

    private VertexLayouts() {
    }

    /** Returns null when they match, otherwise what differs. */
    static String problem() {
        final int[] sizes = Versions.CURRENT.gpu().vertexSizes();
        final int texColor = sizes[0];
        final int tex = sizes[1];
        final int color = sizes[2];
        final int line = sizes[3];
        if (texColor != MeshWriter.Format.TEX_COLOR.bytes() || tex != MeshWriter.Format.TEX.bytes()
                || color != MeshWriter.Format.COLOR.bytes() || line != MeshWriter.Format.LINE.bytes()) {
            return "vertex sizes " + texColor + "/" + tex + "/" + color + "/" + line;
        }
        return null;
    }
}
