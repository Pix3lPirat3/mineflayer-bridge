package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.decl.ActionDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.BossbarDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.ContainerViewDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.CrewDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.HudPanel;
import io.github.pix3lpirat3.mfbridge.core.decl.HudWidget;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.KeybindDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.SettingsDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.ToolDecl;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.BossbarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CrewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudBadgeData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudBarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudKvData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudListData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudSparklineData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.InterceptData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the declaration snapshot in step with the store. The declarations of each owner are built
 * once and kept until one of its declaration objects changes, so one bot's HUD update does not
 * walk the objects of every other bot.
 */
@ClientThread
final class DeclarationPublisher {
    private static final String HUD = "~hud/";

    private record Widget(String key, String panelKey, String panelId, int order, Object data) {}

    private record Panel(StoredObject object, HudPanelData data) {}

    private static final Comparator<Widget> WIDGET_ORDER =
            Comparator.comparingInt(Widget::order).thenComparing(Widget::key);
    private static final Comparator<Panel> PANEL_ORDER =
            Comparator.comparingInt((Panel p) -> p.data().order()).thenComparing(p -> p.object().key());

    /** The declarations of one owner. */
    private static final class Owned {
        final Session session;
        final List<CommandDecl> commands = new ArrayList<>();
        final List<ActionDecl> actions = new ArrayList<>();
        final List<KeybindDecl> keybinds = new ArrayList<>();
        final List<ToolDecl> tools = new ArrayList<>();
        final List<SettingsDecl> settings = new ArrayList<>();
        List<HudPanel> hud = List.of();
        final List<BossbarDecl> bossbars = new ArrayList<>();
        final List<ContainerViewDecl> views = new ArrayList<>();
        final List<InterceptDecl> intercepts = new ArrayList<>();
        final List<ScreenDecl> screens = new ArrayList<>();
        final List<CrewDecl> crews = new ArrayList<>();

        Owned(Session session) {
            this.session = session;
        }
    }

    private final Sight sight;
    private final ObjectStore store;
    private final BotSettings settings;
    /** What was built per owner (no trailing slash); an owner that changed is taken out. */
    private final Map<String, Owned> built = new HashMap<>();
    private boolean stale = true;
    private long settingsSeen = -1;
    private volatile DeclarationSnapshot snapshot = DeclarationSnapshot.EMPTY;

    DeclarationPublisher(Sight sight, ObjectStore store, BotSettings settings) {
        this.sight = sight;
        this.store = store;
        this.settings = settings;
    }

    /** Returns the latest published snapshot. */
    @AnyThread
    DeclarationSnapshot snapshot() {
        return snapshot;
    }

    /** Marks an owner (no trailing slash) whose declaration objects changed. */
    void ownerChanged(String owner) {
        built.remove(owner);
        stale = true;
    }

    /** Marks everything: the player's world, the focus or the set of sessions changed. */
    void allChanged() {
        built.clear();
        stale = true;
    }

    /**
     * Publishes a new snapshot when something was marked and the content differs.
     *
     * @param writers live writer sessions sorted by owned prefix
     * @param now bridge clock ms, to leave out expired objects
     */
    void publish(List<Session> writers, long now) {
        long settingsVersion = settings.version();
        if (settingsVersion != settingsSeen) {
            // Settings declarations carry the player's stored values.
            settingsSeen = settingsVersion;
            allChanged();
        }
        if (!stale) {
            return;
        }
        stale = false;
        DeclarationSnapshot previous = snapshot;
        DeclarationSnapshot next = build(previous.version() + 1, writers, now);
        if (!next.sameContent(previous)) {
            snapshot = next;
        }
    }

    private DeclarationSnapshot build(long version, List<Session> writers, long now) {
        List<CommandDecl> commands = new ArrayList<>();
        List<ActionDecl> actions = new ArrayList<>();
        List<KeybindDecl> keybinds = new ArrayList<>();
        List<ToolDecl> tools = new ArrayList<>();
        List<SettingsDecl> settingsOut = new ArrayList<>();
        Map<String, List<HudPanel>> hud = new LinkedHashMap<>();
        List<BossbarDecl> bossbars = new ArrayList<>();
        List<ContainerViewDecl> views = new ArrayList<>();
        List<InterceptDecl> intercepts = new ArrayList<>();
        List<ScreenDecl> screens = new ArrayList<>();
        List<CrewDecl> crews = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        for (Session s : writers) {
            if (s.ended || s.superseded) {
                continue;
            }
            shown.add(s.owner);
            Owned d = built.get(s.owner);
            if (d == null || d.session != s) {
                d = build(s, now);
                built.put(s.owner, d);
            }
            commands.addAll(d.commands);
            // A bot in another world can still be given orders; what it shows waits until it is attached.
            if (!sight.attachedNow(s)) {
                continue;
            }
            actions.addAll(d.actions);
            keybinds.addAll(d.keybinds);
            tools.addAll(d.tools);
            settingsOut.addAll(d.settings);
            if (!d.hud.isEmpty()) {
                hud.put(s.owner, d.hud);
            }
            bossbars.addAll(d.bossbars);
            views.addAll(d.views);
            intercepts.addAll(d.intercepts);
            screens.addAll(d.screens);
            crews.addAll(d.crews);
        }
        built.keySet().retainAll(shown);
        return new DeclarationSnapshot(version, commands, actions, keybinds, tools, settingsOut, hud, bossbars, views,
                intercepts, screens, crews);
    }

    private Owned build(Session s, long now) {
        Owned out = new Owned(s);
        String name = s.displayName;
        List<Panel> panels = new ArrayList<>();
        List<Widget> widgets = new ArrayList<>();
        for (StoredObject o : store.under(s.ownerPrefix)) {
            StoredObject.State st = o.state();
            if (!KindRoles.declaration(st.kind())) {
                continue;
            }
            if (st.expiresAtMs() >= 0 && st.expiresAtMs() <= now) {
                continue;
            }
            // World-scoped declarations only while the worlds match.
            if (st.scope() == Scope.WORLD && !sight.worldVisible(st.world())) {
                continue;
            }
            if (!sight.focusAllows(s.owner, st.traits())) {
                continue;
            }
            switch (st.decoded()) {
                case CommandData d -> out.commands.add(new CommandDecl(o.key(), name, s.identity, st.scope(), d));
                case ActionData d -> out.actions.add(new ActionDecl(o.key(), name, st.scope(), d));
                case KeybindData d -> out.keybinds.add(new KeybindDecl(o.key(), name, d));
                case ToolData d -> out.tools.add(new ToolDecl(o.key(), name, d));
                case SettingsData d -> out.settings.add(new SettingsDecl(o.key(), name, d, settings.values(o.key(), d)));
                case HudPanelData d -> panels.add(new Panel(o, d));
                case BossbarData d -> out.bossbars.add(new BossbarDecl(o.key(), name, d));
                case ContainerViewData d -> out.views.add(new ContainerViewDecl(o.key(), name, s.identity, d));
                case InterceptData d -> out.intercepts.add(new InterceptDecl(o.key(), name, s.identity, d));
                case ScreenData d -> out.screens.add(new ScreenDecl(o.key(), name, s.identity, d));
                case CrewData d -> out.crews.add(new CrewDecl(o.key(), s.owner, name, d));
                case null -> {}
                default -> {
                    Widget w = widget(o);
                    if (w != null) {
                        widgets.add(w);
                    }
                }
            }
        }
        if (!panels.isEmpty()) {
            out.hud = panels(name, panels, widgets);
        }
        return out;
    }

    private static List<HudPanel> panels(String ownerName, List<Panel> panels, List<Widget> widgets) {
        Map<String, List<Widget>> byPanel = new HashMap<>();
        for (Widget w : widgets) {
            byPanel.computeIfAbsent(w.panelKey(), k -> new ArrayList<>()).add(w);
        }
        panels.sort(PANEL_ORDER);
        List<HudPanel> out = new ArrayList<>(panels.size());
        for (Panel p : panels) {
            String key = p.object().key();
            String panelId = panelId(p.object().parts().localId());
            List<HudWidget> children = new ArrayList<>();
            List<Widget> mine = byPanel.get(key);
            if (mine != null && panelId != null) {
                mine.sort(WIDGET_ORDER);
                for (Widget w : mine) {
                    if (w.panelId().equals(panelId)) {
                        children.add(new HudWidget(w.key(), w.data()));
                    }
                }
            }
            out.add(new HudPanel(key, ownerName, p.data(), children));
        }
        return out;
    }

    /** Returns {@code <panel>} of a panel local id {@code ~hud/<panel>}, or null for other layouts. */
    private static @Nullable String panelId(String localId) {
        if (!localId.startsWith(HUD) || localId.length() == HUD.length() || localId.indexOf('/', HUD.length()) >= 0) {
            return null;
        }
        return localId.substring(HUD.length());
    }

    /**
     * A widget is {@code ~hud/<panel>/<widget>} and its {@code panel} field holds
     * {@code <panel>}; widgets whose key and field disagree belong to no panel.
     */
    private static @Nullable Widget widget(StoredObject o) {
        Object data = o.state().decoded();
        String field;
        int order;
        switch (data) {
            case HudTextData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudKvData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudBarData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudListData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudBadgeData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudSparklineData d -> {
                field = d.panel();
                order = d.order();
            }
            case HudItemData d -> {
                field = d.panel();
                order = d.order();
            }
            case null, default -> {
                return null;
            }
        }
        String localId = o.parts().localId();
        int slash = localId.indexOf('/', HUD.length());
        if (!localId.startsWith(HUD) || slash < 0 || slash == localId.length() - 1) {
            return null;
        }
        String panelId = localId.substring(HUD.length(), slash);
        if (!panelId.equals(field)) {
            return null;
        }
        String panelKey = o.parts().owner() + '/' + o.parts().extension() + '/' + HUD + panelId;
        return new Widget(o.key(), panelKey, panelId, order, data);
    }
}
