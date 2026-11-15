package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.BridgeScreen;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jspecify.annotations.Nullable;

/**
 * The {@code sign} surface: the vanilla sign edit screen over a sign
 * that exists only on the client, in the bot's wood type. Vanilla sends {@code
 * ServerboundSignUpdatePacket} from {@code removed()}; this screen replaces that method, so the lines
 * go to the prompt and no packet is built. The detached sign has no level, so editing it never marks
 * a chunk or block entity changed. Answers text prompts with the lines joined by spaces and lines
 * prompts with up to four lines. Esc returns to the prompt card. Client thread only.
 */
public final class SignPromptScreen extends SignEditScreen implements BridgeScreen {

    private static final int LINES = 4;

    private final PromptInput prompt;
    private final PromptAnswers controller;
    private final DetachedSign sign;
    private boolean leaving;

    /**
     * Creates the screen.
     *
     * @param prompt a text or lines prompt
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public SignPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        this(prompt, controller, new DetachedSign(signBlock(prompt.request().wood()), initial(prompt.request().defaultValue())));
    }

    private SignPromptScreen(final PromptInput prompt, final PromptAnswers controller, final DetachedSign sign) {
        super(sign, SignTextSlot.FRONT, false);
        this.prompt = prompt;
        this.controller = controller;
        this.sign = sign;
    }

    /** The standing sign block of a wood type such as {@code spruce}; oak when unknown. */
    static Block signBlock(final @Nullable String wood) {
        if (wood != null) {
            final String name = wood.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            final Identifier id = Identifier.tryParse("minecraft:" + name + "_sign");
            if (id != null) {
                final Block block = BuiltInRegistries.BLOCK.getValue(id);
                if (block instanceof StandingSignBlock) {
                    return block;
                }
            }
        }
        return Blocks.OAK_SIGN;
    }

    private static SignText initial(final @Nullable Object def) {
        final List<Component> lines = new ArrayList<>(LINES);
        final List<?> given = def instanceof List<?> l ? l : def == null ? List.of() : List.of(def.toString());
        for (int i = 0; i < LINES; i++) {
            lines.add(Component.literal(i < given.size() ? String.valueOf(given.get(i)) : ""));
        }
        return new SignText(lines, lines, DyeColor.BLACK, false);
    }

    @Override
    public void tick() {
        // Replaces vanilla's distance check, which needs the sign to be in a level.
        if (controller.active() != prompt) {
            leaving = true;
            minecraft.gui.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        leaving = true;
        super.onClose();
    }

    @Override
    public void removed() {
        // Vanilla sends ServerboundSignUpdatePacket here; the bridge answers the prompt instead.
        minecraft.textInputManager().stopTextInput(this);
        if (leaving) {
            return;
        }
        leaving = true;
        final List<String> lines = new ArrayList<>(LINES);
        for (final Component c : sign.getText(SignTextSlot.FRONT).getMessages(false)) {
            lines.add(c.getString());
        }
        while (lines.size() > 1 && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        if (prompt.request().kind() == PromptKind.TEXT) {
            controller.answer(String.join(" ", lines).strip());
        } else {
            controller.answer(List.copyOf(lines));
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, Component.literal(prompt.ownerName() + ": " + prompt.request().text()), width / 2, 24, 0xFFFFFFFF);
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
