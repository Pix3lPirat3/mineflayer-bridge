package io.github.pix3lpirat3.mfbridge.mc26_3;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.render.gpu.Gpu;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.SignPrompt;
import io.github.pix3lpirat3.mfbridge.common.version.VersionSupport;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;

/** Minecraft 26.3. */
public final class Support implements VersionSupport {

    private final Gpu gpu = new Gpu263();

    @Override
    public Gpu gpu() {
        return gpu;
    }

    @Override
    public void blit(final GuiGraphicsExtractor g, final Identifier texture, final int x, final int y, final float u, final float v,
            final int width, final int height, final int textureWidth, final int textureHeight) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    @Override
    public void blitSprite(final GuiGraphicsExtractor g, final Identifier sprite, final int x, final int y, final int width,
            final int height) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    @Override
    public void blitSprite(final GuiGraphicsExtractor g, final Identifier sprite, final int spriteWidth, final int spriteHeight,
            final int u, final int v, final int x, final int y, final int width, final int height) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
    }

    @Override
    public boolean textBackground(final SubmitNodeCollector collector, final PoseStack pose, final float x, final float y,
            final float width, final float height, final int argb, final Font.DisplayMode mode, final int light) {
        collector.submitTextBackground(pose, x, y, width, height, argb, mode, light);
        return true;
    }

    @Override
    public Screen signPrompt(final PromptInput prompt, final PromptAnswers answers) {
        return new SignPromptScreen(new SignPrompt(prompt, answers));
    }
}
