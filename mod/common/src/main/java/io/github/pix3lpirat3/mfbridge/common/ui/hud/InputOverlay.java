package io.github.pix3lpirat3.mfbridge.common.ui.hud;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.PlayerFeedback;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PromptController;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PanelLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiText;
import io.github.pix3lpirat3.mfbridge.core.decl.ToolDecl;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * HUD overlays for input: the action-bar style pick hint, the prompt card, the tool mode
 * line with its crosshair tint, and running invocations. Client thread only.
 */
public final class InputOverlay {

    private static final int CARD_MIN = 180;
    private static final int CARD_MAX = 280;
    private static final int CARD_LINES = 8;
    /** Kept free above the bottom edge for the hotbar and status bars. */
    private static final int HOTBAR_RESERVE = 48;
    private static final int BACKGROUND_ARGB = 0xA0000000;
    private static final int OK_ARGB = 0xFF77FF77;
    private static final int BAD_ARGB = 0xFFFF6060;
    private static final int TOOL_ARGB = 0xFFFFC040;
    private static final int SELECTED_ARGB = 0x60FFFFFF;
    private static final Component ITEM_PICK_KEYS = Component.translatable("mineflayer_bridge.pick.item.keys");
    private static final Component YES = Component.translatable("mineflayer_bridge.prompt.yes");
    private static final Component NO = Component.translatable("mineflayer_bridge.prompt.no");
    private static final Component PRESS_ENTER = Component.translatable("mineflayer_bridge.prompt.press_enter");

    private long promptLinesId = -1L;
    private int promptLinesWidth;
    private int promptLinesMax;
    private List<String> promptLines = List.of();

    /** Creates the overlay. */
    public InputOverlay() {
    }

    /**
     * Draws the overlays while no screen is open.
     *
     * @param graphics the frame's GUI extractor
     * @param ui the UI layer
     * @param queued input requests waiting behind the active one
     */
    public void render(final GuiGraphicsExtractor graphics, final BridgeUi ui, final int queued) {
        final Font font = Minecraft.getInstance().font;
        final PickController picks = ui.picks();
        if (picks.active()) {
            pickHint(graphics, font, picks, queued);
        } else {
            final PromptInput prompt = ui.prompts().active();
            if (prompt != null && !ui.prompts().inWorld()) {
                promptCard(graphics, font, ui.prompts(), prompt, queued);
            }
            final ToolDecl tool = ui.tools().active();
            if (tool != null) {
                toolLine(graphics, font, tool, ui);
            }
        }
        final List<PlayerFeedback.Pending> pending = ui.pending();
        int y = graphics.guiHeight() - 124;
        for (int i = pending.size() - 1; i >= 0 && y > graphics.guiHeight() / 2; i--) {
            final PlayerFeedback.Pending p = pending.get(i);
            final String dots = ".".repeat((int) ((ui.nowMs() / 300L) % 4L));
            centered(graphics, font, p.owner() + ": " + p.label() + dots, y, UiColors.LABEL);
            y -= 10;
        }
    }

    /**
     * Draws the hint line of the spatial tools or the gizmo editor above the hotbar.
     *
     * @param graphics the frame's GUI extractor
     * @param hint the text
     */
    public void spatialHint(final GuiGraphicsExtractor graphics, final String hint) {
        centered(graphics, Minecraft.getInstance().font, hint, graphics.guiHeight() - 72, TOOL_ARGB);
    }

    private static void centered(final GuiGraphicsExtractor graphics, final Font font, final String full, final int y, final int argb) {
        final String text = TextWrap.ellipsize(full, graphics.guiWidth() - 2 * PanelLayout.MARGIN, UiText.width(font));
        final int w = font.width(text);
        final int x = graphics.guiWidth() / 2 - w / 2;
        graphics.fill(x - 3, y - 2, x + w + 3, y + 9, BACKGROUND_ARGB);
        graphics.text(font, text, x, y, argb);
    }

    private static void centered(final GuiGraphicsExtractor graphics, final Font font, final Component text, final int y, final int argb) {
        final int w = font.width(text);
        final int x = graphics.guiWidth() / 2 - w / 2;
        graphics.fill(x - 3, y - 2, x + w + 3, y + 9, BACKGROUND_ARGB);
        graphics.text(font, text, x, y, argb);
    }

    private static String seconds(final long ms) {
        return I18n.get("mineflayer_bridge.input.seconds", (ms + 999L) / 1000L);
    }

    private static String waiting(final int queued) {
        return queued > 0 ? "  " + I18n.get("mineflayer_bridge.input.waiting", queued) : "";
    }

    private static void pickHint(final GuiGraphicsExtractor graphics, final Font font, final PickController picks, final int queued) {
        final int base = graphics.guiHeight() - 112;
        final long remaining = picks.remainingMs();
        final String header = "[" + picks.owner() + "] " + picks.prompt() + (remaining >= 0 ? "  " + seconds(remaining) : "")
                + waiting(queued);
        centered(graphics, font, header, base, UiColors.TEXT);
        centered(graphics, font, picks.status(), base + 11, picks.showInvalid() ? BAD_ARGB : OK_ARGB);
        centered(graphics, font, picks.keysHelp(), base + 22, UiColors.LABEL);
    }

    /**
     * Draws the item pick hint at the top of a container screen.
     *
     * @param graphics the frame's GUI extractor
     * @param ui the UI layer
     */
    public void renderItemPick(final GuiGraphicsExtractor graphics, final BridgeUi ui) {
        final Font font = Minecraft.getInstance().font;
        final PickController picks = ui.picks();
        final long remaining = picks.remainingMs();
        centered(graphics, font, "[" + picks.owner() + "] " + picks.prompt() + (remaining >= 0 ? "  " + seconds(remaining) : ""), 4, UiColors.TEXT);
        centered(graphics, font, ITEM_PICK_KEYS, 16, picks.showInvalid() ? BAD_ARGB : UiColors.LABEL);
    }

    private void promptCard(final GuiGraphicsExtractor graphics, final Font font, final PromptController prompts, final PromptInput prompt,
            final int queued) {
        final PromptRequest r = prompt.request();
        final ToIntFunction<String> measure = UiText.width(font);
        final int cardWidth = PanelLayout.width(graphics.guiWidth(), CARD_MIN, CARD_MAX, 0.45);
        final List<String> choices = r.choices() == null ? List.of() : r.choices();
        // The card sits below the crosshair and above the hotbar; the question gets what is left.
        final int room = graphics.guiHeight() - HOTBAR_RESERVE - 8;
        final int choiceRows = r.kind() == PromptKind.CHOICE ? Math.max(1, Math.min(choices.size(), 9)) : 1;
        final int fixed = 12 + 4 + 12 + 12;
        final int optionRows = Math.max(1, Math.min(choiceRows, (room - fixed - 10) / 10));
        final int maxLines = Math.clamp((room - fixed - optionRows * 10) / 10, 1, CARD_LINES);
        if (promptLinesId != prompt.id() || promptLinesWidth != cardWidth || promptLinesMax != maxLines) {
            promptLinesId = prompt.id();
            promptLinesWidth = cardWidth;
            promptLinesMax = maxLines;
            promptLines = TextWrap.clamp(r.text(), cardWidth - 8, maxLines, measure).lines();
        }
        final int height = 12 + promptLines.size() * 10 + 4 + optionRows * 10 + 12;
        final int x = graphics.guiWidth() / 2 - cardWidth / 2;
        final int y = Math.max(4, Math.min(graphics.guiHeight() / 2 + 16, graphics.guiHeight() - HOTBAR_RESERVE - height));
        graphics.fill(x, y, x + cardWidth, y + height, BACKGROUND_ARGB);
        graphics.outline(x, y, cardWidth, height, 0x80FFFFFF);
        graphics.text(font, TextWrap.ellipsize(prompt.ownerName() + waiting(queued), cardWidth - 8, measure), x + 4, y + 3, UiColors.HEADER);
        int ly = y + 15;
        for (final String line : promptLines) {
            graphics.text(font, line, x + 4, ly, UiColors.TEXT);
            ly += 10;
        }
        ly += 4;
        final @Nullable Object def = r.defaultValue();
        switch (r.kind()) {
            case CONFIRM -> {
                final boolean yesDefault = Boolean.TRUE.equals(def);
                final boolean noDefault = Boolean.FALSE.equals(def);
                graphics.text(font, YES, x + 8, ly, yesDefault ? OK_ARGB : UiColors.TEXT);
                graphics.text(font, NO, x + 70, ly, noDefault ? BAD_ARGB : UiColors.TEXT);
            }
            case CHOICE -> {
                // Keys pick 1 to 9; with less room the list follows the highlight.
                final int shown = Math.min(Math.min(choices.size(), 9), optionRows);
                final int first = Math.clamp(prompts.selected() - shown + 1, 0, Math.max(0, Math.min(choices.size(), 9) - shown));
                for (int i = first; i < first + shown; i++) {
                    if (i == prompts.selected()) {
                        graphics.fill(x + 2, ly - 1, x + cardWidth - 2, ly + 9, SELECTED_ARGB);
                    }
                    graphics.text(font, TextWrap.ellipsize((i + 1) + ". " + choices.get(i), cardWidth - 12, measure), x + 6, ly, UiColors.TEXT);
                    ly += 10;
                }
                ly -= 10;
            }
            case TEXT, NUMBER -> graphics.text(font, PRESS_ENTER, x + 8, ly, UiColors.TEXT);
            case LINES, PAGES -> {
            }
        }
        ly += 12;
        final String keys = I18n.get(switch (r.kind()) {
            case CONFIRM -> "mineflayer_bridge.prompt.keys.confirm";
            case CHOICE -> "mineflayer_bridge.prompt.keys.choice";
            case TEXT, NUMBER, LINES, PAGES -> "mineflayer_bridge.prompt.keys.enter";
        });
        final String time = seconds(prompts.remainingMs());
        final String footer = def == null ? I18n.get("mineflayer_bridge.prompt.footer", time, keys)
                : I18n.get("mineflayer_bridge.prompt.footer.default", time, def, keys);
        graphics.text(font, TextWrap.ellipsize(footer, cardWidth - 8, measure), x + 4, ly, UiColors.LABEL);
    }

    private static void toolLine(final GuiGraphicsExtractor graphics, final Font font, final ToolDecl tool, final BridgeUi ui) {
        final String key = ui.toolWheelKey().getTranslatedKeyMessage().getString();
        centered(graphics, font, I18n.get("mineflayer_bridge.tool.active", tool.data().label(), tool.ownerName(), key),
                graphics.guiHeight() - 90, TOOL_ARGB);
        // A tinted crosshair tells tool mode apart from normal play.
        final int cx = graphics.guiWidth() / 2;
        final int cy = graphics.guiHeight() / 2;
        graphics.fill(cx - 7, cy - 7, cx - 4, cy - 6, TOOL_ARGB);
        graphics.fill(cx - 7, cy - 7, cx - 6, cy - 4, TOOL_ARGB);
        graphics.fill(cx + 4, cy - 7, cx + 7, cy - 6, TOOL_ARGB);
        graphics.fill(cx + 6, cy - 7, cx + 7, cy - 4, TOOL_ARGB);
        graphics.fill(cx - 7, cy + 6, cx - 4, cy + 7, TOOL_ARGB);
        graphics.fill(cx - 7, cy + 4, cx - 6, cy + 7, TOOL_ARGB);
        graphics.fill(cx + 4, cy + 6, cx + 7, cy + 7, TOOL_ARGB);
        graphics.fill(cx + 6, cy + 4, cx + 7, cy + 7, TOOL_ARGB);
    }
}
