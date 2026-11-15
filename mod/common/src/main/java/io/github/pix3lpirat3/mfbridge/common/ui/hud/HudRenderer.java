package io.github.pix3lpirat3.mfbridge.common.ui.hud;

import io.github.pix3lpirat3.mfbridge.common.mixin.BossHealthOverlayAccessor;
import io.github.pix3lpirat3.mfbridge.common.mixin.ToastManagerAccessor;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.HudPlacement;
import io.github.pix3lpirat3.mfbridge.core.vars.TemplateCache;
import io.github.pix3lpirat3.mfbridge.core.vars.Templates;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.decl.BossbarDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.HudPanel;
import io.github.pix3lpirat3.mfbridge.core.decl.HudWidget;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayout;
import io.github.pix3lpirat3.mfbridge.core.layout.HudLayouts;
import io.github.pix3lpirat3.mfbridge.protocol.core.BossbarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudBadgeData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudBarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudKvData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudListData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudSparklineData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KvEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Draws bot HUD panels and boss bars.
 * Unless the player shows everything, only the focused bot's panels and boss bars render in
 * full; other bots' panels shrink to their title bar with a one-line summary. Widget text resolves
 * its {@code {name}} templates. Panels honour the
 * placements the player saved in the HUD editor. Layout is cached and rebuilt only when the
 * declarations, the saved placements or the GUI size change; a frame only replays the cached layout. Wire colours are {@code 0xAARRGGBB}, which is also what the GUI
 * takes. Client thread only.
 */
public final class HudRenderer {

    private static final int PAD = 3;
    private static final int TITLE_HEIGHT = HudPlacement.TITLE_HEIGHT;
    private static final int LINE = 10;
    private static final int MIN_WIDTH = 100;
    private static final int MAX_WIDTH = 200;
    private static final int DEFAULT_LIST_ROWS = 5;
    private static final int SPARK_HEIGHT = 16;
    private static final int BAR_HEIGHT = 5;
    private static final int PANEL_ARGB = 0x90000000;
    private static final int TITLE_ARGB = 0xC0202A40;
    private static final int BAR_ARGB = 0xFF55CC55;
    private static final int BAR_BACK_ARGB = 0xFF303030;
    private static final int SPARK_ARGB = 0xFF55AAFF;
    private static final int SELECTED_ARGB = 0x50FFFFFF;
    private static final int BOSS_BAR_WIDTH = 182;
    private static final int BOSS_SPACING = 19;
    private static final int BOSS_TOP = 12;
    private static final String[] BOSS_COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
    private static final String[] BOSS_OVERLAYS = {"notched_6", "notched_10", "notched_12", "notched_20"};
    /** Sprite ids by name index, background then progress; built once, since bars draw every frame. */
    private static final Identifier[][] BOSS_COLOR_SPRITES = sprites(BOSS_COLORS);
    private static final Identifier[][] BOSS_OVERLAY_SPRITES = sprites(BOSS_OVERLAYS);

    private static Identifier[][] sprites(final String[] names) {
        final Identifier[][] out = new Identifier[names.length][];
        for (int i = 0; i < names.length; i++) {
            out[i] = new Identifier[] {
                Identifier.withDefaultNamespace("boss_bar/" + names[i] + "_background"),
                Identifier.withDefaultNamespace("boss_bar/" + names[i] + "_progress"),
            };
        }
        return out;
    }

    private final List<PanelBox> panels = new ArrayList<>();
    private final List<EditorPanel> editorPanels = new ArrayList<>();
    private final TemplateCache templates = new TemplateCache();
    private Variables vars = Variables.EMPTY;
    private long version = -1L;
    private long varsVersion = -1L;
    /** Whether the last layout resolved a placeholder; without one a variable change moves nothing. */
    private boolean layoutReadsVars;
    private @Nullable String focusSeen;
    private boolean showAllSeen;
    private long layoutsVersion = -1L;
    private @Nullable HudLayouts layoutsSeen;
    private int layoutWidth = -1;
    private int layoutHeight = -1;
    private int layoutBossCount = -1;
    private int layoutToastBottom = -1;

    /** Creates the renderer. */
    public HudRenderer() {
    }

    private sealed interface Item permits TextItem, KvItem, BarItem, ListItem, BadgeItem, SparkItem, StackItem {
        int height();
    }

    private record TextItem(List<FormattedCharSequence> lines, int color) implements Item {
        @Override
        public int height() {
            return lines.size() * LINE;
        }
    }

    private record KvRow(String key, String value, int color) {
    }

    private record KvItem(List<KvRow> rows, int keyWidth) implements Item {
        @Override
        public int height() {
            return rows.size() * LINE;
        }
    }

    private record BarItem(String label, String numbers, int numbersWidth, float fraction, int color) implements Item {
        @Override
        public int height() {
            return LINE + BAR_HEIGHT + 2;
        }
    }

    private record ListItem(List<String> rows, int selectedRow) implements Item {
        @Override
        public int height() {
            return rows.size() * LINE;
        }
    }

    private record BadgeItem(String text, int color, int textColor, int width) implements Item {
        @Override
        public int height() {
            return LINE + 2;
        }
    }

    private record SparkItem(float[] values, float min, float max, int color) implements Item {
        @Override
        public int height() {
            return SPARK_HEIGHT + 2;
        }
    }

    private record StackItem(ItemStack stack, String label) implements Item {
        @Override
        public int height() {
            return 18;
        }
    }

    /**
     * A placed panel, with its title row laid out: {@code titleLine} is the title cut to the room
     * left of the owner name, and {@code ownerWidth} is 0 when the owner name is not shown.
     */
    private record PanelBox(int x, int y, int width, int height, String titleLine, String owner, int ownerWidth, String ownerKey,
            boolean collapsed, List<Item> items) {
    }

    private record Measured(HudPanel panel, int width, List<Item> items, int contentHeight, String title) {
    }

    /**
     * Where a panel is, for the HUD editor; hidden panels are included at the place they would take.
     *
     * @param key panel object key
     * @param title panel title
     * @param owner display name of the owning bot
     * @param x left edge, GUI pixels
     * @param y top edge, GUI pixels
     * @param width width, GUI pixels
     * @param height height, GUI pixels
     * @param hidden whether the player hid it
     * @param saved the saved layout, or null when the panel sits where the bot declared it
     * @param declared the anchor slot the bot declared
     */
    public record EditorPanel(String key, String title, String owner, int x, int y, int width, int height, boolean hidden,
            @Nullable HudLayout saved, HudAnchor declared) {
    }

    /** Forces a rebuild on the next frame, for example after the core restarted. */
    public void invalidate() {
        version = -1L;
    }

    /**
     * Returns the panels as last laid out, including hidden ones, for the HUD editor.
     *
     * @return the panels. Immutable copy.
     */
    public List<EditorPanel> editorPanels() {
        return List.copyOf(editorPanels);
    }

    /**
     * Returns the owner whose panel title bar is at a GUI position, for focusing a bot by clicking its
     * panel.
     *
     * @param x GUI x
     * @param y GUI y
     * @return the owner, {@code bot:<identity>}, or null
     */
    public @Nullable String ownerAt(final double x, final double y) {
        for (final PanelBox p : panels) {
            if (x >= p.x() && x < p.x() + p.width() && y >= p.y() && y < p.y() + TITLE_HEIGHT) {
                return p.ownerKey();
            }
        }
        return null;
    }

    /**
     * Draws boss bars and panels.
     *
     * @param graphics the frame's GUI extractor
     * @param decl the current declarations
     * @param layouts the player's saved placements, or null to use the declared ones
     * @param variables the current variables, for templates
     * @param focused the focused bot's owner, or null
     * @param showAll whether every bot renders in full
     */
    public void render(final GuiGraphicsExtractor graphics, final DeclarationSnapshot decl, final @Nullable HudLayouts layouts,
            final Variables variables, final @Nullable String focused, final boolean showAll) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Font font = minecraft.font;
        vars = variables;
        final int vanillaBars = vanillaBossBars(minecraft);
        final int bossBottom = drawBossbars(graphics, font, decl.bossbars(), vanillaBars, showAll ? null : focused);
        final int toastBottom = toastBottom(minecraft);
        final long lv = layouts == null ? -1L : layouts.version();
        if (decl.version() != version || graphics.guiWidth() != layoutWidth || graphics.guiHeight() != layoutHeight
                || vanillaBars + decl.bossbars().size() != layoutBossCount || toastBottom != layoutToastBottom || layouts != layoutsSeen || lv != layoutsVersion
                || variables.version() != varsVersion && layoutReadsVars || showAll != showAllSeen || !Objects.equals(focused, focusSeen)) {
            layoutReadsVars = false;
            layout(decl, font, graphics.guiWidth(), graphics.guiHeight(), bossBottom, toastBottom, layouts, showAll ? null : focused);
            version = decl.version();
            varsVersion = variables.version();
            showAllSeen = showAll;
            focusSeen = focused;
            layoutsSeen = layouts;
            layoutsVersion = lv;
            layoutWidth = graphics.guiWidth();
            layoutHeight = graphics.guiHeight();
            layoutBossCount = vanillaBars + decl.bossbars().size();
            layoutToastBottom = toastBottom;
        }
        for (int i = 0; i < panels.size(); i++) {
            drawPanel(graphics, font, panels.get(i));
        }
    }

    /** Returns the y below the lowest occupied toast slot, or 0 when no toast is shown. */
    private static int toastBottom(final Minecraft minecraft) {
        return ((ToastManagerAccessor) minecraft.gui.toastManager()).mfbridge$occupiedSlots().length() * Toast.SLOT_HEIGHT;
    }

    private static int vanillaBossBars(final Minecraft minecraft) {
        return ((BossHealthOverlayAccessor) minecraft.gui.hud.getBossOverlay()).mfbridge$events().size();
    }

    /**
     * Draws bot boss bars below the vanilla ones in vanilla style, and returns the Y below the last bar.
     * With a focused bot only its bars show.
     */
    private int drawBossbars(final GuiGraphicsExtractor graphics, final Font font, final List<BossbarDecl> bars, final int vanilla,
            final @Nullable String focused) {
        int y = BOSS_TOP + vanilla * BOSS_SPACING;
        final int x = graphics.guiWidth() / 2 - BOSS_BAR_WIDTH / 2;
        // Vanilla stops adding boss bars once they reach a third of the screen height (26.3 BossHealthOverlay).
        final int limit = graphics.guiHeight() / 3;
        boolean any = false;
        for (int i = 0; i < bars.size() && y < limit; i++) {
            final BossbarDecl bar = bars.get(i);
            if (focused != null && !DisplayText.ownedBy(bar.key(), focused)) {
                continue;
            }
            any = true;
            final BossbarData data = bar.data();
            final String text = templates.resolve(data.text(), bar.key(), vars);
            final int color = indexOf(BOSS_COLORS, data.color(), 6);
            final int overlay = indexOf(BOSS_OVERLAYS, data.overlay(), -1);
            final int filled = Math.round(Math.clamp(data.progress(), 0f, 1f) * BOSS_BAR_WIDTH);
            bossBar(graphics, x, y, BOSS_BAR_WIDTH, color, overlay, 0);
            if (filled > 0) {
                bossBar(graphics, x, y, filled, color, overlay, 1);
            }
            graphics.text(font, text, graphics.guiWidth() / 2 - font.width(text) / 2, y - 9, UiColors.TEXT);
            y += BOSS_SPACING;
        }
        if (!any) {
            return vanilla == 0 ? 0 : BOSS_TOP + vanilla * BOSS_SPACING - BOSS_SPACING + BAR_HEIGHT + 2;
        }
        return y - BOSS_SPACING + BAR_HEIGHT + 2;
    }

    private static void bossBar(final GuiGraphicsExtractor graphics, final int x, final int y, final int width, final int color,
            final int overlay, final int part) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_COLOR_SPRITES[color][part],
                BOSS_BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, width, BAR_HEIGHT);
        if (overlay >= 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BOSS_OVERLAY_SPRITES[overlay][part],
                    BOSS_BAR_WIDTH, BAR_HEIGHT, 0, 0, x, y, width, BAR_HEIGHT);
        }
    }

    private static int indexOf(final String[] values, final String value, final int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return fallback;
    }

    private void layout(final DeclarationSnapshot decl, final Font font, final int screenWidth, final int screenHeight, final int bossBottom,
            final int toastBottom, final @Nullable HudLayouts layouts, final @Nullable String focused) {
        panels.clear();
        editorPanels.clear();
        final Map<String, Measured> measured = new HashMap<>();
        final Map<String, @Nullable HudLayout> saved = new HashMap<>();
        final List<HudPlacement.Panel> shown = new ArrayList<>();
        final List<HudPlacement.Panel> all = new ArrayList<>();
        final Map<String, String> owners = new HashMap<>();
        for (final Map.Entry<String, List<HudPanel>> byOwner : decl.hud().entrySet()) {
            final boolean summary = focused != null && !byOwner.getKey().equals(focused);
            for (final HudPanel panel : byOwner.getValue()) {
                final HudLayout layout = layouts == null ? null : layouts.get(panel.key());
                final Measured m = summary ? summary(panel, byOwner.getKey(), font, layout) : measure(panel, font, layout);
                measured.put(panel.key(), m);
                saved.put(panel.key(), layout);
                owners.put(panel.key(), byOwner.getKey());
                final HudPlacement.Panel p = HudPlacement.panel(panel.key(), panel.data().anchor(), layout, m.width(), fullHeight(m),
                        summary || panel.data().collapsed() || m.items().isEmpty());
                all.add(p);
                if (layout == null || !layout.hidden()) {
                    shown.add(p);
                }
            }
        }
        for (final HudPlacement.Placed p : HudPlacement.place(shown, screenWidth, screenHeight, bossBottom, toastBottom)) {
            final Measured m = measured.get(p.key());
            if (m != null) {
                final String owner = m.panel().ownerName();
                final int ownerWidth = owner.equals(m.title()) ? 0 : font.width(owner);
                final String marker = p.collapsed() && !m.items().isEmpty() ? "+ " : "";
                panels.add(new PanelBox(p.x(), p.y(), p.width(), p.height(),
                        font.plainSubstrByWidth(marker + m.title(), p.width() - ownerWidth - 3 * PAD), owner, ownerWidth,
                        owners.getOrDefault(p.key(), ""), p.collapsed(), m.items()));
            }
        }
        for (final HudPlacement.Placed p : HudPlacement.place(all, screenWidth, screenHeight, bossBottom, toastBottom)) {
            final Measured m = measured.get(p.key());
            if (m != null) {
                final HudLayout layout = saved.get(p.key());
                editorPanels.add(new EditorPanel(p.key(), m.title(), m.panel().ownerName(), p.x(), p.y(), p.width(), p.height(),
                        layout != null && layout.hidden(), layout, m.panel().data().anchor()));
            }
        }
    }

    private static int fullHeight(final Measured m) {
        return TITLE_HEIGHT + m.contentHeight() + PAD;
    }

    /** The title plus the first text or key-value entry, else the owner's first variable. */
    private Measured summary(final HudPanel panel, final String owner, final Font font, final @Nullable HudLayout layout) {
        final String title = panel.data().title() != null ? panel.data().title() : localId(panel.key());
        String line = null;
        for (final HudWidget w : panel.widgets()) {
            if (w.data() instanceof HudTextData t) {
                line = t(t.text(), w.key());
            } else if (w.data() instanceof HudKvData kv && !kv.entries().isEmpty()) {
                line = kv.entries().getFirst().key() + " " + t(kv.entries().getFirst().value(), w.key());
            }
            if (line != null) {
                break;
            }
        }
        if (line == null) {
            layoutReadsVars = true;
            line = vars.firstOf(owner);
        }
        final String text = line == null ? title : title + ": " + line;
        final int width = HudPlacement.effectiveWidth(layout, Math.clamp(font.width(text) + font.width(panel.ownerName()) + 12 + 2 * PAD,
                MIN_WIDTH, MAX_WIDTH));
        return new Measured(panel, width, List.of(), 0, text);
    }

    private String t(final String text, final String key) {
        if (Templates.templated(text)) {
            layoutReadsVars = true;
        }
        return templates.resolve(text, key, vars);
    }

    private Measured measure(final HudPanel panel, final Font font, final @Nullable HudLayout layout) {
        final String title = panel.data().title() != null ? panel.data().title() : localId(panel.key());
        int natural = font.width(title) + font.width(panel.ownerName()) + 12;
        for (final HudWidget w : panel.widgets()) {
            natural = Math.max(natural, naturalWidth(w.data(), font));
        }
        final int width = HudPlacement.effectiveWidth(layout, Math.clamp(natural + 2 * PAD, MIN_WIDTH, MAX_WIDTH));
        final int inner = width - 2 * PAD;
        final List<Item> items = new ArrayList<>();
        int height = 0;
        for (final HudWidget w : panel.widgets()) {
            final Item item = item(w.data(), w.key(), font, inner);
            if (item != null) {
                items.add(item);
                height += item.height() + 1;
            }
        }
        return new Measured(panel, width, List.copyOf(items), height, title);
    }

    private static String localId(final String key) {
        final int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static int naturalWidth(final Object data, final Font font) {
        return switch (data) {
            case HudTextData t -> Math.min(font.width(t.text()), MAX_WIDTH);
            case HudKvData kv -> {
                int keys = 0;
                int values = 0;
                for (final KvEntry e : kv.entries()) {
                    keys = Math.max(keys, font.width(e.key()));
                    values = Math.max(values, font.width(e.value()));
                }
                yield keys + 6 + values;
            }
            case HudBarData b -> Math.max(100, font.width(b.label() == null ? "" : b.label()) + 40);
            case HudListData l -> {
                int w = 0;
                for (final String s : l.items()) {
                    w = Math.max(w, font.width(s));
                }
                yield w + 4;
            }
            case HudBadgeData b -> font.width(b.text()) + 8;
            case HudItemData i -> 20 + font.width(i.label() == null ? i.item() : i.label());
            default -> 0;
        };
    }

    private static int opaque(final int argb) {
        // Colours with alpha 0 would be invisible; treat them as opaque RGB like the plugin does.
        return (argb & 0xFF000000) == 0 ? argb | 0xFF000000 : argb;
    }

    private @Nullable Item item(final Object data, final String key, final Font font, final int inner) {
        return switch (data) {
            case HudTextData t -> {
                List<FormattedCharSequence> lines = font.split(Component.literal(t(t.text(), key)), inner);
                final Integer max = t.maxLines();
                if (max != null && max > 0 && lines.size() > max) {
                    lines = lines.subList(0, max);
                }
                yield new TextItem(List.copyOf(lines), t.color() == null ? UiColors.TEXT : opaque(t.color()));
            }
            case HudKvData kv -> {
                int keyWidth = 0;
                for (final KvEntry e : kv.entries()) {
                    keyWidth = Math.max(keyWidth, font.width(e.key()));
                }
                keyWidth = Math.min(keyWidth, inner / 2);
                final List<KvRow> rows = new ArrayList<>(kv.entries().size());
                for (final KvEntry e : kv.entries()) {
                    rows.add(new KvRow(font.plainSubstrByWidth(e.key(), keyWidth), font.plainSubstrByWidth(t(e.value(), key), inner - keyWidth - 6),
                            e.color() == null ? UiColors.TEXT : opaque(e.color())));
                }
                yield new KvItem(List.copyOf(rows), keyWidth);
            }
            case HudBarData b -> {
                final double span = b.max() - b.min();
                final float fraction = span <= 0 ? 0f : (float) Math.clamp((b.value() - b.min()) / span, 0d, 1d);
                final String numbers = plain(b.value()) + "/" + plain(b.max());
                final int numbersWidth = font.width(numbers);
                yield new BarItem(b.label() == null ? "" : font.plainSubstrByWidth(t(b.label(), key), inner - numbersWidth - 4), numbers,
                        numbersWidth, fraction, b.color() == null ? BAR_ARGB : opaque(b.color()));
            }
            case HudListData l -> {
                final int maxRows = l.maxRows() == null || l.maxRows() <= 0 ? DEFAULT_LIST_ROWS : l.maxRows();
                final int selected = l.selected() == null ? -1 : l.selected();
                int first = 0;
                if (selected >= maxRows) {
                    first = Math.min(selected - maxRows + 1, Math.max(0, l.items().size() - maxRows));
                }
                final List<String> rows = new ArrayList<>();
                for (int i = first; i < l.items().size() && rows.size() < maxRows; i++) {
                    rows.add(font.plainSubstrByWidth(t(l.items().get(i), key), inner - 2));
                }
                yield new ListItem(List.copyOf(rows), selected - first);
            }
            case HudBadgeData b -> {
                final int color = opaque(b.color());
                final int r = (color >> 16) & 0xFF;
                final int g = (color >> 8) & 0xFF;
                final int bl = color & 0xFF;
                final boolean light = r * 299 + g * 587 + bl * 114 > 150_000;
                final String text = font.plainSubstrByWidth(t(b.text(), key), inner - 6);
                yield new BadgeItem(text, color, light ? 0xFF000000 : 0xFFFFFFFF, font.width(text) + 6);
            }
            case HudSparklineData s -> {
                float min = Float.POSITIVE_INFINITY;
                float max = Float.NEGATIVE_INFINITY;
                for (final float v : s.values()) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                if (s.min() != null) {
                    min = s.min();
                }
                if (s.max() != null) {
                    max = s.max();
                }
                if (!(max > min)) {
                    max = min + 1f;
                }
                yield new SparkItem(s.values().clone(), min, max, s.color() == null ? SPARK_ARGB : opaque(s.color()));
            }
            case HudItemData i -> {
                final net.minecraft.world.item.@Nullable Item item = SlotItems.item(i.item());
                final ItemStack stack = item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, i.count()));
                yield new StackItem(stack, font.plainSubstrByWidth(i.label() == null ? i.item() : i.label(), inner - 20));
            }
            default -> null;
        };
    }

    private static String plain(final double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static void drawPanel(final GuiGraphicsExtractor graphics, final Font font, final PanelBox p) {
        graphics.fill(p.x(), p.y(), p.x() + p.width(), p.y() + p.height(), PANEL_ARGB);
        graphics.fill(p.x(), p.y(), p.x() + p.width(), p.y() + TITLE_HEIGHT, TITLE_ARGB);
        graphics.text(font, p.titleLine(), p.x() + PAD, p.y() + 2, UiColors.TEXT);
        if (p.ownerWidth() > 0) {
            graphics.text(font, p.owner(), p.x() + p.width() - PAD - p.ownerWidth(), p.y() + 2, UiColors.HEADER);
        }
        if (p.collapsed()) {
            return;
        }
        final int x = p.x() + PAD;
        final int inner = p.width() - 2 * PAD;
        int y = p.y() + TITLE_HEIGHT + 2;
        for (final Item item : p.items()) {
            drawItem(graphics, font, item, x, y, inner);
            y += item.height() + 1;
        }
    }

    private static void drawItem(final GuiGraphicsExtractor graphics, final Font font, final Item item, final int x, final int y, final int inner) {
        switch (item) {
            case TextItem t -> {
                int ly = y;
                for (final FormattedCharSequence line : t.lines()) {
                    graphics.text(font, line, x, ly, t.color());
                    ly += LINE;
                }
            }
            case KvItem kv -> {
                int ly = y;
                for (final KvRow row : kv.rows()) {
                    graphics.text(font, row.key(), x, ly, UiColors.LABEL);
                    graphics.text(font, row.value(), x + kv.keyWidth() + 6, ly, row.color());
                    ly += LINE;
                }
            }
            case BarItem b -> {
                graphics.text(font, b.label(), x, y, UiColors.TEXT);
                graphics.text(font, b.numbers(), x + inner - b.numbersWidth(), y, UiColors.LABEL);
                final int by = y + LINE;
                graphics.fill(x, by, x + inner, by + BAR_HEIGHT, BAR_BACK_ARGB);
                final int filled = Math.round(b.fraction() * inner);
                if (filled > 0) {
                    graphics.fill(x, by, x + filled, by + BAR_HEIGHT, b.color());
                }
            }
            case ListItem l -> {
                int ly = y;
                for (int i = 0; i < l.rows().size(); i++) {
                    if (i == l.selectedRow()) {
                        graphics.fill(x - 1, ly - 1, x + inner + 1, ly + LINE - 1, SELECTED_ARGB);
                    }
                    graphics.text(font, l.rows().get(i), x + 1, ly, UiColors.TEXT);
                    ly += LINE;
                }
            }
            case BadgeItem b -> {
                graphics.fill(x, y, x + b.width(), y + LINE + 1, b.color());
                graphics.text(font, b.text(), x + 3, y + 2, b.textColor(), false);
            }
            case SparkItem s -> drawSparkline(graphics, s, x, y + 1, inner);
            case StackItem s -> {
                if (!s.stack().isEmpty()) {
                    graphics.item(s.stack(), x, y + 1);
                    graphics.itemDecorations(font, s.stack(), x, y + 1);
                }
                graphics.text(font, s.label(), x + 20, y + 5, UiColors.TEXT);
            }
        }
    }

    /** Draws the values as a connected line, one GUI pixel column at a time, newest on the right. */
    private static void drawSparkline(final GuiGraphicsExtractor graphics, final SparkItem s, final int x, final int y, final int width) {
        final float[] values = s.values();
        graphics.fill(x, y, x + width, y + SPARK_HEIGHT, 0x40000000);
        final int n = values.length;
        if (n == 0) {
            return;
        }
        final int count = Math.min(n, width);
        final int first = n - count;
        final float range = s.max() - s.min();
        int previous = -1;
        for (int column = 0; column < width; column++) {
            final float t = count == 1 ? 0f : column * (count - 1) / (float) Math.max(1, width - 1);
            final int i0 = (int) Math.floor(t);
            final int i1 = Math.min(count - 1, i0 + 1);
            final float f = t - i0;
            final float v = values[first + i0] * (1f - f) + values[first + i1] * f;
            final float norm = Math.clamp((v - s.min()) / range, 0f, 1f);
            final int py = y + SPARK_HEIGHT - 1 - Math.round(norm * (SPARK_HEIGHT - 1));
            final int top = previous < 0 ? py : Math.min(previous, py);
            final int bottom = previous < 0 ? py : Math.max(previous, py);
            graphics.fill(x + column, top, x + column + 1, bottom + 1, s.color());
            previous = py;
        }
    }
}
