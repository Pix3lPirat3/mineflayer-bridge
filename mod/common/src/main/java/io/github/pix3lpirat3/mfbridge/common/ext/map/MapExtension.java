package io.github.pix3lpirat3.mfbridge.common.ext.map;

import io.github.pix3lpirat3.mfbridge.api.client.SessionView;
import io.github.pix3lpirat3.mfbridge.api.client.Place;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.api.client.HomeTab;
import io.github.pix3lpirat3.mfbridge.common.ext.map.wire.BridgeMapOrder;
import io.github.pix3lpirat3.mfbridge.common.ext.map.wire.MapOrderType;
import io.github.pix3lpirat3.mfbridge.common.ext.map.wire.MapWire;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:map}, the mod half: a top-down map of the loaded world with the
 * player, bots, zones, points and routes, opened from a key or the Map tab on Bridge home, where the
 * player selects bots and gives them orders sent as {@code bridge:map.order}. The bot's Node half
 * carries them out. Client thread only.
 */
public final class MapExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "map");

    /**
     * A bot on the map.
     *
     * @param owner its owner key
     * @param name its display name
     * @param pos its position: the loaded entity's, else the one it last reported
     */
    record Bot(String owner, String name, Vec3d pos) {}

    private @Nullable ClientBridge bridge;
    private @Nullable MapTerrain terrain;
    /** Bots the player selected, on the map or in their view; kept while the game runs. */
    private final Set<String> selection = new LinkedHashSet<>();

    @Override
    public ExtensionId id() {
        return ID;
    }

    @Override
    public SemVer version() {
        return SemVer.parse("1.0.0");
    }

    @Override
    public void register(final ExtensionRegistrar r) {
        // Sent by the mod only; registered so connections negotiate it.
        r.message(MapWire.MAP_ORDER, (ctx, m) -> { });
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        this.bridge = r.bridge();
        r.keybind("open_map", InputConstants.UNKNOWN.getValue(), this::open);
        r.keybind("select_in_view", InputConstants.UNKNOWN.getValue(), this::selectInView);
        r.homeTab(Component.translatable("mineflayer_bridge_map.tab"), new HomeTab() {
            @Override
            public List<ScreenRow> rows() {
                return List.of(new ScreenRow(List.of(new ScreenItem("button").withId("open").withText(I18n.get("mineflayer_bridge_map.open"))
                                .withIcon("minecraft:filled_map").withWidth(160))),
                        new ScreenRow(List.of(new ScreenItem("text").withColor(UiColors.LABEL)
                                .withText(I18n.get("mineflayer_bridge_map.tab.help")))));
            }

            @Override
            public void use(final String itemId, final @Nullable Object value) {
                if (itemId.equals("open")) {
                    open();
                }
            }
        });
    }

    /** Opens the map over the current screen. */
    void open() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        MapTerrain t = terrain;
        if (t == null) {
            t = new MapTerrain();
            terrain = t;
        }
        Game.setScreen(new MapScreen(this, t, Game.screen()));
    }

    /** Opens bot selection over the player's own view. */
    void selectInView() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && Game.screen() == null) {
            Game.setScreen(new WorldSelectScreen(this));
        }
    }

    /** The selected bots' owner keys, shared by the map and the in-view selection. */
    Set<String> selection() {
        selection.retainAll(bots().stream().map(Bot::owner).toList());
        return selection;
    }

    /** Returns the bridge view, or null before the client parts were registered. */
    @Nullable ClientBridge bridge() {
        return bridge;
    }

    /** Bots in the player's world with a known position. */
    List<Bot> bots() {
        final ClientBridge b = bridge;
        final ClientLevel level = Minecraft.getInstance().level;
        if (b == null || level == null) {
            return List.of();
        }
        final List<Bot> out = new ArrayList<>();
        for (final SessionView s : b.sessions()) {
            if (s.controller() || !s.connected() || !s.inPlayerWorld()) {
                continue;
            }
            Vec3d pos = null;
            for (final Player p : level.players()) {
                if (p.getGameProfile().name().equalsIgnoreCase(s.name())) {
                    pos = new Vec3d(p.getX(), p.getY(), p.getZ());
                    break;
                }
            }
            if (pos == null) {
                pos = s.position();
            }
            if (pos != null) {
                out.add(new Bot(s.owner(), s.name(), pos));
            }
        }
        return out;
    }

    /** Zones, points and routes the player sees. */
    List<Place> places() {
        final ClientBridge b = bridge;
        return b == null ? List.of() : b.places();
    }

    /** The standing position on top of a column, at its centre; the player's y where it is not loaded. */
    Vec3d surface(final double x, final double z) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        double y = mc.player == null ? 64 : Math.floor(mc.player.getY());
        if (level != null && level.hasChunk(bx >> 4, bz >> 4)) {
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        }
        return new Vec3d(bx + 0.5, y, bz + 0.5);
    }

    /** Sends an order to each bot and reports how many took it. */
    void order(final List<String> owners, final MapOrderType type, final @Nullable Vec3d pos, final boolean queue) {
        final ClientBridge b = bridge;
        if (b == null || !b.running()) {
            return;
        }
        int sent = 0;
        final List<String> refused = new ArrayList<>();
        for (final String owner : owners) {
            final MessageContext session = b.session(owner);
            try {
                if (session == null) {
                    throw new IllegalStateException("offline");
                }
                session.send(MapWire.MAP_ORDER, new BridgeMapOrder(type, pos, queue));
                // The player gave the order, so a form the bot answers with may open over the map.
                b.playerAsked(owner);
                sent++;
            } catch (final RuntimeException e) {
                refused.add(name(owner));
            }
        }
        if (sent == 0) {
            toast(I18n.get("mineflayer_bridge_map.order.none"));
            return;
        }
        final String bots = I18n.get(sent == 1 ? "mineflayer_bridge_map.bots.one" : "mineflayer_bridge_map.bots.other", sent);
        final String what = I18n.get(type == MapOrderType.STOP ? "mineflayer_bridge_map.order.stopped"
                : queue ? "mineflayer_bridge_map.order.queued" : "mineflayer_bridge_map.order.sent", bots);
        toast(refused.isEmpty() ? what : I18n.get("mineflayer_bridge_map.order.refused", what, String.join(", ", refused)));
    }

    private String name(final String owner) {
        final ClientBridge view = bridge;
        return view == null ? owner : view.botName(owner);
    }

    void toast(final String text) {
        final ClientBridge b = bridge;
        if (b != null) {
            b.toast(I18n.get("mineflayer_bridge_map.toast"), text);
        }
    }
}
