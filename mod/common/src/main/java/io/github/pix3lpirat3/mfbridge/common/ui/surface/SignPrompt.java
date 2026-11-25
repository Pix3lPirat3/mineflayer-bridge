package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.PanelLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.BridgeScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiText;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import org.jspecify.annotations.Nullable;

/**
 * A sign prompt: the vanilla sign editor over a sign that belongs to no level, answered to the bot
 * instead of the server. The editor itself differs between versions (see {@code VersionSupport.signPrompt});
 * everything else is here.
 */
public final class SignPrompt {

    /** The version's sign editor screen of a prompt. */
    public interface Editor extends BridgeScreen {
    }

    /** Lines on a sign. */
    public static final int LINES = 4;

    private final PromptInput prompt;
    private final PromptAnswers controller;
    private boolean leaving;

    /**
     * Starts a prompt.
     *
     * @param prompt the request
     * @param controller where the answer goes
     */
    public SignPrompt(final PromptInput prompt, final PromptAnswers controller) {
        this.prompt = prompt;
        this.controller = controller;
    }

    /** Returns the sign block the request names by its wood, oak when none fits. */
    public Block block() {
        final String wood = prompt.request().wood();
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

    /** Returns the four starting lines from the request's default. */
    public List<Component> initialLines() {
        final @Nullable Object def = prompt.request().defaultValue();
        final List<Component> lines = new ArrayList<>(LINES);
        final List<?> given = def instanceof List<?> l ? l : def == null ? List.of() : List.of(def.toString());
        for (int i = 0; i < LINES; i++) {
            lines.add(Component.literal(i < given.size() ? String.valueOf(given.get(i)) : ""));
        }
        return lines;
    }

    /** Each tick, in place of vanilla's distance check: closes once the prompt is no longer the active one. */
    public void tick() {
        if (controller.active() != prompt) {
            leaving = true;
            Game.setScreen(null);
        }
    }

    /** The player closed the editor. */
    public void closing() {
        leaving = true;
    }

    /**
     * The editor was removed: answers with the lines unless the prompt already ended.
     *
     * @param owner the editor, whose text input stops
     * @param messages the sign's front lines
     */
    public void removed(final Object owner, final Iterable<Component> messages) {
        // Vanilla sends ServerboundSignUpdatePacket here; the bridge answers the prompt instead.
        Game.stopTextInput(owner);
        if (leaving) {
            return;
        }
        leaving = true;
        final List<String> lines = new ArrayList<>(LINES);
        for (final Component c : messages) {
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

    /** Draws who asks and what, over the editor. */
    public void title(final GuiGraphicsExtractor graphics, final Font font, final int width, final int mouseX, final int mouseY) {
        UiText.line(graphics, font, prompt.ownerName() + ": " + prompt.request().text(), width / 2, 24, width - 2 * PanelLayout.MARGIN,
                0xFFFFFFFF, true, true, mouseX, mouseY);
    }
}
