package io.github.pix3lpirat3.mfbridge.common.ext;

import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.client.Aim;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ObjectView;
import io.github.pix3lpirat3.mfbridge.api.client.Place;
import io.github.pix3lpirat3.mfbridge.api.client.SessionView;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ClientBridge} that extensions get: each call is answered from the running core and the
 * UI, and nothing more of either is handed out. While the endpoint is off the lists are empty and
 * the calls that act do nothing. Client thread only.
 */
public final class BridgeFacade implements ClientBridge {
    private static final String DEFAULT_PREFIX = "@";

    private final BridgeUi ui;

    /**
     * Creates the facade.
     *
     * @param ui the bridge's UI, which knows the running core
     */
    public BridgeFacade(final BridgeUi ui) {
        this.ui = ui;
    }

    @Override
    public boolean running() {
        return ui.core() != null;
    }

    @Override
    public List<SessionView> sessions() {
        final BridgeCore core = ui.core();
        if (core == null) {
            return List.of();
        }
        final List<SessionView> out = new ArrayList<>();
        for (final SessionInfo s : core.inspector().sessions()) {
            final boolean controller = !s.kind().equals("bot");
            out.add(new SessionView(s.owner(), s.displayName(), controller, s.connected(), s.worldMatched(),
                    controller ? null : core.botPosition(s.owner())));
        }
        return out;
    }

    @Override
    public String botName(final String botOwner) {
        final BridgeCore core = ui.core();
        return core == null ? botOwner : BridgeUi.botName(core, botOwner);
    }

    @Override
    public @Nullable MessageContext session(final String owner) {
        final BridgeCore core = ui.core();
        return core == null ? null : core.session(owner);
    }

    @Override
    public void playerAsked(final String owner) {
        final BridgeCore core = ui.core();
        if (core != null) {
            core.playerAsked(owner);
        }
    }

    @Override
    public @Nullable WorldKey playerWorld() {
        final BridgeCore core = ui.core();
        return core == null ? null : core.playerContext();
    }

    @Override
    public <T> void putPlayerObject(final String key, final KindType<T> kind, final @Nullable String layer, final T data) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final WorldKey world = core.playerContext();
        core.putPlayerObject(key, kind, layer, world == null ? Scope.GLOBAL : Scope.WORLD, world, kind.encode(data));
    }

    @Override
    public boolean removePlayerObject(final String key) {
        final BridgeCore core = ui.core();
        return core != null && core.removePlayerObject(key);
    }

    @Override
    public <T> List<ObjectView<T>> objects(final Class<T> dataType) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return List.of();
        }
        final List<ObjectView<T>> out = new ArrayList<>();
        for (final RenderEntry e : core.renderSnapshot().entries()) {
            if (dataType.isInstance(e.data())) {
                out.add(new ObjectView<>(e.key(), e.owner(), e.localId(), dataType.cast(e.data())));
            }
        }
        return out;
    }

    @Override
    public List<Place> places() {
        final BridgeCore core = ui.core();
        if (core == null) {
            return List.of();
        }
        final List<SpatialIndex.Item> items = core.spatial().all();
        final List<Place> out = new ArrayList<>(items.size());
        for (final SpatialIndex.Item it : items) {
            final Place.Kind kind = switch (it.kind()) {
                case ZONE -> Place.Kind.ZONE;
                case POINT -> Place.Kind.POINT;
                case ROUTE -> Place.Kind.ROUTE;
            };
            out.add(new Place(it.key(), it.owner(), kind, it.name(), it.minX(), it.minY(), it.minZ(), it.maxX(), it.maxY(),
                    it.maxZ(), it.polyline()));
        }
        return out;
    }

    @Override
    public boolean openActionMenu(final @Nullable Entity entity, final @Nullable String zoneKey, final @Nullable String pointKey,
            final String title, final List<String> bots, final Screen parent) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        final SpatialIndex index = core.spatial();
        return ui.openMapMenu(entity, zoneKey == null ? null : index.get(zoneKey), pointKey == null ? null : index.get(pointKey),
                title, bots, parent);
    }

    @Override
    public @Nullable Aim aim(final double range) {
        final Crosshair.Hit hit = Crosshair.hit(range, e -> true);
        final Entity entity = hit.entity();
        final Target target = entity != null ? Crosshair.target(hit, "entity") : Crosshair.target(hit, "block");
        if (target == null) {
            return null;
        }
        return new Aim(target, entity, entity == null ? null : Crosshair.entityTypeId(entity),
                hit.state() == null ? null : Crosshair.blockId(hit.state()));
    }

    @Override
    public String chatPrefix() {
        final BridgeCore core = ui.core();
        return core == null ? DEFAULT_PREFIX : core.chat().prefix();
    }

    @Override
    public boolean runChatLine(final String line) {
        final ChatBridgeClient chat = ChatBridgeClient.current();
        if (chat == null || ui.core() == null) {
            // Nothing can take the line; it is dropped, never passed on to the server.
            return true;
        }
        return chat.filterOutgoing(line) == null;
    }

    @Override
    public long bufferMs() {
        final BridgeCore core = ui.core();
        return core == null ? 0L : core.config().extensionBufferMs();
    }

    @Override
    public boolean inPlay() {
        return UiContext.inPlay();
    }

    @Override
    public void toast(final String title, final String message) {
        ui.toastInfo(title, message);
    }

    @Override
    public void toastError(final String title, final String message) {
        ui.toastError(title, message);
    }

    @Override
    public void playSound(final String soundId) {
        ui.sounds().ui(soundId);
    }
}
