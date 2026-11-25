package io.github.pix3lpirat3.mfbridge.common.version;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;

/**
 * What differs between the game versions the mod runs on and cannot be found by name: types that
 * moved and APIs that changed shape. Each version has one implementation, built against that
 * version and picked at startup by {@link Versions}; {@link Game} reaches the rest by name.
 */
public interface VersionSupport {

    /** Returns the GPU calls for this version's render API. */
    Gpu gpu();

    /** Draws part of a texture with the textured GUI pipeline. */
    void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, float u, float v, int width, int height,
            int textureWidth, int textureHeight);

    /** Draws a GUI sprite with the textured GUI pipeline. */
    void blitSprite(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int width, int height);

    /** Draws part of a GUI sprite with the textured GUI pipeline. */
    void blitSprite(GuiGraphicsExtractor g, Identifier sprite, int spriteWidth, int spriteHeight, int u, int v, int x, int y,
            int width, int height);

    /**
     * Draws one background behind a block of world text, where the version can.
     *
     * @return false when it cannot, so each line carries its own background
     */
    boolean textBackground(SubmitNodeCollector collector, PoseStack pose, float x, float y, float width, float height, int argb,
            Font.DisplayMode mode, int light);

    /** Returns the sign editor that answers a sign prompt. */
    Screen signPrompt(PromptInput prompt, PromptAnswers answers);
}
