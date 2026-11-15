package io.github.pix3lpirat3.mfbridge.common.ui;

import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldSpec;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.FormScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PaletteScreen;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.command.Addressee;
import io.github.pix3lpirat3.mfbridge.core.command.BotCommands;
import io.github.pix3lpirat3.mfbridge.core.command.CommandEngine;
import io.github.pix3lpirat3.mfbridge.core.command.CommandUsage;
import io.github.pix3lpirat3.mfbridge.core.command.ParseResult;
import io.github.pix3lpirat3.mfbridge.core.command.TreeNode;
import io.github.pix3lpirat3.mfbridge.core.decl.ActionDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The command palette and running commands from screens: lists every runnable command node, the
 * global actions, nearby things to edit and the recent commands, and runs a command right away or
 * through an argument form. Client thread only.
 */
public final class CommandPalette {

    /** Most "Edit zone" entries the palette lists, nearest first. */
    private static final int MAX_EDIT_ENTRIES = 30;

    private final UiContext ui;
    private final Predicate<String> focusAllows;
    private final SpatialTools spatial;
    private final ActionRunner actions;
    private final FormScreen.BlockPicker picker;
    private final Supplier<List<PaletteScreen.Entry>> builtIn;

    /**
     * Creates the palette.
     *
     * @param ui the core, toasts and the tracker of answers
     * @param focusAllows whether an object key shows with the bot that is focused now
     * @param spatial the zone tools, for the edit, undo and redo entries
     * @param actions runs the global actions
     * @param picker picks a block in the world for a form field
     * @param builtIn the mod's own entries, built each time the palette opens
     */
    public CommandPalette(final UiContext ui, final Predicate<String> focusAllows, final SpatialTools spatial, final ActionRunner actions,
            final FormScreen.BlockPicker picker, final Supplier<List<PaletteScreen.Entry>> builtIn) {
        this.ui = ui;
        this.focusAllows = focusAllows;
        this.spatial = spatial;
        this.actions = actions;
        this.picker = picker;
        this.builtIn = builtIn;
    }

    /** Opens the command palette with every runnable command node and the global actions of attached bots. */
    public void open() {
        final DeclarationSnapshot decl = ui.declarations();
        final List<PaletteScreen.Entry> entries = new ArrayList<>();
        addOwnedEntries(decl, null, entries);
        addSpatialEntries(entries);
        entries.addAll(builtIn.get());
        entries.sort(Comparator.comparing(PaletteScreen.Entry::owner).thenComparing(PaletteScreen.Entry::label));
        entries.addAll(0, recentEntries(null));
        Minecraft.getInstance().gui.setScreen(new PaletteScreen(entries));
    }

    /**
     * Opens the command palette with only the commands and global actions of one session,
     * for a bot's Commands button in Bridge home.
     *
     * @param owner the session's owner, such as {@code bot:<identity>} or {@code ctl:<device>/<name>}
     * @param name the session's display name, for the title
     */
    public void open(final String owner, final String name) {
        final List<PaletteScreen.Entry> entries = new ArrayList<>();
        addOwnedEntries(ui.declarations(), owner, entries);
        for (final BotCommands set : ordersFor(ui.core(), owner)) {
            for (final CommandDecl c : set.commands()) {
                addNodes(c, List.of(), TreeNode.of(c.data()), entries, set);
            }
        }
        entries.sort(Comparator.comparing(PaletteScreen.Entry::label));
        entries.addAll(0, recentEntries(name));
        Minecraft.getInstance().gui.setScreen(new PaletteScreen(Component.translatable("screen.mineflayer_bridge.palette.title.bot", name), entries));
    }

    /** Commands and global actions, all of them or only those under {@code owner}. */
    private void addOwnedEntries(final DeclarationSnapshot decl, final @Nullable String owner, final List<PaletteScreen.Entry> out) {
        for (final CommandDecl c : decl.commands()) {
            if (owner == null ? focusAllows.test(c.key()) : DisplayText.ownedBy(c.key(), owner)) {
                addNodes(c, List.of(), TreeNode.of(c.data()), out, null);
            }
        }
        for (final ActionDecl a : decl.actions()) {
            if (a.scope() == Scope.GLOBAL && (owner == null ? focusAllows.test(a.key()) : DisplayText.ownedBy(a.key(), owner))) {
                out.add(new PaletteScreen.Entry(a.data().label(), I18n.get("screen.mineflayer_bridge.palette.action"), a.ownerName(),
                        () -> actions.runGlobal(a)));
            }
        }
    }

    /** "Edit zone" entries for the nearest editable definitions, plus undo and redo. */
    private void addSpatialEntries(final List<PaletteScreen.Entry> out) {
        final BridgeCore core = ui.core();
        final LocalPlayer player = Minecraft.getInstance().player;
        if (core == null || player == null) {
            return;
        }
        final List<SpatialIndex.Item> items = new ArrayList<>();
        for (final SpatialIndex.Item it : core.spatial().all()) {
            if (it.editable()) {
                items.add(it);
            }
        }
        items.sort(Comparator.comparingDouble(it -> it.distanceSq(player.getX(), player.getY(), player.getZ())));
        for (final SpatialIndex.Item it : items.subList(0, Math.min(items.size(), MAX_EDIT_ENTRIES))) {
            final String what = switch (it.kind()) {
                case ZONE -> "screen.mineflayer_bridge.palette.edit_zone";
                case POINT -> "screen.mineflayer_bridge.palette.edit_point";
                case ROUTE -> "screen.mineflayer_bridge.palette.edit_route";
            };
            final long metres = Math.round(Math.sqrt(it.distanceSq(player.getX(), player.getY(), player.getZ())));
            out.add(new PaletteScreen.Entry(I18n.get(what, it.name()), I18n.get("screen.mineflayer_bridge.palette.edit.detail", metres,
                    it.owner()), SpatialTools.OWNER,
                    () -> spatial.editor().select(it)));
        }
        if (core.history().canUndo()) {
            out.add(new PaletteScreen.Entry(I18n.get("screen.mineflayer_bridge.palette.undo"),
                    I18n.get("screen.mineflayer_bridge.palette.undo.detail"), SpatialTools.OWNER, () -> spatial.undo(true)));
        }
        if (core.history().canRedo()) {
            out.add(new PaletteScreen.Entry(I18n.get("screen.mineflayer_bridge.palette.redo"),
                    I18n.get("screen.mineflayer_bridge.palette.redo.detail"), SpatialTools.OWNER, () -> spatial.undo(false)));
        }
    }

    /**
     * Every runnable node of a tree is its own palette entry. With {@code set}, the entries are orders
     * run for that set's bots, without asking who.
     */
    private void addNodes(final CommandDecl c, final List<String> path, final TreeNode node, final List<PaletteScreen.Entry> out,
            final @Nullable BotCommands set) {
        if (node.runnable()) {
            out.add(new PaletteScreen.Entry(commandLabel(c, path), commandDetail(node), c.ownerName(), () -> runCommandIn(set, c, path)));
        }
        for (final TreeNode child : node.children()) {
            final List<String> next = new ArrayList<>(path);
            next.add(child.name());
            addNodes(c, List.copyOf(next), child, out, set);
        }
    }

    /**
     * The crews' orders that reach one bot, each set carrying that bot as the one they are for.
     *
     * @param core the core, or null while it is down
     * @param owner the bot's owner, such as {@code bot:<identity>}
     * @return the order sets; empty for a controller or a bot in no crew
     */
    public static List<BotCommands> ordersFor(final @Nullable BridgeCore core, final String owner) {
        if (core == null) {
            return List.of();
        }
        for (final Addressee a : core.commands().addressees()) {
            if (a.kind() == Addressee.Kind.BOT && a.chatOwners().contains(owner)) {
                final List<BotCommands> out = new ArrayList<>();
                for (final BotCommands set : a.commands()) {
                    if (!set.bots().isEmpty() && !set.commands().isEmpty()) {
                        out.add(set);
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    /** A command node's label: the command name followed by its subcommand path. */
    private static String commandLabel(final CommandDecl c, final List<String> path) {
        return path.isEmpty() ? c.data().name() : c.data().name() + " " + String.join(" ", path);
    }

    private static String commandDetail(final TreeNode node) {
        final String args = CommandUsage.args(node);
        final String description = node.description();
        return description == null ? args : (args + "  " + description).strip();
    }

    /**
     * Runs a root command: right away without arguments, otherwise through an argument form.
     *
     * @param c the command
     */
    public void runCommand(final CommandDecl c) {
        runCommand(c, List.of());
    }

    /**
     * Runs a command node: right away without arguments, otherwise through an argument form whose
     * fields are validated by the core's command engine and suggest values from it.
     *
     * @param c the command
     * @param path canonical subcommand names of the node
     */
    public void runCommand(final CommandDecl c, final List<String> path) {
        runCommandIn(null, c, path);
    }

    /** As {@link #runCommand(CommandDecl, List)}; a given set runs the node for its bots without asking who. */
    private void runCommandIn(final @Nullable BotCommands fixed, final CommandDecl c, final List<String> path) {
        final BridgeCore core = ui.core();
        final TreeNode node = TreeNode.of(c.data()).at(path);
        if (core == null || node == null) {
            return;
        }
        final Map<String, BotCommands> sets = fixed != null ? Map.of(fixed.name(), fixed)
                : c.data().orders() ? orderTargets(core, c) : Map.of(c.ownerName(), ownerOf(core, c));
        if (sets.isEmpty()) {
            ui.toastError(commandLabel(c, path), I18n.get("mineflayer_bridge.command.no_crew"));
            return;
        }
        final List<String> names = List.copyOf(sets.keySet());
        final boolean ask = c.data().orders() && names.size() > 1;
        final List<CommandArg> args = node.args();
        if (args.isEmpty() && !ask) {
            invokeIn(sets.get(names.getFirst()), c, path, Map.of());
            return;
        }
        final List<FieldSpec> fields = new ArrayList<>(args.size() + 1);
        if (ask) {
            fields.add(new FieldSpec(FOR_FIELD, I18n.get("screen.mineflayer_bridge.form.order_for"), FieldSpec.Kind.SELECT, names.getFirst(),
                    null, null, null, names, true));
        }
        for (final CommandArg arg : args) {
            fields.add(FieldSpec.of(arg));
        }
        final FormScreen screen = new FormScreen(Component.literal(commandLabel(c, path)), Component.literal(c.ownerName()), fields,
                Component.translatable("screen.mineflayer_bridge.form.run"),
                raw -> {
                    final Object chosen = raw.get(FOR_FIELD);
                    final BotCommands set = sets.getOrDefault(chosen == null ? names.getFirst() : chosen.toString(), sets.get(names.getFirst()));
                    final Map<String, Object> values = new java.util.HashMap<>(raw);
                    values.remove(FOR_FIELD);
                    final ParseResult parsed = CommandEngine.parseFields(set, c, path, values, core.commands().context());
                    return switch (parsed) {
                        case ParseResult.Invocation inv -> {
                            invokeIn(set, c, path, inv.args());
                            yield null;
                        }
                        case ParseResult.Failure f -> f.message();
                        case ParseResult.Help h -> I18n.get("mineflayer_bridge.command.choose_subcommand");
                    };
                },
                () -> { },
                picker,
                () -> Crosshair.argContext().crosshairBlock());
        final BotCommands first = sets.get(names.getFirst());
        screen.setSuggester((fieldId, text) -> {
            for (final CommandArg arg : args) {
                if (arg.name().equals(fieldId)) {
                    return core.commands().engine().completeArg(first, c, path, arg, text, 0, Map.of(), core.commands().context());
                }
            }
            return CompletableFuture.completedFuture(List.of());
        });
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Form field id of an order's "For" choice; argument names never start with a space. */
    private static final String FOR_FIELD = " for";

    /**
     * Who an order can be given to from a screen: {@code all}, then groups, then single bots, each
     * with the command set that carries it, by address name.
     */
    private static Map<String, BotCommands> orderTargets(final BridgeCore core, final CommandDecl c) {
        final Map<String, BotCommands> out = new java.util.LinkedHashMap<>();
        for (final Addressee.Kind kind : List.of(Addressee.Kind.ALL, Addressee.Kind.GROUP, Addressee.Kind.BOT)) {
            for (final Addressee a : core.commands().addressees()) {
                if (a.kind() != kind) {
                    continue;
                }
                for (final BotCommands set : a.commands()) {
                    if (!set.bots().isEmpty() && set.commands().contains(c)) {
                        out.putIfAbsent(a.name(), set);
                    }
                }
            }
        }
        return out;
    }

    /**
     * A command the player ran from a screen, kept so the palette can run it again with the same values.
     *
     * @param set the command set it ran in
     * @param key the command's object key
     * @param path the subcommand path
     * @param values the parsed values
     */
    private record Recent(BotCommands set, String key, List<String> path, Map<String, Object> values) {
    }

    /** Most recent commands the palette lists. */
    private static final int MAX_RECENT = 8;

    private final java.util.ArrayDeque<Recent> recents = new java.util.ArrayDeque<>();

    /** Palette entries that run recent commands again, newest first; {@code bot} limits them to that bot's. */
    private List<PaletteScreen.Entry> recentEntries(final @Nullable String bot) {
        final List<PaletteScreen.Entry> out = new ArrayList<>();
        final String header = I18n.get("screen.mineflayer_bridge.palette.recent");
        for (final Recent r : recents) {
            final CommandDecl c = commandByKey(r.key());
            if (c == null || bot != null && !r.set().bots().contains(bot) && !c.ownerName().equals(bot)) {
                continue;
            }
            final String who = r.set().bots().isEmpty() ? c.ownerName() : String.join(", ", r.set().bots());
            final StringBuilder label = new StringBuilder(commandLabel(c, r.path()));
            for (final Object v : r.values().values()) {
                label.append(' ').append(v);
            }
            out.add(new PaletteScreen.Entry(label.toString(), I18n.get("screen.mineflayer_bridge.palette.recent.for", who), header,
                    () -> invokeIn(r.set(), c, r.path(), r.values())));
        }
        return out;
    }

    private @Nullable CommandDecl commandByKey(final String key) {
        for (final CommandDecl c : ui.declarations().commands()) {
            if (c.key().equals(key)) {
                return c;
            }
        }
        return null;
    }

    /** Sends a command node in a command set, as an order for the set's bots when it carries them. */
    private void invokeIn(final BotCommands set, final CommandDecl c, final List<String> path, final Map<String, Object> values) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final Recent recent = new Recent(set, c.key(), List.copyOf(path), Map.copyOf(values));
        recents.removeIf(r -> r.key().equals(recent.key()) && r.path().equals(recent.path()) && r.values().equals(recent.values())
                && r.set().bots().equals(recent.set().bots()));
        recents.addFirst(recent);
        while (recents.size() > MAX_RECENT) {
            recents.removeLast();
        }
        final String who = set.bots().isEmpty() ? c.ownerName() : String.join(", ", set.bots());
        ui.track(commandLabel(c, path), who, set.bots().isEmpty() ? core.invokeCommand(c.key(), path, values, "screen")
                : core.invokeCommand(c.key(), path, values, "screen", set.bots()));
    }

    private static BotCommands ownerOf(final BridgeCore core, final CommandDecl c) {
        for (final BotCommands b : core.commands().bots()) {
            if (c.key().startsWith(b.owner() + "/")) {
                return b;
            }
        }
        return new BotCommands(c.ownerName(), c.key(), null, List.of(c));
    }
}
