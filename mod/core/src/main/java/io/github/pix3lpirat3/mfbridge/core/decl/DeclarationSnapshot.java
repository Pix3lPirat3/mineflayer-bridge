package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of every input and HUD declaration the UI should offer. Contains
 * only objects of sessions that are attached; world-scoped objects appear only while their world
 * matches the player's, and expired objects never appear. Shared and player objects are not
 * declarations and never appear.
 *
 * <p>The core publishes a new instance only when the content changed; otherwise {@code
 * BridgeCore.declarations()} keeps returning the same instance, so the UI can compare by identity or
 * by {@link #version()} to skip rebuilding menus and HUD layouts.
 */
@AnyThread
public final class DeclarationSnapshot {
    /** Snapshot with no declarations and version 0. */
    public static final DeclarationSnapshot EMPTY =
            new DeclarationSnapshot(0, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of());

    private final long version;
    private final List<CommandDecl> commands;
    private final List<ActionDecl> actions;
    private final List<KeybindDecl> keybinds;
    private final List<ToolDecl> tools;
    private final List<SettingsDecl> settings;
    private final Map<String, List<HudPanel>> hud;
    private final List<BossbarDecl> bossbars;
    private final List<ContainerViewDecl> containerViews;
    private final List<InterceptDecl> intercepts;
    private final List<ScreenDecl> screens;
    private final List<CrewDecl> crews;

    /**
     * Creates a snapshot. Every collection is copied; the iteration order of {@code hud} is kept.
     *
     * @param version monotonically increasing content version
     * @param hud panels by owner (for example {@code bot:<identity>}), each list sorted by panel
     *     {@code order}, then key
     * @param containerViews container views and menus in key order
     * @param intercepts interaction intercepts in key order
     * @param crews crews in key order
     */
    public DeclarationSnapshot(
            long version,
            List<CommandDecl> commands,
            List<ActionDecl> actions,
            List<KeybindDecl> keybinds,
            List<ToolDecl> tools,
            List<SettingsDecl> settings,
            Map<String, List<HudPanel>> hud,
            List<BossbarDecl> bossbars,
            List<ContainerViewDecl> containerViews,
            List<InterceptDecl> intercepts,
            List<ScreenDecl> screens,
            List<CrewDecl> crews) {
        this.version = version;
        this.commands = List.copyOf(commands);
        this.actions = List.copyOf(actions);
        this.keybinds = List.copyOf(keybinds);
        this.tools = List.copyOf(tools);
        this.settings = List.copyOf(settings);
        LinkedHashMap<String, List<HudPanel>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<HudPanel>> e : hud.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.hud = Collections.unmodifiableMap(copy);
        this.bossbars = List.copyOf(bossbars);
        this.containerViews = List.copyOf(containerViews);
        this.intercepts = List.copyOf(intercepts);
        this.screens = List.copyOf(screens);
        this.crews = List.copyOf(crews);
    }

    /** Returns the content version; a larger version means newer content. */
    public long version() {
        return version;
    }

    /** Returns commands in key order. Immutable. */
    public List<CommandDecl> commands() {
        return commands;
    }

    /** Returns context actions in key order. Immutable. */
    public List<ActionDecl> actions() {
        return actions;
    }

    /** Returns keybinds in key order. Immutable. */
    public List<KeybindDecl> keybinds() {
        return keybinds;
    }

    /** Returns tools in key order. Immutable. */
    public List<ToolDecl> tools() {
        return tools;
    }

    /** Returns settings groups in key order, with current values. Immutable. */
    public List<SettingsDecl> settings() {
        return settings;
    }

    /**
     * Returns HUD panels grouped by owner (owner without trailing slash), owners in key order, panels
     * sorted by {@code order}, then key. Immutable.
     */
    public Map<String, List<HudPanel>> hud() {
        return hud;
    }

    /** Returns boss bars in key order. Immutable. */
    public List<BossbarDecl> bossbars() {
        return bossbars;
    }

    /** Returns container views and menus in key order. Immutable. */
    public List<ContainerViewDecl> containerViews() {
        return containerViews;
    }

    /** Returns interaction intercepts of attached sessions in key order. Immutable. */
    public List<InterceptDecl> intercepts() {
        return intercepts;
    }

    /** Returns the crews of attached sessions in key order: who each one gives orders to. Immutable. */
    public List<CrewDecl> crews() {
        return crews;
    }

    /**
     * Returns the container view with this key.
     *
     * @return the view, or null when no attached session declares it
     */
    public @Nullable ContainerViewDecl containerView(String key) {
        for (ContainerViewDecl v : containerViews) {
            if (v.key().equals(key)) {
                return v;
            }
        }
        return null;
    }

    /**
     * Returns the declared screen at a key.
     *
     * @param key object key of a {@code core:screen}
     * @return the screen, or null when no attached session declares it
     */
    public @Nullable ScreenDecl screen(String key) {
        for (ScreenDecl s : screens) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** Returns whether this snapshot has the same content as {@code other}, ignoring versions. */
    public boolean sameContent(DeclarationSnapshot other) {
        return commands.equals(other.commands)
                && actions.equals(other.actions)
                && keybinds.equals(other.keybinds)
                && tools.equals(other.tools)
                && settings.equals(other.settings)
                && hud.equals(other.hud)
                && List.copyOf(hud.keySet()).equals(List.copyOf(other.hud.keySet()))
                && bossbars.equals(other.bossbars)
                && containerViews.equals(other.containerViews)
                && intercepts.equals(other.intercepts)
                && screens.equals(other.screens)
                && crews.equals(other.crews);
    }

    /** Returns a short summary for diagnostics. */
    @Override
    public String toString() {
        return "DeclarationSnapshot[v" + version + ", commands=" + commands.size() + ", actions=" + actions.size()
                + ", keybinds=" + keybinds.size() + ", tools=" + tools.size() + ", settings=" + settings.size()
                + ", hudOwners=" + hud.size() + ", bossbars=" + bossbars.size()
                + ", containerViews=" + containerViews.size() + ", intercepts=" + intercepts.size() + ", screens="
                + screens.size() + "]";
    }

    /**
     * Returns the declared screens of attached sessions, in owner order.
     *
     * @return the screens
     */
    public List<ScreenDecl> screens() {
        return screens;
    }
}
