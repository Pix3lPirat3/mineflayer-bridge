package io.github.pix3lpirat3.mfbridge.common.ext.console;

import io.github.pix3lpirat3.mfbridge.common.ext.console.wire.ConsoleLevel;
import io.github.pix3lpirat3.mfbridge.common.ext.console.wire.ConsoleLine;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One bot's console: its lines, newest at the bottom, filtered by a search and a lowest level.
 * Clicking a line that names a position marks it in the world; Enter in the input line sends it to the
 * bot. New lines keep the view at the bottom unless the player scrolled up. Client thread only.
 */
final class ConsoleScreen extends ChildScreen {
    private static final Component EMPTY = Component.translatable("mineflayer_bridge_console.empty");
    private static final Component NO_MATCH = Component.translatable("mineflayer_bridge_console.no_match");

    private static final int ROW = 11;
    private static final int TOP = 40;
    private static final int BOTTOM_BAR = 30;
    private static final int BACKGROUND_ARGB = 0xC0101010;
    private static final int TIME_ARGB = 0xFF808080;
    private static final int POS_ARGB = 0xFF7FD4FF;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final List<ConsoleLevel> LEVELS = List.of(ConsoleLevel.DEBUG, ConsoleLevel.INFO, ConsoleLevel.WARN, ConsoleLevel.ERROR);

    private final ConsoleExtension console;
    private final String owner;
    private final List<ConsoleLine> shown = new ArrayList<>();
    private String query = "";
    private ConsoleLevel lowest = ConsoleLevel.DEBUG;
    /** Lines scrolled up from the bottom; 0 follows new lines. */
    private int fromBottom;
    private @Nullable EditBox input;
    private @Nullable Component status;

    ConsoleScreen(final ConsoleExtension console, final String owner, final String name, final @Nullable Screen parent) {
        super(Component.translatable("mineflayer_bridge_console.title", name), parent);
        this.console = console;
        this.owner = owner;
    }

    /** The bot this console shows. */
    String owner() {
        return owner;
    }

    /** New lines arrived. */
    void linesChanged() {
        final int before = shown.size();
        refilter();
        if (fromBottom > 0) {
            fromBottom += Math.max(0, shown.size() - before);
        }
    }

    @Override
    protected void init() {
        final int w = Math.min(width - 20, 520);
        final int left = (width - w) / 2;
        final EditBox search = new EditBox(font, left, 16, w - 190, 18, Component.translatable("mineflayer_bridge_console.search"));
        search.setHint(Component.translatable("mineflayer_bridge_console.search"));
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            fromBottom = 0;
            refilter();
        });
        addRenderableWidget(search);
        addRenderableWidget(CycleButton.<ConsoleLevel>builder(l -> Component.translatable(label(l)), lowest).withValues(LEVELS)
                .create(left + w - 186, 15, 90, 20, Component.translatable("mineflayer_bridge_console.level"), (b, v) -> {
                    lowest = v;
                    fromBottom = 0;
                    refilter();
                }));
        addRenderableWidget(Button.builder(Component.translatable("mineflayer_bridge_console.clear"), b -> {
            console.clear(owner);
            refilter();
        }).bounds(left + w - 92, 15, 44, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("mineflayer_bridge_console.unmark"), b -> console.unmark()).bounds(left + w - 46, 15, 46, 20).build());
        final EditBox line = new EditBox(font, left, height - 24, w, 18, Component.translatable("mineflayer_bridge_console.input"));
        line.setHint(Component.translatable("mineflayer_bridge_console.input.hint"));
        line.setMaxLength(256);
        addRenderableWidget(line);
        input = line;
        setInitialFocus(line);
        refilter();
    }

    private static String label(final ConsoleLevel l) {
        return switch (l) {
            case DEBUG -> "mineflayer_bridge_console.level.all";
            case INFO -> "mineflayer_bridge_console.level.info";
            case WARN -> "mineflayer_bridge_console.level.warn";
            case ERROR -> "mineflayer_bridge_console.level.error";
        };
    }

    private void refilter() {
        shown.clear();
        final String q = query.strip().toLowerCase(Locale.ROOT);
        for (final ConsoleLine l : console.lines(owner)) {
            if (l.level().ordinal() >= lowest.ordinal() && (q.isEmpty() || l.text().toLowerCase(Locale.ROOT).contains(q))) {
                shown.add(l);
            }
        }
        fromBottom = Math.min(fromBottom, Math.max(0, shown.size() - visibleRows()));
    }

    private int visibleRows() {
        return Math.max(1, (height - TOP - BOTTOM_BAR) / ROW);
    }

    /** Index of the first line drawn. */
    private int first() {
        return Math.max(0, shown.size() - visibleRows() - fromBottom);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final EditBox in = input;
        if (event.isConfirmation() && in != null && in.isFocused() && !in.getValue().isBlank()) {
            final String why = console.input(owner, in.getValue().strip());
            status = why == null ? null : Component.translatable("mineflayer_bridge_console.not_sent", why);
            if (why == null) {
                in.setValue("");
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        final int max = Math.max(0, shown.size() - visibleRows());
        fromBottom = Math.clamp(fromBottom + (scrollY > 0 ? 3 : -3), 0, max);
        return true;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.y() < TOP || event.y() >= TOP + visibleRows() * ROW) {
            return false;
        }
        final int index = first() + (int) ((event.y() - TOP) / ROW);
        if (index >= shown.size()) {
            return false;
        }
        final ConsoleLine l = shown.get(index);
        final Vec3d p = l.pos();
        if (p != null) {
            console.mark(p, position(p));
            return true;
        }
        return false;
    }

    private static String position(final Vec3d p) {
        return Math.round(p.x()) + " " + Math.round(p.y()) + " " + Math.round(p.z());
    }

    private static int color(final ConsoleLevel l) {
        return switch (l) {
            case DEBUG -> UiColors.LABEL;
            case INFO -> UiColors.TEXT;
            case WARN -> UiColors.WARNING;
            case ERROR -> UiColors.ERROR;
        };
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final int w = Math.min(width - 20, 520);
        final int left = (width - w) / 2;
        final int rows = visibleRows();
        graphics.fill(left - 2, TOP - 2, left + w + 2, TOP + rows * ROW + 2, BACKGROUND_ARGB);
        graphics.text(font, title, left, 4, UiColors.HEADER);
        if (shown.isEmpty()) {
            graphics.text(font, console.lines(owner).isEmpty() ? EMPTY : NO_MATCH, left + 4, TOP + 2, UiColors.LABEL);
        }
        final int start = first();
        for (int i = 0; i < rows && start + i < shown.size(); i++) {
            final ConsoleLine l = shown.get(start + i);
            final int y = TOP + i * ROW + 1;
            final String time = TIME.format(Instant.ofEpochMilli(l.time()));
            graphics.text(font, time, left + 2, y, TIME_ARGB);
            int x = left + 6 + font.width(time);
            final Vec3d p = l.pos();
            final String pos = p == null ? "" : "[" + position(p) + "]";
            final int room = w - (x - left) - (pos.isEmpty() ? 4 : font.width(pos) + 10);
            graphics.text(font, font.plainSubstrByWidth(l.text(), Math.max(0, room)), x, y, color(l.level()));
            if (!pos.isEmpty()) {
                graphics.text(font, pos, left + w - font.width(pos) - 4, y, POS_ARGB);
            }
        }
        if (fromBottom > 0) {
            graphics.text(font, fromBottom + " newer below", left + w - 90, TOP + rows * ROW + 4, UiColors.LABEL);
        }
        final Component s = status;
        if (s != null) {
            graphics.text(font, s, left, height - 36, UiColors.ERROR);
        }
    }
}
