package io.github.pix3lpirat3.mfbridge.common.ui.home;

import io.github.pix3lpirat3.mfbridge.common.BridgeClient;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ext.ClientExtensions;
import io.github.pix3lpirat3.mfbridge.common.ext.ExtensionsScreen;
import io.github.pix3lpirat3.mfbridge.common.settings.BridgeSettings;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.CommandPalette;
import io.github.pix3lpirat3.mfbridge.common.ui.declared.DeclaredScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.declared.ScreenView;
import io.github.pix3lpirat3.mfbridge.common.ui.devices.PairScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.CrewRoster;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.Presence;
import io.github.pix3lpirat3.mfbridge.core.command.TreeNode;
import io.github.pix3lpirat3.mfbridge.core.decl.ActionDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.core.diag.ConnectionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Bridge home: the way into everything the bridge offers. Its built-in tabs are a
 * screen model drawn by {@link ScreenView}, the renderer bot screens use too:
 *
 * <ul>
 *   <li><b>Bots</b>: every known bot from presence, online or offline, with its attach toggle, focus,
 *       the bot buttons (bot-target actions of the bot itself and of controllers), its commands, its
 *       settings and forgetting it. Menus that bots offer as the primary menu are listed on top until
 *       the player picks one.</li>
 *   <li><b>Controllers</b>: the global actions and commands of each connected controller session,
 *       which is how a bot-manager addon offers "Add bot", then the global actions of bots.</li>
 *   <li><b>Tools</b>: palette, inspector, HUD editor, devices, pairing, settings, the primary menu and
 *       the intercept pause switch.</li>
 *   <li>Then every tab of every screen declared with {@code home: tab}.</li>
 * </ul>
 *
 * <p>The footer shows the endpoint: listening address and port, TLS state and connection count. On
 * the title screen, parts that need a world are hidden.
 */
public final class BridgeHomeScreen extends ScreenView implements io.github.pix3lpirat3.mfbridge.common.ui.screen.FormHost {

    private static final int TOOL_WIDTH = (COLUMN_WIDTH - GAP) / 2;
    private static final String CONTROLLER = "controller";
    /** Ids of contributed items are {@code <screen key>#<item id>}; built-in ids never contain it. */
    private static final char CONTRIBUTED = '#';
    /** Ids of extension tab items are {@code ext<tab index>|<item id>}. */
    private static final String EXTENSION = "ext";
    private static final char EXTENSION_SEPARATOR = '|';

    /** The tab shown last, so the screen reopens where the player left it. */
    private static @Nullable String lastTab;

    private final BridgeClient client;
    private final BridgeUi ui;
    /** Built-in controls by item id, rebuilt with every model. */
    private final Map<String, Consumer<@Nullable Object>> handlers = new HashMap<>();
    /** Contributed screens by key, from the last model. */
    private final Map<String, ScreenDecl> contributed = new HashMap<>();
    private int ids;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param client the bridge client, for the UI layer, the settings and the settings screens
     */
    public BridgeHomeScreen(final @Nullable Screen parent, final BridgeClient client) {
        super(Component.literal(Constants.MOD_NAME), parent);
        this.client = client;
        this.ui = client.ui();
        selectTab(lastTab);
    }

    private boolean inWorld() {
        return minecraft.level != null && minecraft.player != null;
    }

    @Override
    public void tick() {
        super.tick();
        lastTab = currentTabId();
    }

    @Override
    protected ScreenData model() {
        handlers.clear();
        contributed.clear();
        ids = 0;
        final BridgeCore core = ui.core();
        final List<ScreenTab> tabs = new ArrayList<>();
        tabs.add(new ScreenTab("bots", I18n.get("screen.mineflayer_bridge.home.tab.bots"), botsTab(core)));
        tabs.add(new ScreenTab("controllers", I18n.get("screen.mineflayer_bridge.home.tab.controllers"), controllersTab(core)));
        tabs.add(new ScreenTab("tools", I18n.get("screen.mineflayer_bridge.home.tab.tools"), toolsTab(core)));
        if (core != null) {
            final List<ScreenDecl> screens = new ArrayList<>(core.declarations().screens());
            screens.sort(Comparator.comparingInt((ScreenDecl s) -> s.data().order()).thenComparing(ScreenDecl::ownerName));
            for (final ScreenDecl s : screens) {
                if (ScreenDecl.HOME_TAB.equals(s.data().home())) {
                    contributed.put(s.key(), s);
                    for (final ScreenTab t : s.data().tabs()) {
                        tabs.add(new ScreenTab(s.key() + CONTRIBUTED + t.id(), t.label(), qualified(s.key(), t.rows())));
                    }
                }
            }
        }
        final List<ClientExtensions.Tab> extensionTabs = client.extensions().tabs();
        for (int i = 0; i < extensionTabs.size(); i++) {
            final ClientExtensions.Tab t = extensionTabs.get(i);
            final String prefix = EXTENSION + i + EXTENSION_SEPARATOR;
            final List<ScreenRow> rows = new ArrayList<>();
            client.extensions().guard(t.extensionId(), () -> rows.addAll(t.tab().rows()));
            tabs.add(new ScreenTab(prefix, t.label().getString(), rows.stream().map(r -> new ScreenRow(r.items().stream()
                    .map(it -> it.id() == null ? it : it.withId(prefix + it.id())).toList())).toList()));
        }
        return new ScreenData(Constants.MOD_NAME, tabs, "none", 0);
    }

    /** Prefixes item ids with their screen key, so a click finds its owner. */
    private static List<ScreenRow> qualified(final String screenKey, final List<ScreenRow> rows) {
        return rows.stream().map(r -> new ScreenRow(r.items().stream().map(it -> it.id() == null ? it
                : it.withId(screenKey + CONTRIBUTED + it.id())).toList())).toList();
    }

    @Override
    protected void use(final ScreenItem item, final @Nullable Object value) {
        final String id = item.id();
        if (id == null) {
            return;
        }
        if (id.startsWith(EXTENSION) && id.indexOf(EXTENSION_SEPARATOR) > 0) {
            final int sep = id.indexOf(EXTENSION_SEPARATOR);
            final List<ClientExtensions.Tab> extensionTabs = client.extensions().tabs();
            final int index = Integer.parseInt(id.substring(EXTENSION.length(), sep));
            if (index < extensionTabs.size()) {
                final ClientExtensions.Tab t = extensionTabs.get(index);
                client.extensions().guard(t.extensionId(), () -> t.tab().use(id.substring(sep + 1), value));
                invalidate();
            }
            return;
        }
        final int at = id.indexOf(CONTRIBUTED);
        if (at < 0) {
            final Consumer<@Nullable Object> handler = handlers.get(id);
            if (handler != null) {
                handler.accept(value);
                invalidate();
            }
            return;
        }
        final ScreenDecl screen = contributed.get(id.substring(0, at));
        if (screen == null) {
            return;
        }
        if (item.open() != null && value == null) {
            ui.surfaces().openScreen(this, item.open());
        }
        DeclaredScreen.send(ui, screen.ownerName(), screen.key(), item.withId(id.substring(at + 1)), value, this::invalidate);
    }

    @Override
    protected Component status() {
        return endpointLine(ui.core());
    }

    // Item builders. Each control gets a fresh id bound to its handler for this model.

    private String bind(final Consumer<@Nullable Object> handler) {
        final String id = "home." + ids++;
        handlers.put(id, handler);
        return id;
    }

    private static ScreenItem heading(final String text) {
        return new ScreenItem("heading").withText(text);
    }

    private static ScreenItem text(final String text, final int argb) {
        return new ScreenItem("text").withText(text).withColor(argb);
    }

    private ScreenItem button(final String label, final @Nullable String icon, final @Nullable String tooltip, final int width,
            final boolean enabled, final Runnable run) {
        return new ScreenItem("button").withId(bind(v -> run.run())).withText(label).withTooltip(tooltip).withIcon(icon).withWidth(width)
                .withEnabled(enabled);
    }

    private ScreenItem toggle(final String label, final boolean value, final String tooltip, final int width, final Consumer<Boolean> set) {
        return new ScreenItem("toggle").withId(bind(v -> set.accept(Boolean.TRUE.equals(v)))).withText(label).withTooltip(tooltip)
                .withValue(value).withWidth(width);
    }

    private static ScreenRow row(final ScreenItem... items) {
        return new ScreenRow(List.of(items));
    }

    /** Lays buttons out two per row, as a tool grid. */
    private static void grid(final List<ScreenRow> rows, final List<ScreenItem> buttons) {
        for (int i = 0; i < buttons.size(); i += 2) {
            rows.add(new ScreenRow(buttons.subList(i, Math.min(i + 2, buttons.size()))));
        }
    }

    // Bots

    private List<ScreenRow> botsTab(final @Nullable BridgeCore core) {
        final List<ScreenRow> rows = new ArrayList<>();
        if (core == null) {
            rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.not_running.hint"), UiColors.ERROR)));
            return rows;
        }
        offeredMenus(core, rows);
        final List<Presence> bots = core.presence().all();
        final List<CrewRoster.Unseen> unseen = CrewRoster.unseen(core.declarations().crews(), bots);
        if (bots.isEmpty() && unseen.isEmpty()) {
            rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.bots.empty"), UiColors.LABEL)));
        }
        final long now = System.currentTimeMillis();
        for (final Presence p : bots) {
            botRows(rows, core, p, now);
        }
        for (final CrewRoster.Unseen u : unseen) {
            unseenRows(rows, core.declarations(), u);
        }
        return rows;
    }

    /** A crew bot that has not connected in this game: its controller's buttons for it, such as one to connect it. */
    private void unseenRows(final List<ScreenRow> rows, final DeclarationSnapshot decl, final CrewRoster.Unseen u) {
        rows.add(row(heading(u.name())));
        rows.add(row(text("§7" + I18n.get("screen.mineflayer_bridge.home.bot.unseen", u.controller()), UiColors.LABEL)));
        if (!inWorld()) {
            return;
        }
        final String owner = DisplayText.botOwner(u.name().toLowerCase(Locale.ROOT) + "@crew");
        final List<ScreenItem> buttons = new ArrayList<>();
        for (final ActionDecl a : decl.actions()) {
            if (a.key().startsWith("ctl:") && a.scope() == Scope.GLOBAL && TargetMatch.matches(a.data().target(), "bot", u.name(), tag -> false)
                    && a.data().target() != null && a.data().target().ids() != null) {
                buttons.add(button(a.data().label(), a.data().icon(), I18n.get("mineflayer_bridge.from", a.ownerName()),
                        font.width(a.data().label()) + (a.data().icon() == null ? 12 : 30), true, () -> ui.actions().runOnBot(a, owner)));
            }
        }
        if (!buttons.isEmpty()) {
            rows.add(new ScreenRow(buttons));
        }
    }

    /** Offered menus are listed until the player picks one. */
    private void offeredMenus(final BridgeCore core, final List<ScreenRow> rows) {
        if (core.screens().primaryMenuChoice() != null) {
            return;
        }
        for (final ScreenDecl s : core.declarations().screens()) {
            if (ScreenDecl.HOME_MENU.equals(s.data().home())) {
                rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.menu.offered", "§l" + s.ownerName() + "§r§7", s.data().title()),
                        UiColors.TEXT),
                        button(I18n.get("screen.mineflayer_bridge.home.menu.open"), null, null, 52, true, () -> ui.surfaces().openScreen(this, s.key())),
                        button(I18n.get("screen.mineflayer_bridge.home.menu.use"), null, I18n.get("screen.mineflayer_bridge.home.menu.use.tooltip"),
                                100, true,
                                () -> core.screens().setPrimaryMenu(s.key()))));
            }
        }
    }

    private void botRows(final List<ScreenRow> rows, final BridgeCore core, final Presence p, final long now) {
        final String identity = p.identity();
        final String owner = DisplayText.botOwner(identity);
        rows.add(row(heading(p.displayName())));
        final StringBuilder line = new StringBuilder(p.connected() ? "§a" + I18n.get("screen.mineflayer_bridge.home.bot.online")
                : "§7" + I18n.get("screen.mineflayer_bridge.home.bot.offline", DisplayText.age(I18n::get, p.lastSeenMillis(), now)));
        if (inWorld()) {
            final boolean sameWorld = core.attach().worldMatched(identity);
            line.append(sameWorld ? "§7, " + I18n.get("screen.mineflayer_bridge.home.bot.same_world")
                    : "§7, §e" + I18n.get("screen.mineflayer_bridge.home.bot.other_world"));
            if (sameWorld && p.connected() && !onPlayerList(p.displayName())) {
                line.append("§7, ").append(I18n.get("screen.mineflayer_bridge.home.bot.not_listed"));
            }
        }
        rows.add(row(text(line.toString(), UiColors.LABEL)));
        final String status = DisplayText.status(p.status());
        if (!status.isEmpty()) {
            rows.add(row(text(status, UiColors.LABEL)));
        }

        final DeclarationSnapshot decl = core.declarations();
        final List<ScreenItem> buttons = new ArrayList<>();
        buttons.add(toggle(I18n.get("screen.mineflayer_bridge.home.attached"), core.attach().isAttached(identity),
                I18n.get(core.attach().choice(identity) == null ? "screen.mineflayer_bridge.home.attached.auto"
                        : "screen.mineflayer_bridge.home.attached.chosen"), 100, value -> core.attach().setChoice(identity, value)));
        final boolean focused = owner.equals(core.focus().focused());
        buttons.add(button(I18n.get(focused ? "screen.mineflayer_bridge.home.focused" : "screen.mineflayer_bridge.home.focus"), null, null, 52, !focused && core.attach().isAttached(identity) && p.connected(),
                () -> ui.focus(owner)));
        if (inWorld()) {
            for (final ActionDecl a : botButtons(decl, owner, p.displayName())) {
                buttons.add(button(a.data().label(), a.data().icon(), a.ownerName().equals(p.displayName()) ? null : I18n.get("mineflayer_bridge.from", a.ownerName()),
                        font.width(a.data().label()) + (a.data().icon() == null ? 12 : 30), true, () -> ui.actions().runOnBot(a, owner)));
            }
        }
        for (final ClientExtensions.Button x : client.extensions().botButtons()) {
            if (x.button().shows(owner, p.connected())) {
                final String label = x.label().getString();
                buttons.add(button(label, x.icon(), null, font.width(label) + (x.icon() == null ? 12 : 30), true,
                        () -> client.extensions().guard(x.extensionId(), () -> x.button().press(owner))));
            }
        }
        final boolean commands = hasCommands(decl, owner) || !CommandPalette.ordersFor(core, owner).isEmpty();
        buttons.add(button(I18n.get("screen.mineflayer_bridge.home.commands"), null,
                commands ? null : I18n.get("screen.mineflayer_bridge.home.commands.none"), 62, commands,
                () -> ui.palette().open(owner, p.displayName())));
        buttons.add(button(I18n.get("screen.mineflayer_bridge.home.settings"), null, null, 56, decl.settings().stream().anyMatch(s -> DisplayText.ownedBy(s.key(), owner)),
                () -> minecraft.gui.setScreen(client.createSettingsScreen(this, owner))));
        if (!p.connected()) {
            buttons.add(button(I18n.get("screen.mineflayer_bridge.home.forget"), null, I18n.get("screen.mineflayer_bridge.home.forget.tooltip"), 46, true, () -> core.presence().forget(identity)));
        }
        rows.add(new ScreenRow(buttons));
    }

    /** The bot's own bot-target actions, then those of controllers; an action that names bots shows on theirs only. */
    private static List<ActionDecl> botButtons(final DeclarationSnapshot decl, final String owner, final String name) {
        final List<ActionDecl> out = new ArrayList<>();
        for (final ActionDecl a : decl.actions()) {
            if (TargetMatch.matches(a.data().target(), "bot", name, tag -> false)
                    && (DisplayText.ownedBy(a.key(), owner) || a.key().startsWith("ctl:") && a.scope() == Scope.GLOBAL)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Whether the server's player list has this name; a simulated bot or a bot on another server has no entry. */
    private boolean onPlayerList(final String name) {
        final ClientPacketListener connection = minecraft.getConnection();
        return connection != null && connection.getPlayerInfo(name) != null;
    }

    private static boolean hasCommands(final DeclarationSnapshot decl, final String owner) {
        return decl.commands().stream().anyMatch(c -> DisplayText.ownedBy(c.key(), owner))
                || decl.actions().stream().anyMatch(a -> a.scope() == Scope.GLOBAL && DisplayText.ownedBy(a.key(), owner));
    }

    // Controllers

    private List<ScreenRow> controllersTab(final @Nullable BridgeCore core) {
        final List<ScreenRow> rows = new ArrayList<>();
        if (core == null) {
            rows.add(row(text(I18n.get("mineflayer_bridge.endpoint.stopped"), UiColors.ERROR)));
            return rows;
        }
        final DeclarationSnapshot decl = core.declarations();
        final List<SessionInfo> controllers = new ArrayList<>();
        for (final SessionInfo s : core.inspector().sessions()) {
            if (s.kind().equals(CONTROLLER) && s.connected() && !s.superseded()) {
                controllers.add(s);
            }
        }
        if (controllers.isEmpty()) {
            rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.controllers.empty"), UiColors.LABEL)));
        }
        for (final SessionInfo s : controllers) {
            rows.add(row(heading(s.displayName())));
            final List<ScreenItem> buttons = globalActions(decl, s.owner());
            final List<ScreenItem> orders = new ArrayList<>();
            for (final CommandDecl c : decl.commands()) {
                if (DisplayText.ownedBy(c.key(), s.owner())) {
                    (c.data().orders() ? orders : buttons).add(button("/" + c.data().name(), null,
                            c.data().description() == null ? I18n.get("screen.mineflayer_bridge.home.command") : c.data().description(),
                            TOOL_WIDTH, true, () -> runCommand(c, s)));
                }
            }
            if (buttons.isEmpty() && orders.isEmpty()) {
                rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.controller.empty"), UiColors.LABEL)));
            }
            grid(rows, buttons);
            if (!orders.isEmpty()) {
                rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.controller.orders"), UiColors.LABEL)));
                grid(rows, orders);
            }
        }
        final List<String> owners = new ArrayList<>();
        for (final ActionDecl a : decl.actions()) {
            final String owner = botOwnerOf(a.key());
            if (a.scope() == Scope.GLOBAL && owner != null && !owners.contains(owner)) {
                owners.add(owner);
                rows.add(row(heading(a.ownerName())));
                rows.add(row(text(I18n.get("screen.mineflayer_bridge.home.bot.global_actions"), UiColors.LABEL)));
                grid(rows, globalActions(decl, owner));
            }
        }
        return rows;
    }

    private List<ScreenItem> globalActions(final DeclarationSnapshot decl, final String owner) {
        final List<ScreenItem> out = new ArrayList<>();
        for (final ActionDecl a : decl.actions()) {
            if (a.scope() == Scope.GLOBAL && DisplayText.ownedBy(a.key(), owner) && !a.data().target().type().equals("bot")) {
                out.add(button(a.data().label(), a.data().icon(), null, TOOL_WIDTH, true, () -> ui.actions().runAtPlayer(a)));
            }
        }
        return out;
    }

    /** A runnable root command runs, through an argument form when it has arguments; a command tree opens the palette. */
    private void runCommand(final CommandDecl c, final SessionInfo s) {
        if (TreeNode.of(c.data()).runnable()) {
            ui.palette().runCommand(c);
        } else {
            ui.palette().open(s.owner(), s.displayName());
        }
    }

    private static @Nullable String botOwnerOf(final String key) {
        if (!key.startsWith("bot:")) {
            return null;
        }
        final int slash = key.indexOf('/');
        return slash < 0 ? null : key.substring(0, slash);
    }

    // Tools

    private List<ScreenRow> toolsTab(final @Nullable BridgeCore core) {
        final List<ScreenItem> tools = new ArrayList<>();
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.palette"), null, I18n.get("screen.mineflayer_bridge.home.tool.palette.tooltip"),
                TOOL_WIDTH, core != null, ui.palette()::open));
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.inspector"), null,
                I18n.get("screen.mineflayer_bridge.home.tool.inspector.tooltip"), TOOL_WIDTH, true,
                () -> ui.openInspector(this)));
        if (inWorld()) {
            tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.hud_editor"), null,
                    I18n.get("screen.mineflayer_bridge.home.tool.hud_editor.tooltip"), TOOL_WIDTH, core != null, () -> ui.openHudEditor(this)));
        }
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.devices"), null,
                I18n.get("screen.mineflayer_bridge.home.tool.devices.tooltip"), TOOL_WIDTH, true, () -> ui.openDevices(this)));
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.pair"), null,
                I18n.get("screen.mineflayer_bridge.home.tool.pair.tooltip"), TOOL_WIDTH, core != null,
                () -> minecraft.gui.setScreen(new PairScreen(this, ui::core))));
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.settings"), null,
                I18n.get("screen.mineflayer_bridge.home.tool.settings.tooltip"), TOOL_WIDTH, true,
                () -> minecraft.gui.setScreen(client.createSettingsScreen(this, null))));
        tools.add(button(I18n.get("screen.mineflayer_bridge.home.tool.extensions"), null,
                I18n.get("screen.mineflayer_bridge.home.tool.extensions.tooltip"), TOOL_WIDTH, true,
                () -> minecraft.gui.setScreen(new ExtensionsScreen(this, client.extensions()))));
        if (core != null) {
            tools.add(primaryMenu(core));
        }
        if (inWorld() && core != null) {
            tools.add(new ScreenItem("cycle").withId(bind(v -> core.intercepts().setPaused(Long.valueOf(1).equals(v)))).withText(I18n.get("screen.mineflayer_bridge.home.intercepts"))
                    .withTooltip(I18n.get("screen.mineflayer_bridge.home.intercepts.tooltip")).withValue(core.intercepts().paused() ? 1L : 0L)
                    .withOptions(List.of(I18n.get("screen.mineflayer_bridge.home.intercepts.active"), I18n.get("screen.mineflayer_bridge.home.intercepts.paused"))).withWidth(TOOL_WIDTH));
        }
        final List<ScreenRow> rows = new ArrayList<>();
        grid(rows, tools);
        return rows;
    }

    /** Bridge home or one of the menus bots offer. */
    private ScreenItem primaryMenu(final BridgeCore core) {
        final List<String> keys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        keys.add("");
        names.add(I18n.get("mineflayer_bridge.home"));
        for (final ScreenDecl s : core.declarations().screens()) {
            if (ScreenDecl.HOME_MENU.equals(s.data().home())) {
                keys.add(s.key());
                names.add(s.ownerName() + ": " + s.data().title());
            }
        }
        final String chosen = core.screens().primaryMenu();
        final long index = chosen == null ? 0 : Math.max(0, keys.indexOf(chosen));
        final String id = bind(v -> {
            final String key = keys.get(((Number) v).intValue());
            core.screens().setPrimaryMenu(key.isEmpty() ? null : key);
        });
        return new ScreenItem("cycle").withId(id).withText(I18n.get("screen.mineflayer_bridge.home.primary_menu"))
                .withTooltip(I18n.get("screen.mineflayer_bridge.home.primary_menu.tooltip")).withValue(index)
                .withOptions(names).withWidth(TOOL_WIDTH);
    }

    private Component endpointLine(final @Nullable BridgeCore core) {
        final BridgeSettings settings = client.settings();
        if (core == null) {
            return Component.translatable("screen.mineflayer_bridge.home.endpoint.down", settings.port()).withColor(UiColors.ERROR & 0xFFFFFF);
        }
        final List<ConnectionInfo> connections = core.inspector().connections();
        final long secure = connections.stream().filter(ConnectionInfo::secure).count();
        final Component tls = Component.translatable(core.config().allowPlainLoopback() ? "screen.mineflayer_bridge.home.endpoint.tls_off"
                : "screen.mineflayer_bridge.home.endpoint.tls_required");
        final boolean one = connections.size() == 1;
        final MutableComponent count = secure > 0
                ? Component.translatable(one ? "screen.mineflayer_bridge.home.endpoint.connections.one.tls"
                        : "screen.mineflayer_bridge.home.endpoint.connections.other.tls", connections.size(), secure)
                : Component.translatable(one ? "screen.mineflayer_bridge.home.endpoint.connections.one"
                        : "screen.mineflayer_bridge.home.endpoint.connections.other", connections.size());
        return Component.translatable("mineflayer_bridge.endpoint.listening", core.config().bindAddress(), core.boundPort())
                .withColor(UiColors.LABEL & 0xFFFFFF)
                .append(Component.literal("  |  ").append(tls).append("  |  ").withColor(UiColors.LABEL & 0xFFFFFF))
                .append(count.withColor((connections.isEmpty() ? UiColors.LABEL : UiColors.GOOD) & 0xFFFFFF));
    }
}
