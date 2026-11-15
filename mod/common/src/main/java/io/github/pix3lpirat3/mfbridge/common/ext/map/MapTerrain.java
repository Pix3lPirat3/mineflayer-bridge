package io.github.pix3lpirat3.mfbridge.common.ext.map;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The map's terrain: a {@link #SIZE} by {@link #SIZE} texture of the client's loaded chunks seen from
 * above, one pixel per {@code blocksPerPixel} blocks, coloured by the top block's map colour and
 * shaded by the height step to the north, as vanilla maps are. Painted a few rows per frame, so opening
 * or panning the map never costs one large frame; nothing is read from or sent to the server.
 */
final class MapTerrain implements AutoCloseable {

    /** Texture edge, pixels. */
    static final int SIZE = 256;
    /** Rows painted per frame. */
    private static final int ROWS_PER_FRAME = 24;
    private static final int UNLOADED_ARGB = 0xFF1A1A1A;

    private final Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "map/terrain");
    private final DynamicTexture texture = new DynamicTexture(() -> "Bridge map terrain", SIZE, SIZE, false);
    private int originX;
    private int originZ;
    private int blocksPerPixel = 1;
    private int nextRow = SIZE;
    private boolean registered;

    /** Returns the texture's id, registered on first use. */
    Identifier id() {
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(id, texture);
            registered = true;
        }
        return id;
    }

    /** World x of the texture's left edge. */
    int originX() {
        return originX;
    }

    /** World z of the texture's top edge. */
    int originZ() {
        return originZ;
    }

    /** Blocks one pixel covers. */
    int blocksPerPixel() {
        return blocksPerPixel;
    }

    /**
     * Starts repainting around a centre.
     *
     * @param centerX world x at the texture centre
     * @param centerZ world z at the texture centre
     * @param scale blocks per pixel, 1 or more
     */
    void repaint(final int centerX, final int centerZ, final int scale) {
        blocksPerPixel = Math.max(1, scale);
        originX = centerX - SIZE / 2 * blocksPerPixel;
        originZ = centerZ - SIZE / 2 * blocksPerPixel;
        nextRow = 0;
    }

    /** Whether a repaint is still in progress. */
    boolean painting() {
        return nextRow < SIZE;
    }

    /** Paints the next rows of a repaint; call once per frame. */
    void step(final ClientLevel level) {
        if (nextRow >= SIZE) {
            return;
        }
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int end = Math.min(SIZE, nextRow + ROWS_PER_FRAME);
        for (int py = nextRow; py < end; py++) {
            final int z = originZ + py * blocksPerPixel;
            for (int px = 0; px < SIZE; px++) {
                final int x = originX + px * blocksPerPixel;
                texture.getPixels().setPixel(px, py, color(level, pos, x, z));
            }
        }
        nextRow = end;
        texture.upload();
    }

    private int color(final ClientLevel level, final BlockPos.MutableBlockPos pos, final int x, final int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return UNLOADED_ARGB;
        }
        final int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (top < level.getMinY()) {
            return UNLOADED_ARGB;
        }
        pos.set(x, top, z);
        final BlockState state = level.getBlockState(pos);
        final MapColor color = state.getMapColor(level, pos);
        if (color == MapColor.NONE) {
            return UNLOADED_ARGB;
        }
        final int north = level.hasChunk(x >> 4, (z - blocksPerPixel) >> 4)
                ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - blocksPerPixel) - 1 : top;
        final MapColor.Brightness b = top > north ? MapColor.Brightness.HIGH : top < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        return color.calculateARGBColor(b);
    }

    @Override
    public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(id);
            registered = false;
        } else {
            texture.close();
        }
    }
}
