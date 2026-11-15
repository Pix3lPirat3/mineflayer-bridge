package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.protocol.core.BossbarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EntityTagData;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudKvData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudListData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudSparklineData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PathData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.VarsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * Size caps on what one object or request may hold, so a single write cannot make the client lay
 * out or draw an absurd amount. Over a cap, the write is refused with {@code quota_exceeded}.
 */
final class KindCaps {
    /** Longest text of a label, HUD line, display, boss bar, prompt or screen item, in characters. */
    static final int MAX_TEXT = 4096;
    /** Most points of one path. */
    static final int MAX_PATH_POINTS = 16_384;
    /** Most nodes of one route, or boxes or corners of one zone. */
    static final int MAX_SHAPE_PARTS = 4096;
    /** Most rows of a HUD list or key-value widget, or samples of a sparkline. */
    static final int MAX_HUD_ROWS = 1024;
    /** Most items of one screen, over all of its tabs. */
    static final int MAX_SCREEN_ITEMS = 1024;
    /** Most tabs of one screen. */
    static final int MAX_SCREEN_TABS = 32;
    /** Most slots of one container view. */
    static final int MAX_SLOTS = 2048;
    /** Most settings of one group, or variables of one scope. */
    static final int MAX_ENTRIES = 1024;
    /** Most fields of one form. */
    static final int MAX_FORM_FIELDS = 64;
    /** Most choices of a prompt or select field, entries of a world menu, or extra form buttons. */
    static final int MAX_CHOICES = 256;

    private KindCaps() {}

    /**
     * Checks decoded kind data against the caps of its kind.
     *
     * @throws BridgeException {@code quota_exceeded}
     */
    static void check(KindType<?> kind, @Nullable Object decoded) {
        String name = kind.name();
        switch (decoded) {
            case LabelData d -> text(name, d.text());
            case HudTextData d -> text(name, d.text());
            case DisplayTextData d -> text(name, d.text());
            case BossbarData d -> text(name, d.text());
            case WaypointData d -> text(name, d.name());
            case EntityTagData d -> text(name, d.text());
            case PathData d -> count(name, "points", d.points().length / 3, MAX_PATH_POINTS);
            case RouteData d -> count(name, "nodes", d.nodes().size(), MAX_SHAPE_PARTS);
            case ZoneData d -> {
                count(name, "boxes", size(d.boxes()), MAX_SHAPE_PARTS);
                count(name, "corners", size(d.polygon()), MAX_SHAPE_PARTS);
            }
            case HudListData d -> {
                count(name, "items", d.items().size(), MAX_HUD_ROWS);
                for (String item : d.items()) {
                    text(name, item);
                }
            }
            case HudKvData d -> count(name, "entries", d.entries().size(), MAX_HUD_ROWS);
            case HudSparklineData d -> count(name, "values", d.values().length, MAX_HUD_ROWS);
            case ScreenData d -> screen(name, d);
            case ContainerViewData d -> count(name, "slots", d.slots().size(), MAX_SLOTS);
            case SettingsData d -> count(name, "settings", d.settings().size(), MAX_ENTRIES);
            case VarsData d -> count(name, "values", d.values().size(), MAX_ENTRIES);
            case null, default -> {
            }
        }
    }

    private static void screen(String name, ScreenData d) {
        count(name, "tabs", d.tabs().size(), MAX_SCREEN_TABS);
        int items = 0;
        for (ScreenTab tab : d.tabs()) {
            for (ScreenRow row : tab.rows()) {
                items += row.items().size();
                for (ScreenItem it : row.items()) {
                    text(name, it.text());
                    text(name, it.tooltip());
                    count(name, "options", size(it.options()), MAX_CHOICES);
                }
            }
        }
        count(name, "items", items, MAX_SCREEN_ITEMS);
    }

    /** Checks a form request. */
    static void check(FormOpen f) {
        text("a form", f.title());
        count("a form", "fields", f.fields().size(), MAX_FORM_FIELDS);
        count("a form", "buttons", size(f.buttons()), MAX_CHOICES);
        for (var field : f.fields()) {
            text("a form", field.label());
            count("a form field", "options", size(field.options()), MAX_CHOICES);
        }
    }

    /** Checks a prompt request. */
    static void check(PromptRequest r) {
        text("a prompt", r.text());
        count("a prompt", "choices", size(r.choices()), MAX_CHOICES);
        count("a prompt", "options", size(r.options()), MAX_CHOICES);
        count("a prompt", "offers", size(r.offers()), MAX_CHOICES);
    }

    /** Checks a pick request. */
    static void check(PickRequest r) {
        text("a pick", r.prompt());
    }

    /** Checks a world menu request. */
    static void check(WorldMenuRequest r) {
        text("a world menu", r.prompt());
        count("a world menu", "entries", r.entries().size(), MAX_CHOICES);
    }

    private static int size(@Nullable Collection<?> c) {
        return c == null ? 0 : c.size();
    }

    private static void text(String what, @Nullable String text) {
        if (text != null && text.length() > MAX_TEXT) {
            throw new BridgeException("quota_exceeded", what + " takes at most " + MAX_TEXT + " characters of text");
        }
    }

    private static void count(String what, String parts, int n, int max) {
        if (n > max) {
            throw new BridgeException("quota_exceeded", what + " takes at most " + max + " " + parts);
        }
    }
}
