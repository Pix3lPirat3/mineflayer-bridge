package io.github.pix3lpirat3.mfbridge.common.spatial;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.RouteData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedUpdate;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.resources.language.I18n;
import org.jspecify.annotations.Nullable;

/**
 * Writes spatial edits through the owner's single-writer path, each as one undo
 * step: shared objects with {@code shared.op} and {@code expectRev}, the player's own directly, and
 * bot-owned objects as an {@code obj.edit.propose} that stays pending until the bot answers.
 * Client thread only.
 */
public final class SpatialWrites {

    /** Extension of shared zones and points. */
    public static final String ZONES = "bridge:zones";

    /** Extension of shared routes. */
    public static final String PATHS = "bridge:path-tool";

    private static final String TITLE = "mineflayer_bridge.spatial.title";

    private final UiContext ui;
    private final Map<String, SpatialIndex.Item> pending = new HashMap<>();
    private final Set<String> waiting = new HashSet<>();

    /**
     * Creates the writer.
     *
     * @param ui the UI services
     */
    public SpatialWrites(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Returns the bot edits waiting for an answer, by key, as the proposed objects.
     *
     * @return a live view
     */
    public Map<String, SpatialIndex.Item> pending() {
        return pending;
    }

    /** Returns the kind of a spatial data value. */
    static KindType<?> kindOf(final Object data) {
        return switch (data) {
            case ZoneData z -> CoreKinds.ZONE;
            case PointData p -> CoreKinds.POINT;
            case RouteData r -> CoreKinds.ROUTE;
            default -> throw new IllegalArgumentException("not spatial data: " + data.getClass().getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(final Object data) {
        return ((KindType<Object>) kindOf(data)).encode(data);
    }

    /**
     * Creates a shared object in the player's world, or a global one on the title screen.
     *
     * @param label the undo label
     * @param extension the key's extension segment
     * @param data zone, point or route data
     * @return the new key, or null when the write failed (a toast says why)
     */
    public @Nullable String create(final String label, final String extension, final Object data) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        final String key = ObjectKeys.SHARED_PREFIX + extension + "/" + UUID.randomUUID();
        final WorldKey world = core.playerContext();
        try {
            core.history().sharedOp(label, new SharedOpBody.Create(new SharedCreate(new ObjectFull(key, kindOf(data).id(), 0, null,
                    Lifetime.PERSISTENT, world == null ? Scope.GLOBAL : Scope.WORLD, world, null, encode(data)))));
            return key;
        } catch (final BridgeException e) {
            ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.failed", label, BridgeUi.describe(e)));
            return null;
        }
    }

    /**
     * Replaces an object's data as one undo step.
     *
     * @param item the object as last seen; its revision is the expected revision
     * @param label the undo label
     * @param data the new data, of the object's kind
     * @return false when a shared write conflicted, so the caller reloads its copy
     */
    public boolean update(final SpatialIndex.Item item, final String label, final Object data) {
        final byte[] encoded = encode(data);
        final SpatialIndex.Item proposed = SpatialIndex.item(new RenderEntry(0, item.key(), item.owner(), "", "", null, kindOf(data),
                item.rev(), data, null, Scope.GLOBAL, null));
        return write(item.key(), item.owner(), item.rev(), item.name(), label, kindOf(data), encoded,
                PatchApplier.fullPatch(encoded), proposed);
    }

    /**
     * Writes an edit of any object as one undo step, through its owner's path: shared objects with
     * {@code shared.op} and {@code expectRev}, player objects directly, and bot- or controller-owned
     * objects as an {@code obj.edit.propose} that stays pending until the owner answers.
     * The answer shows as a toast, with the owner's reason on a
     * rejection.
     *
     * @param key the object key
     * @param owner the owner part of the key
     * @param rev the revision the edit is based on
     * @param name the object's name for messages
     * @param label the undo label
     * @param kind the object's kind
     * @param encoded the object's new data
     * @param fields the field patch from the old data to {@code encoded}
     * @param preview what pending spatial edits draw meanwhile, or null
     * @return false when a shared write conflicted, so the caller reloads its copy
     */
    public boolean write(final String key, final String owner, final long rev, final String name, final String label,
            final KindType<?> kind, final byte[] encoded, final byte[] fields, final SpatialIndex.@Nullable Item preview) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        if (key.startsWith(ObjectKeys.SHARED_PREFIX)) {
            try {
                core.history().sharedOp(label, new SharedOpBody.Update(new SharedUpdate(key, rev, fields)));
                return true;
            } catch (final BridgeException e) {
                if (e.code().equals("conflict")) {
                    ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.conflict", name));
                } else {
                    ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.failed", label, BridgeUi.describe(e)));
                }
                return false;
            }
        }
        if (key.startsWith(ObjectKeys.PLAYER_PREFIX)) {
            final RenderEntry entry = entry(core, key);
            try {
                core.history().putPlayerObject(label, key, kind, entry == null ? null : entry.layer(),
                        entry == null ? Scope.GLOBAL : entry.scope(), entry == null ? null : entry.world(), encoded);
            } catch (final IllegalArgumentException e) {
                ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.failed", label, e.getMessage()));
            }
            return true;
        }
        if (waiting.contains(key)) {
            ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.waiting_for", name));
            return true;
        }
        waiting.add(key);
        if (preview != null) {
            pending.put(key, preview);
        }
        final String ownerName = ownerName(core, owner);
        core.history().proposeEdit(label, key, rev, fields).whenComplete((r, error) -> {
            pending.remove(key);
            waiting.remove(key);
            if (error != null) {
                ui.toastError(ownerName, I18n.get("mineflayer_bridge.edit.failed", label, BridgeUi.describe(error)));
            } else if (!r.accepted()) {
                final String reason = r.reason();
                ui.toastError(ownerName, reason == null || reason.isEmpty() ? I18n.get("mineflayer_bridge.edit.rejected", label)
                        : I18n.get("mineflayer_bridge.edit.rejected.reason", label, reason));
            } else {
                ui.toastInfo(ownerName, I18n.get("mineflayer_bridge.edit.accepted", label));
            }
        });
        return true;
    }

    /**
     * Returns whether an edit of an object waits for its owner's answer.
     *
     * @param key the object key
     * @return true while an {@code obj.edit.propose} is pending
     */
    public boolean waiting(final String key) {
        return waiting.contains(key);
    }

    /**
     * Deletes a shared or player object as one undo step. Bot-owned objects cannot be deleted by
     * the player.
     *
     * @return whether it was deleted
     */
    public boolean delete(final SpatialIndex.Item item) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        final String label = I18n.get("mineflayer_bridge.edit.delete", item.name());
        if (item.key().startsWith(ObjectKeys.SHARED_PREFIX)) {
            try {
                core.history().sharedOp(label, new SharedOpBody.Remove(new SharedRemove(item.key(), item.rev())));
                return true;
            } catch (final BridgeException e) {
                ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.failed", label, BridgeUi.describe(e)));
                return false;
            }
        }
        if (item.key().startsWith(ObjectKeys.PLAYER_PREFIX)) {
            return core.history().removePlayerObject(label, item.key());
        }
        ui.toastError(I18n.get(TITLE), I18n.get("mineflayer_bridge.edit.not_deletable", item.name(), ownerName(core, item.owner())));
        return false;
    }

    private static @Nullable RenderEntry entry(final BridgeCore core, final String key) {
        return core.renderSnapshot().find(key);
    }

    /** Returns the display name of a bot owner, or the owner itself. */
    static String ownerName(final BridgeCore core, final String owner) {
        final String prefix = "bot:";
        if (owner.startsWith(prefix)) {
            final String identity = owner.substring(prefix.length());
            final var p = core.presence().get(identity);
            return p == null ? identity : p.displayName();
        }
        return owner;
    }
}
