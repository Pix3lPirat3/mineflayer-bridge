package io.github.pix3lpirat3.mfbridge.common.ui.inspector;

import io.github.pix3lpirat3.mfbridge.common.ui.hud.HudRenderer;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.HudPlacement;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayout;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayouts;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The HUD editor: the bot HUD stays drawn underneath, and the player drags panels
 * between anchor slots or to free positions, drags a panel's right edge to resize it, and hides,
 * shows or resets panels. Every change is saved at once under {@code player} preferences, per bot
 * identity and panel with a fallback per extension and panel.
 */
public final class HudEditorScreen extends ChildScreen {
    private static final int HANDLE = 5;
    private static final int OUTLINE_ARGB = 0xC0FFFFFF;
    private static final int SELECTED_ARGB = 0xFFFFD84A;
    private static final int HIDDEN_ARGB = 0x60404040;
    private static final int GHOST_ARGB = 0x4060A0FF;
    private static final int ANCHOR_ARGB = 0x80FFFFFF;
    private static final Component EMPTY = Component.translatable("screen.mineflayer_bridge.hud_editor.empty");
    private static final Component HELP = Component.translatable("screen.mineflayer_bridge.hud_editor.help");

    private enum Drag {
        NONE,
        MOVE,
        RESIZE
    }

    private final Supplier<@Nullable BridgeCore> core;
    private final HudRenderer hud;
    private @Nullable String selected;
    private Drag drag = Drag.NONE;
    private HudRenderer.@Nullable EditorPanel dragged;
    private double grabX;
    private double grabY;
    private int ghostX;
    private int ghostY;
    private int ghostWidth;

    /**
     * Creates the editor.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param core the running core, or null while the endpoint is down
     * @param hud the HUD renderer whose panels are edited
     */
    public HudEditorScreen(final @Nullable Screen parent, final Supplier<@Nullable BridgeCore> core, final HudRenderer hud) {
        super(Component.translatable("screen.mineflayer_bridge.hud_editor.title"), parent);
        this.core = core;
        this.hud = hud;
    }

    @Override
    protected void init() {
        final int y = height - 26;
        final int cx = width / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.hud_editor.toggle_hidden"), b -> toggleHidden()).bounds(cx - 206, y, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.hud_editor.reset"), b -> reset()).bounds(cx - 122, y, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.hud_editor.reset_all"), b -> {
            final HudLayouts l = layouts();
            if (l != null) {
                l.resetAll();
            }
        }).bounds(cx - 38, y, 80, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(cx + 126, y, 80, 20).build());
    }

    private @Nullable HudLayouts layouts() {
        final BridgeCore c = core.get();
        return c == null ? null : c.hudLayouts();
    }

    private HudRenderer.@Nullable EditorPanel find(final @Nullable String key) {
        if (key == null) {
            return null;
        }
        for (final HudRenderer.EditorPanel p : hud.editorPanels()) {
            if (p.key().equals(key)) {
                return p;
            }
        }
        return null;
    }

    private void toggleHidden() {
        final HudLayouts l = layouts();
        final HudRenderer.EditorPanel p = find(selected);
        if (l == null || p == null) {
            return;
        }
        l.set(p.key(), current(p).withHidden(!p.hidden()));
    }

    private void reset() {
        final HudLayouts l = layouts();
        if (l != null && selected != null) {
            l.reset(selected);
        }
    }

    /** The panel's layout as saved, or one that keeps it in its declared slot. */
    private static HudLayout current(final HudRenderer.EditorPanel p) {
        final HudLayout saved = p.saved();
        return saved != null ? saved : HudLayout.anchored(p.declared());
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        final List<HudRenderer.EditorPanel> panels = hud.editorPanels();
        for (int i = panels.size() - 1; i >= 0; i--) {
            final HudRenderer.EditorPanel p = panels.get(i);
            if (event.x() >= p.x() && event.x() < p.x() + p.width() && event.y() >= p.y() && event.y() < p.y() + p.height()) {
                selected = p.key();
                dragged = p;
                drag = event.x() >= p.x() + p.width() - HANDLE ? Drag.RESIZE : Drag.MOVE;
                grabX = event.x() - p.x();
                grabY = event.y() - p.y();
                ghostX = p.x();
                ghostY = p.y();
                ghostWidth = p.width();
                return true;
            }
        }
        selected = null;
        return false;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        final HudRenderer.EditorPanel p = dragged;
        if (p == null || drag == Drag.NONE) {
            return super.mouseDragged(event, dx, dy);
        }
        if (drag == Drag.MOVE) {
            ghostX = (int) Math.round(event.x() - grabX);
            ghostY = (int) Math.round(event.y() - grabY);
        } else {
            ghostWidth = Math.clamp((int) Math.round(event.x()) - p.x(), HudPlacement.MIN_WIDTH, HudPlacement.MAX_WIDTH);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        final HudRenderer.EditorPanel p = dragged;
        final Drag mode = drag;
        dragged = null;
        drag = Drag.NONE;
        final HudLayouts l = layouts();
        if (p == null || mode == Drag.NONE || l == null) {
            return super.mouseReleased(event);
        }
        if (mode == Drag.MOVE) {
            if (ghostX != p.x() || ghostY != p.y()) {
                l.set(p.key(), HudPlacement.dropped(ghostX, ghostY, p.width(), p.height(), width, height, p.saved()));
            }
        } else if (ghostWidth != p.width()) {
            l.set(p.key(), current(p).withWidth(ghostWidth));
        }
        return true;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        // No backdrop: the bot HUD drawn underneath is what is being edited.
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        for (final HudAnchor anchor : HudAnchor.values()) {
            final int ax = switch (anchor) {
                case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> HudPlacement.MARGIN;
                case TOP_CENTER -> width / 2;
                case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> width - HudPlacement.MARGIN;
            };
            final int ay = switch (anchor) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> HudPlacement.MARGIN;
                case MIDDLE_LEFT, MIDDLE_RIGHT -> height / 2;
                case BOTTOM_LEFT, BOTTOM_RIGHT -> height - HudPlacement.MARGIN;
            };
            graphics.fill(ax - 3, ay - 3, ax + 3, ay + 3, ANCHOR_ARGB);
        }
        final List<HudRenderer.EditorPanel> panels = hud.editorPanels();
        for (final HudRenderer.EditorPanel p : panels) {
            if (p.hidden()) {
                graphics.fill(p.x(), p.y(), p.x() + p.width(), p.y() + p.height(), HIDDEN_ARGB);
                graphics.text(font, font.plainSubstrByWidth(I18n.get("screen.mineflayer_bridge.hud_editor.panel.hidden", p.title()), p.width() - 4), p.x() + 2, p.y() + 2, UiColors.LABEL);
            }
            outline(graphics, p.x(), p.y(), p.width(), p.height(), p.key().equals(selected) ? SELECTED_ARGB : OUTLINE_ARGB);
            graphics.fill(p.x() + p.width() - 2, p.y() + p.height() / 2 - 4, p.x() + p.width(), p.y() + p.height() / 2 + 4, OUTLINE_ARGB);
        }
        final HudRenderer.EditorPanel d = dragged;
        if (d != null && drag != Drag.NONE) {
            final int w = drag == Drag.RESIZE ? ghostWidth : d.width();
            final int x = drag == Drag.RESIZE ? d.x() : ghostX;
            final int y = drag == Drag.RESIZE ? d.y() : ghostY;
            graphics.fill(x, y, x + w, y + d.height(), GHOST_ARGB);
            final HudAnchor snap = drag == Drag.MOVE ? HudPlacement.snap(x, y, w, d.height(), width, height) : null;
            outline(graphics, x, y, w, d.height(), snap != null ? UiColors.GOOD : SELECTED_ARGB);
            final String hint = drag == Drag.RESIZE ? I18n.get("screen.mineflayer_bridge.hud_editor.width", w)
                    : snap != null ? I18n.get("screen.mineflayer_bridge.hud_editor.snap", snap.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    : I18n.get("screen.mineflayer_bridge.hud_editor.free");
            graphics.text(font, hint, x, Math.max(0, y - 10), snap != null ? UiColors.GOOD : UiColors.TEXT);
        }
        if (panels.isEmpty()) {
            graphics.centeredText(font, EMPTY, width / 2, height / 2, UiColors.LABEL);
        }
        final HudRenderer.EditorPanel sel = find(selected);
        final Component info;
        if (sel == null) {
            info = HELP;
        } else {
            final String placed = I18n.get(sel.saved() == null ? "screen.mineflayer_bridge.hud_editor.selected.declared"
                    : sel.saved().isFree() ? "screen.mineflayer_bridge.hud_editor.selected.free" : "screen.mineflayer_bridge.hud_editor.selected.slot",
                    sel.title(), sel.owner());
            info = Component.literal(sel.hidden() ? I18n.get("screen.mineflayer_bridge.hud_editor.selected.hidden", placed) : placed);
        }
        graphics.centeredText(font, info, width / 2, height - 38, UiColors.TEXT);
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private static void outline(final GuiGraphicsExtractor graphics, final int x, final int y, final int w, final int h, final int argb) {
        graphics.fill(x, y, x + w, y + 1, argb);
        graphics.fill(x, y + h - 1, x + w, y + h, argb);
        graphics.fill(x, y, x + 1, y + h, argb);
        graphics.fill(x + w - 1, y, x + w, y + h, argb);
    }
}
