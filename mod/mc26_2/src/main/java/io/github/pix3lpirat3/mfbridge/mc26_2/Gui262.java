package io.github.pix3lpirat3.mfbridge.mc26_2;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The GUI calls of 26.1 and 26.2, which share these signatures; the 26.1 module borrows them. */
public final class Gui262 {

    private Gui262() {
    }

    /** Draws part of a texture with the textured GUI pipeline. */
    public static void blit(final GuiGraphicsExtractor g, final Identifier texture, final int x, final int y, final float u,
            final float v, final int width, final int height, final int textureWidth, final int textureHeight) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /** Draws a GUI sprite with the textured GUI pipeline. */
    public static void sprite(final GuiGraphicsExtractor g, final Identifier sprite, final int x, final int y, final int width,
            final int height) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    /** Draws part of a GUI sprite with the textured GUI pipeline. */
    public static void spritePart(final GuiGraphicsExtractor g, final Identifier sprite, final int spriteWidth, final int spriteHeight,
            final int u, final int v, final int x, final int y, final int width, final int height) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
    }
}
