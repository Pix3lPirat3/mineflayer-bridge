package io.github.pix3lpirat3.mfbridge.mc26_3;

import io.github.pix3lpirat3.mfbridge.common.ui.surface.SignPrompt;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

/** The sign prompt's editor where a sign side is a {@link SignTextSlot} (26.3). */
final class SignPromptScreen extends SignEditScreen implements SignPrompt.Editor {

    private final SignPrompt prompt;
    private final DetachedSign sign;

    SignPromptScreen(final SignPrompt prompt) {
        this(prompt, new DetachedSign(prompt.block(), text(prompt)));
    }

    private SignPromptScreen(final SignPrompt prompt, final DetachedSign sign) {
        super(sign, SignTextSlot.FRONT, false);
        this.prompt = prompt;
        this.sign = sign;
    }

    private static SignText text(final SignPrompt prompt) {
        final List<Component> lines = prompt.initialLines();
        return new SignText(lines, lines, DyeColor.BLACK, false);
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
        prompt.removed(this, sign.getText(SignTextSlot.FRONT).getMessages(false));
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
        public SignText getText(final SignTextSlot slot) {
            return slot == SignTextSlot.FRONT && front != null ? front : super.getText(slot);
        }

        @Override
        public void setText(final SignText text, final SignTextSlot slot) {
            // SignBlockEntity.setText marks the block updated in its level, and this sign has none.
            if (slot == SignTextSlot.FRONT) {
                front = text;
            }
        }
    }
}
