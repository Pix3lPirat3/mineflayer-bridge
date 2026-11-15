package io.github.pix3lpirat3.mfbridge.common.ui.declared;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link ScreenData} with vanilla widgets: a tab bar when there is
 * more than one tab, each tab a scrolling column of rows, each row its items side by side. Bridge home
 * and bot screens both extend it, so a bot can build anything Bridge home shows.
 *
 * <p>The model is polled twice a second; when it changed, the widgets are built again and the tab and
 * scroll position are kept. Client thread only.
 */
public abstract class ScreenView extends ChildScreen {

    /** Width of a tab's column, in GUI pixels. */
    public static final int COLUMN_WIDTH = 320;
    /** Gap between items and rows, in GUI pixels. */
    public static final int GAP = 4;
    private static final int MIN_FLEX_WIDTH = 40;
    private static final int TEXT_ROWS = 4;
    private static final int REFRESH_TICKS = 10;
    private static final int FOOTER_BUTTONS = 33;
    private static final int FOOTER_LINE = 19;

    /** Set by {@link #init()}, which runs before any other use. */
    private HeaderAndFooterLayout layout;
    private TabManager tabManager;
    private @Nullable MenuTabBar tabBar;
    private List<ScreenTab> tabs = List.of();
    private List<String> tabIds = List.of();
    private final Map<String, Double> scrolls = new HashMap<>();
    private @Nullable String currentTabId;
    private @Nullable ScreenData shown;
    /** Every item of the shown model with the widget built for it, in model order. */
    private final List<Bound> bound = new ArrayList<>();
    private @Nullable StringWidget statusLine;
    private boolean stale;
    private int ticks;

    protected ScreenView(final Component title, final @Nullable Screen parent) {
        super(title, parent);
    }

    /**
     * Returns what to draw now. Called on every poll; a value equal to the last one keeps the widgets.
     *
     * @return the model
     */
    protected abstract ScreenData model();

    /**
     * The player used a control.
     *
     * @param item the button, toggle or cycle
     * @param value null for a click, the new {@code Boolean} of a toggle or the {@code Long} index of a cycle
     */
    protected abstract void use(ScreenItem item, @Nullable Object value);

    /**
     * Returns a line shown above the footer buttons.
     *
     * @return the line, or null for none
     */
    protected @Nullable Component status() {
        return null;
    }

    /**
     * Adds buttons left of Done.
     *
     * @param row the footer button row
     */
    protected void footerButtons(final LinearLayout row) {
    }

    /**
     * Returns the id of the tab shown.
     *
     * @return the id, or null before the first tick
     */
    protected final @Nullable String currentTabId() {
        return currentTabId;
    }

    /**
     * Selects the tab to show on the next build, when the model has it.
     *
     * @param id a tab id
     */
    protected final void selectTab(final @Nullable String id) {
        currentTabId = id;
    }

    /** Builds the widgets again on the next tick, from the current model; for a refused value change. */
    protected final void invalidate() {
        stale = true;
    }

    @Override
    protected void init() {
        final ScreenData data = model();
        shown = data;
        bound.clear();
        final Component line = status();
        layout = new HeaderAndFooterLayout(this, HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT,
                FOOTER_BUTTONS + (line == null ? 0 : FOOTER_LINE));
        tabManager = new TabManager(w -> addRenderableWidget(w), this::removeWidget);
        final List<ScreenTab> built = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        for (final io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab t : data.tabs()) {
            built.add(new ScreenTab(minecraft, Component.literal(t.label()), column(t.rows())));
            ids.add(t.id());
        }
        if (built.isEmpty()) {
            final LinearLayout empty = emptyColumn();
            empty.addChild(text(Component.translatable("mineflayer_bridge.screen.empty"), UiColors.LABEL, COLUMN_WIDTH));
            built.add(new ScreenTab(minecraft, Component.literal(data.title()), empty));
            ids.add("");
        }
        tabs = built;
        tabIds = ids;
        if (tabs.size() > 1) {
            final MenuTabBar bar = MenuTabBar.builder(tabManager, width).addTabs(tabs.toArray(Tab[]::new)).build();
            tabBar = bar;
            addRenderableWidget(bar);
        } else {
            tabBar = null;
            layout.addTitleHeader(Component.literal(data.title()), font);
            tabManager.setCurrentTab(tabs.getFirst(), false);
        }

        final LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(GAP));
        footer.defaultCellSetting().alignHorizontallyCenter();
        statusLine = line == null ? null : footer.addChild(new StringWidget(line, font).setMaxWidth(width - 16));
        final LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(GAP));
        footerButtons(buttons);
        buttons.addChild(Button.builder(parent() == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose())
                .width(150).build());
        layout.visitWidgets(w -> {
            w.setTabOrderGroup(1);
            addRenderableWidget(w);
        });
        final MenuTabBar bar = tabBar;
        if (bar != null) {
            bar.selectTab(Math.max(0, currentTabId == null ? 0 : tabIds.indexOf(currentTabId)), false);
        }
        repositionElements();
        final int current = currentTab();
        if (current >= 0) {
            tabs.get(current).setScrollAmount(scrolls.getOrDefault(tabIds.get(current), 0d));
        }
    }

    private int currentTab() {
        final Tab tab = tabManager.getCurrentTab();
        return IntStream.range(0, tabs.size()).filter(i -> tabs.get(i) == tab).findFirst().orElse(-1);
    }

    @Override
    protected void repositionElements() {
        final MenuTabBar bar = tabBar;
        final int top;
        if (bar != null) {
            bar.arrangeElements(width);
            top = bar.getRectangle().bottom();
            layout.setHeaderHeight(top);
        } else {
            top = layout.getHeaderHeight();
        }
        tabManager.setTabArea(new ScreenRectangle(0, top, width, height - layout.getFooterHeight() - top));
        layout.arrangeElements();
    }

    @Override
    public void tick() {
        super.tick();
        final int current = currentTab();
        if (current >= 0) {
            currentTabId = tabIds.get(current);
            scrolls.put(currentTabId, tabs.get(current).scrollAmount());
        }
        if (stale) {
            stale = false;
            rebuildWidgets();
        } else if (++ticks % REFRESH_TICKS == 0) {
            refresh();
        }
    }

    /**
     * Applies the current model. Changed text, values, tooltips and enabled states are set on the
     * widgets in place, so live data neither flickers nor eats a click; anything else rebuilds.
     */
    private void refresh() {
        final StringWidget line = statusLine;
        final Component status = line == null ? null : status();
        if (line != null && status != null && !status.equals(line.getMessage())) {
            line.setMessage(status);
        }
        final ScreenData next = model();
        final ScreenData previous = shown;
        if (next.equals(previous)) {
            return;
        }
        if (previous == null || !shape(next).equals(shape(previous))) {
            rebuildWidgets();
            return;
        }
        final List<ScreenItem> items = items(next);
        for (int i = 0; i < items.size(); i++) {
            final Bound b = bound.get(i);
            final ScreenItem it = items.get(i);
            if (!it.equals(b.item())) {
                patch(b.element(), it);
                bound.set(i, new Bound(it, b.element()));
            }
        }
        shown = next;
        repositionElements();
    }

    private record Bound(ScreenItem item, LayoutElement element) {
    }

    /** What a widget cannot change in place: tabs, rows and each item's kind, id, size, icon, link and choices. */
    private static List<Object> shape(final ScreenData data) {
        final List<Object> out = new ArrayList<>();
        out.add(data.title());
        for (final io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab t : data.tabs()) {
            out.add(t.id());
            out.add(t.label());
            for (final ScreenRow r : t.rows()) {
                out.add(r.items().size());
                for (final ScreenItem it : r.items()) {
                    final boolean labelled = it.kind().equals("toggle") || it.kind().equals("cycle");
                    out.add(List.of(it.kind(), String.valueOf(it.id()), String.valueOf(it.width()), String.valueOf(it.icon()),
                            String.valueOf(it.open()), String.valueOf(it.options()), labelled ? String.valueOf(it.text()) : ""));
                }
            }
        }
        return out;
    }

    private static List<ScreenItem> items(final ScreenData data) {
        final List<ScreenItem> out = new ArrayList<>();
        for (final io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab t : data.tabs()) {
            for (final ScreenRow r : t.rows()) {
                out.addAll(r.items());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void patch(final LayoutElement element, final ScreenItem it) {
        if (!(element instanceof AbstractWidget widget)) {
            return;
        }
        final Component label = Component.literal(it.text() == null ? "" : it.text());
        switch (it.kind()) {
            case "heading" -> widget.setMessage(Component.empty().append(label.copy().withStyle(ChatFormatting.BOLD)));
            case "text" -> widget.setMessage(colored(label, it.color() == null ? UiColors.TEXT : it.color()));
            case "toggle" -> ((CycleButton<Boolean>) widget).setValue(Boolean.TRUE.equals(it.value()));
            case "cycle" -> ((CycleButton<Long>) widget).setValue(it.value() instanceof Number n ? n.longValue() : 0L);
            default -> widget.setMessage(label);
        }
        widget.setTooltip(it.tooltip() == null ? null : Tooltip.create(Component.literal(it.tooltip())));
        widget.active = it.enabled();
    }

    private LinearLayout column(final List<ScreenRow> rows) {
        final LinearLayout column = emptyColumn();
        for (final ScreenRow row : rows) {
            for (final List<Sized> line : lines(row.items())) {
                final LinearLayout h = LinearLayout.horizontal().spacing(GAP);
                for (final Sized s : line) {
                    h.addChild(widget(s.item(), s.width()));
                }
                final boolean heading = !line.isEmpty() && line.getFirst().item().kind().equals("heading");
                column.addChild(h, c -> c.paddingTop(heading ? GAP * 2 : 0));
            }
        }
        return column;
    }

    private record Sized(ScreenItem item, int width) {
    }

    /**
     * Splits a row into lines that fit the column. Items with a width keep it; the others share what
     * is left of their line, at least {@link #MIN_FLEX_WIDTH} each.
     */
    private static List<List<Sized>> lines(final List<ScreenItem> items) {
        final List<List<ScreenItem>> groups = new ArrayList<>();
        List<ScreenItem> line = new ArrayList<>();
        int used = 0;
        for (final ScreenItem it : items) {
            final int w = it.width() == null ? MIN_FLEX_WIDTH : Math.min(it.width(), COLUMN_WIDTH);
            final int next = used + (line.isEmpty() ? 0 : GAP) + w;
            if (!line.isEmpty() && next > COLUMN_WIDTH) {
                groups.add(line);
                line = new ArrayList<>();
                used = w;
            } else {
                used = next;
            }
            line.add(it);
        }
        if (!line.isEmpty()) {
            groups.add(line);
        }
        final List<List<Sized>> out = new ArrayList<>(groups.size());
        for (final List<ScreenItem> g : groups) {
            int fixed = GAP * (g.size() - 1);
            int flex = 0;
            for (final ScreenItem it : g) {
                if (it.width() == null) {
                    flex++;
                } else {
                    fixed += Math.min(it.width(), COLUMN_WIDTH);
                }
            }
            final int share = flex == 0 ? 0 : Math.max(MIN_FLEX_WIDTH, (COLUMN_WIDTH - fixed) / flex);
            out.add(g.stream().map(it -> new Sized(it, it.width() == null ? share : Math.min(it.width(), COLUMN_WIDTH))).toList());
        }
        return out;
    }

    private LayoutElement widget(final ScreenItem it, final int w) {
        final Component label = Component.literal(it.text() == null ? "" : it.text());
        final LayoutElement element = switch (it.kind()) {
            case "heading" -> new StringWidget(Component.empty().append(label.copy().withStyle(ChatFormatting.BOLD)), font).setMaxWidth(w);
            case "text" -> text(label, it.color() == null ? UiColors.TEXT : it.color(), w);
            case "button" -> ItemIconButton.create(w, label, it.icon(), b -> use(it, null));
            case "toggle" -> CycleButton.onOffBuilder(Boolean.TRUE.equals(it.value()))
                    .create(0, 0, w, Button.DEFAULT_HEIGHT, label, (b, v) -> use(it, v));
            case "cycle" -> cycle(it, label, w);
            case "item" -> new ItemRowWidget(w, it.icon(), label, font);
            default -> SpacerElement.width(w);
        };
        if (element instanceof AbstractWidget widget) {
            if (it.tooltip() != null) {
                widget.setTooltip(Tooltip.create(Component.literal(it.tooltip())));
            }
            widget.active = it.enabled();
        }
        bound.add(new Bound(it, element));
        return element;
    }

    private LayoutElement cycle(final ScreenItem it, final Component label, final int w) {
        final List<String> options = it.options() == null || it.options().isEmpty() ? List.of("") : it.options();
        final List<Long> indexes = IntStream.range(0, options.size()).mapToObj(i -> (long) i).toList();
        final long chosen = it.value() instanceof Number n ? Math.clamp(n.longValue(), 0, options.size() - 1) : 0;
        return CycleButton.<Long>builder(i -> Component.literal(options.get(i.intValue())), chosen)
                .withValues(indexes)
                .create(0, 0, w, Button.DEFAULT_HEIGHT, label, (b, v) -> use(it, v));
    }

    private static LinearLayout emptyColumn() {
        final LinearLayout column = LinearLayout.vertical().spacing(2);
        column.defaultCellSetting().alignHorizontallyLeft();
        column.addChild(SpacerElement.width(COLUMN_WIDTH));
        return column;
    }

    /** Text that wraps at {@code w}, in a colour unless the text has its own. */
    private MultiLineTextWidget text(final Component message, final int argb, final int w) {
        return new MultiLineTextWidget(colored(message, argb), font).setMaxWidth(w).setMaxRows(TEXT_ROWS);
    }

    private static Component colored(final Component message, final int argb) {
        final MutableComponent styled = message.copy();
        if (styled.getStyle().getColor() == null) {
            styled.withColor(argb & 0xFFFFFF);
        }
        return styled;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final MenuTabBar bar = tabBar;
        return bar != null && bar.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, height - layout.getFooterHeight(), 0.0F, 0.0F, width, 2, 32, 2);
    }

    @Override
    protected void extractMenuBackground(final GuiGraphicsExtractor graphics) {
        if (tabBar != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND, 0, 0, 0.0F, 0.0F, width,
                    layout.getHeaderHeight(), 16, 16);
            extractMenuBackground(graphics, 0, layout.getHeaderHeight(), width, height);
        } else {
            super.extractMenuBackground(graphics);
        }
    }
}
