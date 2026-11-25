package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.FuzzyMatch;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The command palette: a search box over the commands and global
 * actions of attached bots, fuzzy matched and grouped by owner. Up and Down select, Enter or a click
 * runs the entry.
 */
public final class PaletteScreen extends ChildScreen {

    private static final int WIDTH = 320;
    private static final int ROW = 12;
    private static final int LIST_TOP = 40;
    private static final int DETAIL_ARGB = 0xFF909090;
    private static final int OWNER_ARGB = 0xFF7FB2FF;
    private static final int SELECTED_ARGB = 0x60FFFFFF;
    private static final int BACKGROUND_ARGB = 0xB0000000;
    private static final Component EMPTY = Component.translatable("screen.mineflayer_bridge.palette.empty");
    private static final Component NO_MATCH = Component.translatable("mineflayer_bridge.no_match");

    /**
     * One runnable entry.
     *
     * @param label the command name or action label
     * @param detail a description or argument summary, possibly empty
     * @param owner the bot that declared it
     * @param run what selecting the entry does; called after the palette closed
     */
    public record Entry(String label, String detail, String owner, Runnable run) {
    }

    private record Row(@Nullable Entry entry, String header) {
    }

    private final List<Entry> entries;
    private final List<Row> rows = new ArrayList<>();
    private String query = "";
    private int selected;
    private int scroll;

    /**
     * Creates the palette.
     *
     * @param entries the entries in owner order
     */
    public PaletteScreen(final List<Entry> entries) {
        this(Component.translatable("screen.mineflayer_bridge.palette.title"), entries);
    }

    /**
     * Creates the palette with its own title, for a palette narrowed to one session.
     *
     * @param title the title, narrated and used as the search box label
     * @param entries the entries in display order
     */
    public PaletteScreen(final Component title, final List<Entry> entries) {
        super(title, null);
        this.entries = List.copyOf(entries);
    }

    @Override
    protected void init() {
        final EditBox search = new EditBox(font, width / 2 - WIDTH / 2, 16, WIDTH, 18, title);
        search.setMaxLength(256);
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            refilter();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        refilter();
    }

    private void refilter() {
        rows.clear();
        final String q = query.strip();
        if (q.isEmpty()) {
            String owner = null;
            for (final Entry e : entries) {
                if (!e.owner().equals(owner)) {
                    owner = e.owner();
                    rows.add(new Row(null, owner));
                }
                rows.add(new Row(e, ""));
            }
        } else {
            final List<Scored> scored = new ArrayList<>();
            for (final Entry e : entries) {
                final int s = Math.max(FuzzyMatch.score(q, e.label()), FuzzyMatch.score(q, e.owner() + " " + e.label()) - 4);
                if (s > 0) {
                    scored.add(new Scored(e, s));
                }
            }
            scored.sort(Comparator.comparingInt(Scored::score).reversed());
            for (final Scored s : scored) {
                rows.add(new Row(s.entry(), ""));
            }
        }
        selected = firstEntry(0, 1);
        scroll = 0;
    }

    private record Scored(Entry entry, int score) {
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 16) / ROW);
    }

    private int firstEntry(final int from, final int direction) {
        for (int i = from; i >= 0 && i < rows.size(); i += direction) {
            if (rows.get(i).entry() != null) {
                return i;
            }
        }
        return -1;
    }

    private void move(final int direction) {
        if (selected < 0) {
            return;
        }
        final int next = firstEntry(selected + direction, direction);
        if (next >= 0) {
            selected = next;
        }
        if (selected < scroll) {
            scroll = Math.max(0, selected - 1);
        } else if (selected >= scroll + visibleRows()) {
            scroll = selected - visibleRows() + 1;
        }
    }

    private void run(final int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        final Entry entry = rows.get(index).entry();
        if (entry == null) {
            return;
        }
        Game.setScreen(null);
        entry.run().run();
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isUp()) {
            move(-1);
            return true;
        }
        if (event.isDown()) {
            move(1);
            return true;
        }
        if (event.isConfirmation()) {
            run(selected);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        final int left = width / 2 - WIDTH / 2;
        if (event.x() >= left && event.x() < left + WIDTH && event.y() >= LIST_TOP) {
            final int index = scroll + (int) ((event.y() - LIST_TOP) / ROW);
            if (index < rows.size() && index < scroll + visibleRows()) {
                run(index);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        move(scrollY > 0 ? -1 : 1);
        return true;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final int left = width / 2 - WIDTH / 2;
        final int shown = Math.min(visibleRows(), rows.size() - scroll);
        graphics.fill(left - 2, LIST_TOP - 2, left + WIDTH + 2, LIST_TOP + Math.max(shown, 1) * ROW + 2, BACKGROUND_ARGB);
        if (rows.isEmpty()) {
            graphics.text(font, entries.isEmpty() ? EMPTY : NO_MATCH, left + 4, LIST_TOP + 2, DETAIL_ARGB);
            return;
        }
        for (int i = 0; i < shown; i++) {
            final int index = scroll + i;
            final Row row = rows.get(index);
            final int y = LIST_TOP + i * ROW;
            final Entry e = row.entry();
            if (e == null) {
                graphics.text(font, row.header(), left + 2, y + 2, OWNER_ARGB);
                continue;
            }
            if (index == selected) {
                graphics.fill(left, y, left + WIDTH, y + ROW, SELECTED_ARGB);
            }
            final int labelWidth = font.width(e.label());
            graphics.text(font, e.label(), left + 10, y + 2, UiColors.TEXT);
            final String owner = e.owner();
            final int ownerWidth = font.width(owner);
            if (!query.isBlank()) {
                graphics.text(font, owner, left + WIDTH - ownerWidth - 4, y + 2, OWNER_ARGB);
            }
            final int detailRoom = WIDTH - 24 - labelWidth - (query.isBlank() ? 0 : ownerWidth + 8);
            if (!e.detail().isEmpty() && detailRoom > 20) {
                graphics.text(font, font.plainSubstrByWidth(e.detail(), detailRoom), left + 16 + labelWidth, y + 2, DETAIL_ARGB);
            }
        }
    }
}
