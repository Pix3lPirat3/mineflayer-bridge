package io.github.pix3lpirat3.mfbridge.common.ext.map;

import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.Place;
import io.github.pix3lpirat3.mfbridge.common.ext.map.wire.MapOrderType;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.common.version.Keys;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The Bridge map: the loaded terrain from above with the player, bots, zones, points and
 * routes. RTS-style control: click or drag a box to select bots, right-click to send them there or
 * to open the actions for the mob, player or point under the cursor
 * (Shift queues), S stops them; the wheel zooms, middle-drag or WASD pans, C centres on the player.
 * Client thread only.
 */
final class MapScreen extends ChildScreen implements io.github.pix3lpirat3.mfbridge.common.ui.screen.FormHost {

    private static final int BOT_RADIUS = 3;
    private static final int PICK_RADIUS = 6;
    private static final int DRAG_THRESHOLD = 4;
    private static final int REFRESH_TICKS = 40;
    private static final float[] ZOOMS = {0.25f, 0.5f, 1f, 2f, 4f};
    private static final int PLAYER_ARGB = 0xFFFFFFFF;
    private static final int BOT_ARGB = 0xFF4FC3F7;
    private static final int SELECTED_ARGB = 0xFFFFD54F;
    private static final int ZONE_ARGB = 0xFF81C784;
    private static final int POINT_ARGB = 0xFFFF8A65;
    private static final int ROUTE_ARGB = 0xFFBA68C8;
    private static final int BOX_ARGB = 0x40FFD54F;
    /** Most entities drawn in one frame; the client only knows those within its view distance anyway. */
    private static final int MAX_ENTITIES = 600;
    /** Most item icons (mob eggs, ground items) drawn in one frame; the rest are dots. */
    private static final int MAX_ICONS = 160;
    /** Side of a face or icon on the map, in screen pixels. */
    private static final int ICON = 8;
    /** A mob's spawn egg as its map icon, by type; mobs without one get a dot. */
    private static final Map<EntityType<?>, ItemStack> EGGS = new HashMap<>();
    private static final int LEGEND_ROW = 11;
    private static final int LEGEND_WIDTH = 92;
    /** What the legend has switched on; kept while the game runs. */
    private static final Set<MapLayer> SHOWN = EnumSet.allOf(MapLayer.class);

    private final MapExtension map;
    private final MapTerrain terrain;
    private final Set<String> selected;
    private double centerX;
    private double centerZ;
    private int zoom = 2;
    private int ticks;
    private double pressX = Double.NaN;
    private double pressY;
    private double dragX;
    private double dragY;
    private boolean boxing;
    private boolean placed;

    MapScreen(final MapExtension map, final MapTerrain terrain, final @Nullable Screen parent) {
        super(Component.translatable("mineflayer_bridge_map.title"), parent);
        this.map = map;
        this.terrain = terrain;
        this.selected = map.selection();
    }

    @Override
    protected void init() {
        final LocalPlayer p = minecraft.player;
        // Centre on the player when first opened, not when coming back from a menu or form.
        if (p != null && !placed) {
            placed = true;
            centerX = p.getX();
            centerZ = p.getZ();
        }
        repaint();
    }

    private float scale() {
        return ZOOMS[zoom];
    }

    /** Blocks per terrain pixel for the zoom: one pixel per screen pixel or finer. */
    private int blocksPerPixel() {
        return Math.max(1, Math.round(1f / scale()));
    }

    private void repaint() {
        terrain.repaint((int) Math.floor(centerX), (int) Math.floor(centerZ), blocksPerPixel());
    }

    private double sx(final double wx) {
        return width / 2.0 + (wx - centerX) * scale();
    }

    private double sy(final double wz) {
        return height / 2.0 + (wz - centerZ) * scale();
    }

    private double wx(final double x) {
        return centerX + (x - width / 2.0) / scale();
    }

    private double wz(final double y) {
        return centerZ + (y - height / 2.0) / scale();
    }

    @Override
    public void tick() {
        if (minecraft.level == null) {
            onClose();
            return;
        }
        final int span = MapTerrain.SIZE * terrain.blocksPerPixel();
        final double offX = centerX - (terrain.originX() + span / 2.0);
        final double offZ = centerZ - (terrain.originZ() + span / 2.0);
        if (++ticks >= REFRESH_TICKS || Math.abs(offX) > span / 4.0 || Math.abs(offZ) > span / 4.0
                || terrain.blocksPerPixel() != blocksPerPixel()) {
            if (!terrain.painting()) {
                ticks = 0;
                repaint();
            }
        }
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        final double ax = wx(x);
        final double az = wz(y);
        zoom = Math.clamp(zoom + (scrollY > 0 ? 1 : -1), 0, ZOOMS.length - 1);
        // Keep the point under the cursor still.
        centerX = ax - (x - width / 2.0) / scale();
        centerZ = az - (y - height / 2.0) / scale();
        return true;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == Keys.MOUSE_BUTTON_LEFT && legendClick(event.x(), event.y())) {
            return true;
        }
        // Button numbers differ between game versions, so no switch on them.
        if (event.button() == Keys.MOUSE_BUTTON_LEFT) {
            pressX = event.x();
            pressY = event.y();
            dragX = pressX;
            dragY = pressY;
            boxing = false;
        } else if (event.button() == Keys.MOUSE_BUTTON_RIGHT
                && (event.hasShiftDown() || !contextMenu(event.x(), event.y()))) {
            order(MapOrderType.MOVE, event.x(), event.y(), event.hasShiftDown());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (event.button() == Keys.MOUSE_BUTTON_MIDDLE) {
            centerX -= dx / scale();
            centerZ -= dy / scale();
            return true;
        }
        if (event.button() == Keys.MOUSE_BUTTON_LEFT && !Double.isNaN(pressX)) {
            dragX = event.x();
            dragY = event.y();
            boxing |= Math.abs(dragX - pressX) > DRAG_THRESHOLD || Math.abs(dragY - pressY) > DRAG_THRESHOLD;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (event.button() == Keys.MOUSE_BUTTON_LEFT && !Double.isNaN(pressX)) {
            final boolean add = event.hasShiftDown();
            if (!add) {
                selected.clear();
            }
            if (boxing) {
                final double x0 = Math.min(pressX, dragX);
                final double x1 = Math.max(pressX, dragX);
                final double y0 = Math.min(pressY, dragY);
                final double y1 = Math.max(pressY, dragY);
                for (final MapExtension.Bot b : map.bots()) {
                    final double x = sx(b.pos().x());
                    final double y = sy(b.pos().z());
                    if (x >= x0 && x <= x1 && y >= y0 && y <= y1) {
                        selected.add(b.owner());
                    }
                }
            } else {
                final MapExtension.Bot hit = botAt(event.x(), event.y());
                if (hit != null && !selected.remove(hit.owner())) {
                    selected.add(hit.owner());
                }
            }
            pressX = Double.NaN;
            boxing = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private MapExtension.@Nullable Bot botAt(final double x, final double y) {
        MapExtension.Bot best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (final MapExtension.Bot b : map.bots()) {
            final double dx = sx(b.pos().x()) - x;
            final double dy = sy(b.pos().z()) - y;
            final double d = dx * dx + dy * dy;
            if (d <= bestD) {
                best = b;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Right-click on a mob, player, bot or point (or, with no bots selected, inside a zone) opens
     * the actions bots offer for it, given to the selected bots. False when there are none.
     */
    private boolean contextMenu(final double x, final double y) {
        final ClientBridge bridge = map.bridge();
        if (bridge == null) {
            return false;
        }
        final Entity entity = entityAt(x, y);
        final Place point = SHOWN.contains(MapLayer.POINTS) ? pointAt(x, y) : null;
        // Zone actions only on open ground: on a mob, its own actions are what the player wants.
        final Place zone = entity == null && point == null && selected.isEmpty() && SHOWN.contains(MapLayer.ZONES)
                ? zoneAt(wx(x), wz(y)) : null;
        if (entity == null && point == null && zone == null) {
            return false;
        }
        final List<String> bots = new ArrayList<>();
        for (final MapExtension.Bot b : map.bots()) {
            if (selected.contains(b.owner())) {
                bots.add(b.name());
            }
        }
        final String title = entity != null ? entity.getName().getString() : point != null ? point.name() : zone.name();
        return bridge.openActionMenu(entity, zone == null ? null : zone.key(), point == null ? null : point.key(), title, bots, this);
    }

    /** The shown entity nearest the cursor, within a few pixels; bots count as players. */
    private @Nullable Entity entityAt(final double x, final double y) {
        if (minecraft.level == null) {
            return null;
        }
        Entity best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (final Entity e : minecraft.level.entitiesForRendering()) {
            final MapLayer layer = layerOf(e);
            if (e == minecraft.player || layer == null || !(SHOWN.contains(layer) || layer == MapLayer.PLAYERS && SHOWN.contains(MapLayer.BOTS))) {
                continue;
            }
            final double dx = sx(e.getX()) - x;
            final double dy = sy(e.getZ()) - y;
            final double d = dx * dx + dy * dy;
            if (d <= bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    private @Nullable Place pointAt(final double x, final double y) {
        Place best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (final Place it : map.places()) {
            if (it.kind() != Place.Kind.POINT) {
                continue;
            }
            final double dx = sx((it.minX() + it.maxX()) / 2) - x;
            final double dy = sy((it.minZ() + it.maxZ()) / 2) - y;
            final double d = dx * dx + dy * dy;
            if (d <= bestD) {
                best = it;
                bestD = d;
            }
        }
        return best;
    }

    /** The smallest zone whose area covers the column at world x, z. */
    private @Nullable Place zoneAt(final double x, final double z) {
        Place best = null;
        for (final Place it : map.places()) {
            if (it.kind() == Place.Kind.ZONE && x >= it.minX() && x <= it.maxX() && z >= it.minZ() && z <= it.maxZ()
                    && (best == null || (it.maxX() - it.minX()) * (it.maxZ() - it.minZ()) < (best.maxX() - best.minX()) * (best.maxZ() - best.minZ()))) {
                best = it;
            }
        }
        return best;
    }

    private void order(final MapOrderType type, final double x, final double y, final boolean queue) {
        if (selected.isEmpty()) {
            map.toast(I18n.get("mineflayer_bridge_map.select_first"));
            return;
        }
        final Vec3d at = type == MapOrderType.MOVE ? map.surface(wx(x), wz(y)) : null;
        map.order(List.copyOf(selected), type, at, queue);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final double step = 16 / scale();
        // Key numbers differ between game versions, so no switch on them.
        final int key = event.key();
        if (key == Keys.KEY_W) {
            centerZ -= step;
        } else if (key == Keys.KEY_S) {
            if (!selected.isEmpty() && !event.hasShiftDown()) {
                order(MapOrderType.STOP, 0, 0, false);
            } else {
                centerZ += step;
            }
        } else if (key == Keys.KEY_A) {
            centerX -= step;
        } else if (key == Keys.KEY_D) {
            centerX += step;
        } else if (key == Keys.KEY_C) {
            final LocalPlayer p = minecraft.player;
            if (p != null) {
                centerX = p.getX();
                centerZ = p.getZ();
            }
        } else {
            return super.keyPressed(event);
        }
        return true;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        g.fill(0, 0, width, height, 0xFF0E0E0E);
        if (minecraft.level != null) {
            terrain.step(minecraft.level);
        }
        final int span = MapTerrain.SIZE * terrain.blocksPerPixel();
        g.blit(terrain.id(), (int) sx(terrain.originX()), (int) sy(terrain.originZ()), (int) sx(terrain.originX() + span),
                (int) sy(terrain.originZ() + span), 0f, 1f, 0f, 1f);
        for (final Place it : map.places()) {
            switch (it.kind()) {
                case ZONE -> {
                    if (SHOWN.contains(MapLayer.ZONES)) {
                        outline(g, (int) sx(it.minX()), (int) sy(it.minZ()), (int) sx(it.maxX()), (int) sy(it.maxZ()), ZONE_ARGB);
                    }
                }
                case POINT -> {
                    if (SHOWN.contains(MapLayer.POINTS)) {
                        dot(g, sx((it.minX() + it.maxX()) / 2), sy((it.minZ() + it.maxZ()) / 2), 2, POINT_ARGB);
                    }
                }
                case ROUTE -> {
                    if (SHOWN.contains(MapLayer.ROUTES)) {
                        route(g, it.polyline());
                    }
                }
            }
        }
        final LocalPlayer p = minecraft.player;
        // Built once per frame: it walks the sessions and the players in view.
        final List<MapExtension.Bot> bots = map.bots();
        entities(g, p, bots);
        if (p != null) {
            face(g, p.getSkin(), sx(p.getX()), sy(p.getZ()), PLAYER_ARGB);
            g.text(font, p.getName().getString(), (int) sx(p.getX()) + 6, (int) sy(p.getZ()) - 4, PLAYER_ARGB);
        }
        final ClientPacketListener connection = minecraft.getConnection();
        for (final MapExtension.Bot b : SHOWN.contains(MapLayer.BOTS) ? bots : List.<MapExtension.Bot>of()) {
            final double x = sx(b.pos().x());
            final double y = sy(b.pos().z());
            final boolean on = selected.contains(b.owner());
            if (on) {
                g.fill((int) x - ICON / 2 - 2, (int) y - ICON / 2 - 2, (int) x + ICON / 2 + 2, (int) y + ICON / 2 + 2, SELECTED_ARGB);
            }
            // A bot is a player on the server: its face comes from the tab list, like anyone's.
            final PlayerInfo info = connection == null ? null : connection.getPlayerInfoIgnoreCase(b.name());
            if (info != null) {
                face(g, info.getSkin(), x, y, BOT_ARGB);
            } else {
                dot(g, x, y, BOT_RADIUS, BOT_ARGB);
            }
            g.text(font, b.name(), (int) x + 6, (int) y - 4, on ? SELECTED_ARGB : BOT_ARGB);
        }
        if (boxing) {
            final int x0 = (int) Math.min(pressX, dragX);
            final int y0 = (int) Math.min(pressY, dragY);
            final int x1 = (int) Math.max(pressX, dragX);
            final int y1 = (int) Math.max(pressY, dragY);
            g.fill(x0, y0, x1, y1, BOX_ARGB);
            outline(g, x0, y0, x1, y1, SELECTED_ARGB);
        }
        g.text(font, title.getString() + "   " + Math.round(wx(mouseX)) + ", " + Math.round(wz(mouseY)), 6, 6, UiColors.HEADER);
        final String help = selected.isEmpty()
                ? I18n.get("mineflayer_bridge_map.help.none")
                : I18n.get("mineflayer_bridge_map.help.selected", selected.size());
        g.text(font, help, 6, height - 14, UiColors.LABEL);
        legend(g);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    /** The layer an entity belongs to, or null for those the map leaves out (the player, armour stands). */
    private static @Nullable MapLayer layerOf(final Entity e) {
        if (e instanceof ItemEntity) {
            return MapLayer.ITEMS;
        }
        if (e instanceof Player) {
            return MapLayer.PLAYERS;
        }
        if (e instanceof Enemy) {
            return MapLayer.HOSTILE;
        }
        if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
            return MapLayer.ANIMALS;
        }
        return null;
    }

    /** Players, mobs and items the client knows, on screen only; bots are drawn as bots. */
    private void entities(final GuiGraphicsExtractor g, final @Nullable LocalPlayer self, final List<MapExtension.Bot> known) {
        if (minecraft.level == null || !(SHOWN.contains(MapLayer.PLAYERS) || SHOWN.contains(MapLayer.HOSTILE)
                || SHOWN.contains(MapLayer.ANIMALS) || SHOWN.contains(MapLayer.ITEMS))) {
            return;
        }
        final Set<String> bots = new HashSet<>();
        for (final MapExtension.Bot b : known) {
            bots.add(b.name().toLowerCase(Locale.ROOT));
        }
        int drawn = 0;
        int icons = 0;
        final boolean close = scale() >= 1f;
        for (final Entity e : minecraft.level.entitiesForRendering()) {
            if (e == self) {
                continue;
            }
            final MapLayer layer = layerOf(e);
            if (layer == null || !SHOWN.contains(layer)) {
                continue;
            }
            final double x = sx(e.getX());
            final double y = sy(e.getZ());
            if (x < 0 || y < 0 || x > width || y > height) {
                continue;
            }
            if (layer == MapLayer.PLAYERS) {
                final String name = e.getName().getString();
                if (bots.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (e instanceof AbstractClientPlayer player) {
                    face(g, player.getSkin(), x, y, layer.argb);
                } else {
                    dot(g, x, y, 2, layer.argb);
                }
                g.text(font, name, (int) x + 6, (int) y - 4, layer.argb);
            } else {
                final ItemStack icon = close && icons < MAX_ICONS ? iconOf(e) : ItemStack.EMPTY;
                if (icon.isEmpty()) {
                    dot(g, x, y, layer == MapLayer.ITEMS ? 0 : 1, layer.argb);
                } else {
                    icons++;
                    smallItem(g, icon, x, y);
                }
            }
            if (++drawn >= MAX_ENTITIES) {
                return;
            }
        }
    }

    /** A face from a skin the client already has, with a thin frame in the layer's colour. */
    private static void face(final GuiGraphicsExtractor g, final net.minecraft.world.entity.player.PlayerSkin skin, final double x, final double y,
            final int frame) {
        final int x0 = (int) x - ICON / 2;
        final int y0 = (int) y - ICON / 2;
        g.fill(x0 - 1, y0 - 1, x0 + ICON + 1, y0 + ICON + 1, frame);
        PlayerFaceExtractor.extractRenderState(g, skin, x0, y0, ICON);
    }

    /** The map icon of a mob (its spawn egg) or a ground item (the item); empty for none. */
    private static ItemStack iconOf(final Entity e) {
        if (e instanceof ItemEntity item) {
            return item.getItem();
        }
        return EGGS.computeIfAbsent(e.getType(), t -> SpawnEggItem.byId(t).map(h -> new ItemStack(h.value())).orElse(ItemStack.EMPTY));
    }

    /** An item icon at half size, centred on the point. */
    private static void smallItem(final GuiGraphicsExtractor g, final ItemStack stack, final double x, final double y) {
        g.pose().pushMatrix();
        g.pose().translate((float) x - ICON / 2f, (float) y - ICON / 2f);
        g.pose().scale(0.5f, 0.5f);
        g.fakeItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    private int legendX() {
        return width - LEGEND_WIDTH - 6;
    }

    private static final int LEGEND_TOP = 20;

    /** Every layer with its colour; a click switches it. */
    private void legend(final GuiGraphicsExtractor g) {
        final int x = legendX();
        final MapLayer[] layers = MapLayer.values();
        g.fill(x - 4, LEGEND_TOP - 4, x + LEGEND_WIDTH + 2, LEGEND_TOP + layers.length * LEGEND_ROW + 2, 0xB0101010);
        for (int i = 0; i < layers.length; i++) {
            final MapLayer l = layers[i];
            final boolean on = SHOWN.contains(l);
            final int y = LEGEND_TOP + i * LEGEND_ROW;
            g.fill(x, y + 1, x + 7, y + 8, on ? l.argb : 0xFF404040);
            g.text(font, layerName(l), x + 11, y, on ? UiColors.TEXT : UiColors.LABEL);
        }
    }

    /** Switches the layer whose legend row is at the point; returns whether there was one. */
    private boolean legendClick(final double mx, final double my) {
        final int x = legendX();
        final int row = (int) Math.floor((my - LEGEND_TOP) / LEGEND_ROW);
        if (mx < x - 4 || mx > x + LEGEND_WIDTH + 2 || my < LEGEND_TOP || row < 0 || row >= MapLayer.values().length) {
            return false;
        }
        final MapLayer l = MapLayer.values()[row];
        if (!SHOWN.remove(l)) {
            SHOWN.add(l);
        }
        return true;
    }

    private static String layerName(final MapLayer l) {
        return switch (l) {
            case PLAYERS -> I18n.get("mineflayer_bridge_map.layer.players");
            case BOTS -> I18n.get("mineflayer_bridge_map.layer.bots");
            case HOSTILE -> I18n.get("mineflayer_bridge_map.layer.hostile");
            case ANIMALS -> I18n.get("mineflayer_bridge_map.layer.animals");
            case ITEMS -> I18n.get("mineflayer_bridge_map.layer.items");
            case ZONES -> I18n.get("mineflayer_bridge_map.layer.zones");
            case POINTS -> I18n.get("mineflayer_bridge_map.layer.points");
            case ROUTES -> I18n.get("mineflayer_bridge_map.layer.routes");
        };
    }

    private static void dot(final GuiGraphicsExtractor g, final double x, final double y, final int r, final int argb) {
        g.fill((int) x - r, (int) y - r, (int) x + r + 1, (int) y + r + 1, argb);
    }

    private static void outline(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1, final int argb) {
        g.fill(x0, y0, x1 + 1, y0 + 1, argb);
        g.fill(x0, y1, x1 + 1, y1 + 1, argb);
        g.fill(x0, y0, x0 + 1, y1 + 1, argb);
        g.fill(x1, y0, x1 + 1, y1 + 1, argb);
    }

    private void route(final GuiGraphicsExtractor g, final double @Nullable [] xyz) {
        if (xyz == null) {
            return;
        }
        for (int i = 0; i + 5 < xyz.length; i += 3) {
            final double x0 = sx(xyz[i]);
            final double y0 = sy(xyz[i + 2]);
            final double x1 = sx(xyz[i + 3]);
            final double y1 = sy(xyz[i + 5]);
            final int steps = (int) Math.max(1, Math.hypot(x1 - x0, y1 - y0) / 2);
            for (int s = 0; s <= steps; s++) {
                final double t = (double) s / steps;
                g.fill((int) (x0 + (x1 - x0) * t), (int) (y0 + (y1 - y0) * t), (int) (x0 + (x1 - x0) * t) + 1,
                        (int) (y0 + (y1 - y0) * t) + 1, ROUTE_ARGB);
            }
        }
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        // The map paints its own background.
    }
}
