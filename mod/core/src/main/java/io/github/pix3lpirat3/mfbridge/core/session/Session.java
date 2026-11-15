package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.SessionRef;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PlayerContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedSubscribe;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashSet;
import java.util.Set;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import java.util.ArrayList;
import java.util.EnumSet;
import io.github.pix3lpirat3.mfbridge.protocol.core.ClientSample;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import org.jspecify.annotations.Nullable;

/**
 * One participant: a bot or a controller. Outlives its connection for the
 * resume window. Confined to the client thread.
 */
final class Session {
    final SessionKind kind;
    final String displayName;
    /** Bot identity key; null for controllers. */
    final @Nullable String identity;
    /** Owner without trailing slash, for example {@code bot:<identity>}. */
    final String owner;
    /** Owned key prefix, {@code owner + "/"}. */
    final String ownerPrefix;
    final String deviceId;
    final byte[] resumeToken;
    final String tokenKey;
    /** Distinguishes writers: a new session may overwrite objects of an older one regardless of rev. */
    final int epoch;

    @Nullable Connection conn;
    @Nullable ConnRegistry lastRegistry;
    int id;
    long detachedAtMs = -1;
    boolean superseded;
    boolean ended;
    /** Handle given to mod extensions, made on first use. */
    @Nullable SessionRef ref;

    long lastAppliedIn;
    long ackDueSinceMs = -1;
    final OutboundLog log;
    long nextOutSeq = 1;
    long detachedReq;
    final Long2ObjectOpenHashMap<PendingRequest<?>> pending = new Long2ObjectOpenHashMap<>();

    int snapshotGen;
    boolean inSnapshot;

    /** The open transaction id (u32), or -1. */
    long txId = -1;
    /** Bridge clock ms at which the open transaction began. */
    long txStartedMs;
    /** {@code obj.upsert} and {@code obj.remove} messages buffered until {@code obj.commit}. */
    final ArrayList<Object> txWrites = new ArrayList<>();

    @Nullable WorldKey world;
    /** Last {@code world.report} position, for {@code near: bot} completion and {@code from=bot} selectors. */
    @Nullable Vec3d position;
    @Nullable SharedSubscribe subscription;
    /** Keys of the shared objects this session was sent. */
    Set<String> deliveredShared = new HashSet<>();

    @Nullable Interest lastInterest;
    @Nullable PlayerContext lastContext;
    boolean interestDirty = true;

    /** Start of the current one-second effect window, bridge clock ms. */
    long effectWindowStartMs = Long.MIN_VALUE / 2;
    int effectsInWindow;

    /** Start of the current one-second window of notifications, bridge clock ms. */
    long noticeWindowStartMs = Long.MIN_VALUE / 2;
    int noticesInWindow;

    /** Open inbound streams by stream id. */
    final Int2ObjectOpenHashMap<Streams.Inbound> streams = new Int2ObjectOpenHashMap<>();
    /** Declared payload bytes of the open inbound streams. */
    long streamBytesInFlight;
    /** Permissions whose denial was already reported for unsolicited messages. */
    final EnumSet<Permission> deniedReported = EnumSet.noneOf(Permission.class);
    /** Whether this session's interest currently says {@code reduced}. */
    boolean reducedDetail;
    /** The {@code session.open} that created the session, for the inspector and recording preludes. */
    @Nullable SessionOpen opened;
    /** The bot's current request, or null when it wants none. */
    @Nullable SampleRequest sampleRequest;
    /** Bridge clock ms before which no further sample is sent. */
    long nextSampleMs;
    /** The last sample sent, to skip unchanged ones; null after (re)attaching. */
    @Nullable ClientSample lastSample;

    Session(
            SessionKind kind,
            String displayName,
            @Nullable String identity,
            String owner,
            String deviceId,
            byte[] resumeToken,
            String tokenKey,
            int epoch,
            long logCapBytes) {
        this.kind = kind;
        this.displayName = displayName;
        this.identity = identity;
        this.owner = owner;
        this.ownerPrefix = owner + '/';
        this.deviceId = deviceId;
        this.resumeToken = resumeToken;
        this.tokenKey = tokenKey;
        this.epoch = epoch;
        this.log = new OutboundLog(logCapBytes);
    }

    /** Registry to encode outbound kind ids with: the live connection's, else the last one's. */
    @Nullable ConnRegistry registry() {
        Connection c = conn;
        return c != null ? c.registry : lastRegistry;
    }
}
