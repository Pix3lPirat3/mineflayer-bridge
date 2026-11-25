package io.github.pix3lpirat3.mfbridge.mc26_2;

import io.github.pix3lpirat3.mfbridge.common.ui.surface.SignPrompt;
import java.util.Arrays;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/** The sign prompt's editor where a sign side is a boolean (26.1 and 26.2). */
public final class SignPromptScreen extends SignEditScreen implements SignPrompt.Editor {

    private final SignPrompt prompt;
    private final DetachedSign sign;

    /**
     * Opens the editor of a prompt.
     *
     * @param prompt the prompt
     */
    public SignPromptScreen(final SignPrompt prompt) {
        this(prompt, new DetachedSign(prompt.block(), text(prompt)));
    }

    private SignPromptScreen(final SignPrompt prompt, final DetachedSign sign) {
        super(sign, true, false);
        this.prompt = prompt;
        this.sign = sign;
    }

    private static SignText text(final SignPrompt prompt) {
        final Component[] lines = prompt.initialLines().toArray(Component[]::new);
        return new SignText(lines, lines.clone(), DyeColor.BLACK, false);
    }

    @Override
    public void tick() {
        prompt.tick();
    }

    @Override
    public void onClose() {
        prompt.closing();
        super.onClose();
    }

    @Override
    public void removed() {
        prompt.removed(this, Arrays.asList(sign.getText(true).getMessages(false)));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        prompt.title(graphics, font, width, mouseX, mouseY);
    }

    /** A sign block entity that belongs to no level; text changes stay in the object. */
    static final class DetachedSign extends SignBlockEntity {
        private SignText front;

        DetachedSign(final Block block, final SignText front) {
            super(BlockPos.ZERO, block.defaultBlockState());
            this.front = front;
        }

        @Override
        public SignText getText(final boolean isFront) {
            return isFront && front != null ? front : super.getText(isFront);
        }

        @Override
        public boolean setText(final SignText text, final boolean isFront) {
            // SignBlockEntity.setText marks the block updated in its level, and this sign has none.
            if (isFront) {
                front = text;
            }
            return true;
        }
    }
}
