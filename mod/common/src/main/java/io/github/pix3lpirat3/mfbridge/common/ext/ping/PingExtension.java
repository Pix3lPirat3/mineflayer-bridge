package io.github.pix3lpirat3.mfbridge.common.ext.ping;

import io.github.pix3lpirat3.mfbridge.api.client.SessionView;
import io.github.pix3lpirat3.mfbridge.api.client.Aim;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.common.ext.ping.wire.BridgePingMark;
import io.github.pix3lpirat3.mfbridge.common.ext.ping.wire.PingWire;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:ping}, the mod half: a key marks the block or entity under the crosshair, far
 * beyond reach, and tells every bot and controller that loaded the Node half. The spot shows as a
 * beam for a few seconds. Nothing is sent to the server. Client thread.
 */
public final class PingExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "ping");

    /** How far a ping reaches, in blocks. */
    static final double RANGE = 192d;

    /** How long the beam stays. */
    private static final long SHOW_MS = 8_000L;

    private static final String MARK_KEY = "player/bridge:ping/mark";

    private @Nullable ClientBridge bridge;
    private long shownUntil;

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
        r.message(PingWire.PING_MARK, (ctx, m) -> { });
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        this.bridge = r.bridge();
        r.keybind("ping", InputConstants.UNKNOWN.getValue(), this::ping);
        r.tick(this::tick);
    }

    /** Pings what the player looks at. */
    void ping() {
        final ClientBridge b = bridge;
        if (b == null || !b.running()) {
            return;
        }
        final Aim aim = b.aim(RANGE);
        if (aim == null) {
            b.toast(I18n.get("mineflayer_bridge_ping.toast"), I18n.get("mineflayer_bridge_ping.nothing"));
            return;
        }
        final Entity entity = aim.entity();
        final Target target = aim.target();
        final String name = entity == null ? null : entityName(entity);
        final BridgePingMark mark = new BridgePingMark(target, b.playerWorld(), aim.entityType(), name, aim.block());
        int told = 0;
        for (final SessionView s : b.sessions()) {
            if (!s.connected()) {
                continue;
            }
            final MessageContext session = b.session(s.owner());
            if (session == null) {
                continue;
            }
            try {
                session.send(PingWire.PING_MARK, mark);
                b.playerAsked(s.owner());
                told++;
            } catch (final RuntimeException e) {
                // That session did not load the Node half.
            }
        }
        if (told == 0) {
            b.toast(I18n.get("mineflayer_bridge_ping.toast"), I18n.get("mineflayer_bridge_ping.nobody"));
        }
        final String label = name != null ? I18n.get("mineflayer_bridge_ping.label.entity", name) : I18n.get("mineflayer_bridge_ping.label");
        b.putPlayerObject(MARK_KEY, CoreKinds.WAYPOINT, "ping", new WaypointData(target.pos(), label).withBeam(true));
        shownUntil = System.currentTimeMillis() + SHOW_MS;
        b.playSound("block.note_block.bell");
    }

    private static @Nullable String entityName(final Entity entity) {
        if (entity instanceof Player p) {
            return p.getGameProfile().name();
        }
        return entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName().getString() : null;
    }

    private void tick() {
        if (shownUntil != 0 && System.currentTimeMillis() >= shownUntil) {
            shownUntil = 0;
            final ClientBridge b = bridge;
            if (b != null) {
                b.removePlayerObject(MARK_KEY);
            }
        }
    }
}
