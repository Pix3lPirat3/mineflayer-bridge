package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.query.ClientQueries;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocks;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocksResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntities;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntitiesResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import org.jspecify.annotations.Nullable;

/** Answers a bot's questions about the entities and blocks the client knows. */
@ClientThread
final class ClientQueryService {
    private @Nullable ClientQueries queries;

    private final Permits permits;
    private final PlayerView view;

    ClientQueryService(Permits permits, PlayerView view) {
        this.permits = permits;
        this.view = view;
    }

    /** Sets what answers client queries; null answers them {@code unsupported}. */
    void set(@Nullable ClientQueries queries) {
        this.queries = queries;
    }

    /** Checks shared by both queries; returns the answering side. */
    private ClientQueries queryAccess(Session s) {
        permits.requirePermission(s, Permission.CLIENT_QUERY);
        ClientQueries q = queries;
        if (q == null) {
            throw new BridgeException("unsupported", "this client does not answer queries");
        }
        if (!view.attachedNow(s)) {
            throw new BridgeException("not_attached", "the player has not attached " + s.displayName);
        }
        if (!view.worldMatched(s)) {
            throw new BridgeException("world_mismatch", "the player is not in this bot's world");
        }
        return q;
    }

    /** Answers {@code query.entities}, around the player when the query names no centre. */
    QueryEntitiesResult queryEntities(Session s, QueryEntities q) {
        ClientQueries cq = queryAccess(s);
        float radius = q.radius();
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new BridgeException("invalid_message", "query radius must be a positive number");
        }
        Vec3d center = q.center();
        if (center == null) {
            center = view.position();
            if (center == null) {
                throw new BridgeException("not_attached", "the player's position is not known");
            }
        }
        int limit = (int) Math.min(Integer.toUnsignedLong(q.limit()), ClientQueries.MAX_ENTITIES);
        return cq.entities(new QueryEntities(center, Math.min(radius, ClientQueries.MAX_RADIUS), q.types(), limit));
    }

    /** Answers {@code query.blocks} for a box of at most {@code ClientQueries.MAX_VOLUME} blocks. */
    QueryBlocksResult queryBlocks(Session s, QueryBlocks q) {
        ClientQueries cq = queryAccess(s);
        Vec3i a = q.min();
        Vec3i b = q.max();
        Vec3i min = new Vec3i(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Vec3i max = new Vec3i(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        long volume = (max.x() - (long) min.x() + 1) * (max.y() - (long) min.y() + 1) * (max.z() - (long) min.z() + 1);
        if (volume > ClientQueries.MAX_VOLUME) {
            throw new BridgeException("invalid_message", "query box of " + volume + " blocks is over " + ClientQueries.MAX_VOLUME);
        }
        int limit = (int) Math.min(Integer.toUnsignedLong(q.limit()), ClientQueries.MAX_BLOCKS);
        return cq.blocks(new QueryBlocks(min, max, q.blocks(), limit));
    }
}
