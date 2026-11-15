package io.github.pix3lpirat3.mfbridge.common.ui;

import io.github.pix3lpirat3.mfbridge.common.display.Displays;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialTools;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.input.ToolController;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TargetMatch;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.RadialMenuScreen;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.decl.ActionDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.ToolDecl;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The radial menus: the context menu of actions for what the crosshair is on, or for something
 * picked off the map, and the tool wheel. Client thread only.
 */
public final class RadialMenus {

    /** Actions live under {@code ~action/<id>}. */
    private static final String ACTION_PREFIX = "~action/";

    private final UiContext ui;
    private final Predicate<String> focusAllows;
    private final Displays displays;
    private final ToolController tools;
    private final SpatialTools spatial;
    private final ActionRunner actions;

    /**
     * Creates the menus.
     *
     * @param ui the core
     * @param focusAllows whether an object key shows with the bot that is focused now
     * @param displays knows the object under the crosshair
     * @param tools tool mode
     * @param spatial the zone tools, which are always on the wheel
     * @param actions runs the chosen action
     */
    public RadialMenus(final UiContext ui, final Predicate<String> focusAllows, final Displays displays, final ToolController tools,
            final SpatialTools spatial, final ActionRunner actions) {
        this.ui = ui;
        this.focusAllows = focusAllows;
        this.displays = displays;
        this.tools = tools;
        this.spatial = spatial;
        this.actions = actions;
    }

    /**
     * Opens the radial menu with every action that matches the target under the crosshair.
     *
     * @param key the key that opened it; letting it go chooses the hovered entry
     */
    public void openContextMenu(final KeyMapping key) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final Crosshair.Hit hit = Crosshair.hit(Crosshair.ACTION_RANGE, e -> true);
        final String targetKey = displays.targetKey();
        final RenderEntry element = targetKey == null ? null : entry(core, targetKey);
        if (element != null) {
            objectActions(core, element, key);
            return;
        }
        // Zone and point actions: the zone around the block looked at (or the player), the point near it.
        final List<RadialMenuScreen.Entry> entries = contextEntries(core, hit, spatialAt(core, hit, SpatialIndex.Kind.ZONE),
                spatialAt(core, hit, SpatialIndex.Kind.POINT), "menu", List.of());
        Minecraft.getInstance().gui.setScreen(new RadialMenuScreen(Component.literal(describe(hit)), I18n.get("mineflayer_bridge.actions.empty.target"),
                entries, key));
    }

    /**
     * Opens the context menu for something picked off the map: an entity, a point
     * or a zone. Its actions get the selected bots. Opens nothing and returns false when no action
     * applies, so the map can do its own thing with the click.
     *
     * @param entity the entity clicked on, or null
     * @param zone the zone clicked in, or null
     * @param point the point clicked on, or null
     * @param title shown in the menu's centre
     * @param bots names of the bots selected on the map
     * @param parent the map, to return to
     * @return whether a menu opened
     */
    public boolean openMapMenu(final net.minecraft.world.entity.@Nullable Entity entity, final SpatialIndex.@Nullable Item zone, final SpatialIndex.@Nullable Item point,
            final String title, final List<String> bots, final Screen parent) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        final Crosshair.Hit hit = entity == null ? null
                : new Crosshair.Hit(net.minecraft.world.phys.HitResult.Type.ENTITY, null, null, null, entity, entity.position());
        final List<RadialMenuScreen.Entry> entries = contextEntries(core, hit, zone, point, "map", bots);
        if (entries.isEmpty()) {
            return false;
        }
        Minecraft.getInstance().gui.setScreen(new RadialMenuScreen(Component.literal(title), "", entries, null, parent));
        return true;
    }

    /** The actions for a hit (blocks, entities, bots, air), then for a zone and a point, in menu order. */
    private List<RadialMenuScreen.Entry> contextEntries(final BridgeCore core, final Crosshair.@Nullable Hit hit, final SpatialIndex.@Nullable Item zone,
            final SpatialIndex.@Nullable Item point, final String source, final List<String> bots) {
        final List<ActionDecl> matching = new ArrayList<>();
        for (final ActionDecl a : core.declarations().actions()) {
            if (hit != null && focusAllows.test(a.key()) && Crosshair.matches(a.data().target(), hit)) {
                matching.add(a);
            }
        }
        matching.sort(Comparator.comparing(ActionDecl::ownerName).thenComparingInt((ActionDecl a) -> a.data().order())
                .thenComparing(a -> a.data().label()));
        final List<RadialMenuScreen.Entry> entries = new ArrayList<>();
        for (final ActionDecl a : matching) {
            final Target target = Crosshair.target(java.util.Objects.requireNonNull(hit), a.data().target().type());
            if (target != null) {
                entries.add(new RadialMenuScreen.Entry(a.data().label(), a.ownerName(), () -> actions.invoke(a, target, source, bots), false));
            }
        }
        for (final ActionDecl a : core.declarations().actions()) {
            final String type = a.data().target().type();
            final SpatialIndex.Item it = type.equals("zone") ? zone : type.equals("point") ? point : null;
            if (it != null && focusAllows.test(a.key())
                    && TargetMatch.matchesFilter(a.data().target().ids(), a.data().target().tags(), it.name(), it.tags()::contains)) {
                final Target target = new Target(type, null, null, null,
                        new Vec3d((it.minX() + it.maxX()) / 2, (it.minY() + it.maxY()) / 2, (it.minZ() + it.maxZ()) / 2), null, it.key());
                entries.add(new RadialMenuScreen.Entry(a.data().label() + ": " + it.name(), a.ownerName(),
                        () -> actions.invoke(a, target, source, bots), false));
            }
        }
        return entries;
    }

    /**
     * The radial menu on an object lists its {@code traits.actions}, plus the
     * {@code element} actions whose tags match its {@code interactive.tags}.
     * Each runs on an {@code element} target carrying the object key.
     */
    private void objectActions(final BridgeCore core, final RenderEntry e, final KeyMapping key) {
        final String scope = e.owner() + "/" + e.extension() + "/";
        final List<String> bound = Traits.actions(e.traits());
        final var spec = e.interactive();
        final List<String> tags = spec == null || spec.tags() == null ? List.of() : spec.tags();
        final Target target = new Target("element", null, null, null, vec(Crosshair.eye().add(Crosshair.look().scale(2))), null, e.key());
        final List<RadialMenuScreen.Entry> entries = new ArrayList<>();
        for (final ActionDecl a : core.declarations().actions()) {
            final String local = a.key().startsWith(scope) ? a.key().substring(scope.length()) : null;
            final boolean isBound = bound.contains(a.key()) || local != null
                    && (bound.contains(local) || local.startsWith(ACTION_PREFIX) && bound.contains(local.substring(ACTION_PREFIX.length())));
            final boolean byTag = a.data().target().type().equals("element")
                    && TargetMatch.matches(a.data().target(), "element", null, tags::contains);
            if (isBound || byTag && focusAllows.test(a.key())) {
                entries.add(new RadialMenuScreen.Entry(a.data().label(), a.ownerName(), () -> actions.invoke(a, target, "menu"), false));
            }
        }
        Minecraft.getInstance().gui.setScreen(new RadialMenuScreen(Component.literal(e.localId()), I18n.get("mineflayer_bridge.actions.empty.object"),
                entries, key));
    }

    /**
     * The shared zone around the block the crosshair is on (else around the player), or the shared
     * point within 2.5 blocks of it.
     */
    private static SpatialIndex.@Nullable Item spatialAt(final BridgeCore core, final Crosshair.Hit hit, final SpatialIndex.Kind kind) {
        final net.minecraft.world.phys.Vec3 at = hit.block() != null ? net.minecraft.world.phys.Vec3.atCenterOf(hit.block()) : hit.location();
        if (kind == SpatialIndex.Kind.POINT) {
            return core.spatial().nearest(at.x, at.y, at.z, SpatialIndex.Kind.POINT, 2.5);
        }
        for (final SpatialIndex.Item it : core.spatial().containing(at.x, at.y, at.z)) {
            if (it.kind() == SpatialIndex.Kind.ZONE) {
                return it;
            }
        }
        final LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            for (final SpatialIndex.Item it : core.spatial().containing(p.getX(), p.getY(), p.getZ())) {
                if (it.kind() == SpatialIndex.Kind.ZONE) {
                    return it;
                }
            }
        }
        return null;
    }

    private static Vec3d vec(final net.minecraft.world.phys.Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    private static @Nullable RenderEntry entry(final BridgeCore core, final String key) {
        return core.renderSnapshot().find(key);
    }

    private static String describe(final Crosshair.Hit hit) {
        if (hit.type() == HitResult.Type.ENTITY && hit.entity() != null) {
            return hit.entity().getName().getString();
        }
        if (hit.type() == HitResult.Type.BLOCK && hit.state() != null) {
            return hit.state().getBlock().getName().getString();
        }
        return I18n.get("block.minecraft.air");
    }

    /**
     * Opens the tool wheel with the tools of attached bots.
     *
     * @param key the key that opened it; letting it go chooses the hovered entry
     */
    public void openToolWheel(final KeyMapping key) {
        final DeclarationSnapshot decl = ui.declarations();
        final ToolDecl active = tools.active();
        final List<ToolDecl> list = new ArrayList<>();
        for (final ToolDecl t : decl.tools()) {
            if (focusAllows.test(t.key())) {
                list.add(t);
            }
        }
        list.sort(Comparator.comparing(ToolDecl::ownerName).thenComparing(t -> t.data().label()));
        final List<RadialMenuScreen.Entry> entries = new ArrayList<>();
        // The spatial tools are always on the wheel.
        for (final SpatialTools.Tool t : SpatialTools.Tool.values()) {
            entries.add(new RadialMenuScreen.Entry(t.label(), SpatialTools.OWNER, () -> {
                tools.select(null);
                spatial.select(t);
            }, spatial.active() == t));
        }
        for (final ToolDecl t : list) {
            entries.add(new RadialMenuScreen.Entry(t.data().label(), t.ownerName(), () -> {
                spatial.select(null);
                tools.select(t.key());
            }, active != null && active.key().equals(t.key())));
        }
        entries.add(new RadialMenuScreen.Entry(I18n.get("mineflayer_bridge.tools.off"), "", () -> {
            tools.select(null);
            spatial.select(null);
        }, active == null && spatial.active() == null));
        Minecraft.getInstance().gui.setScreen(new RadialMenuScreen(Component.translatable("mineflayer_bridge.tools"), I18n.get("mineflayer_bridge.tools.empty"), entries,
                key));
    }
}
