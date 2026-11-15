package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.BridgeClock;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficEntry;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.core.record.RecordedFrame;
import io.github.pix3lpirat3.mfbridge.core.record.RecorderControls;
import io.github.pix3lpirat3.mfbridge.core.record.RecordingFormat;
import io.github.pix3lpirat3.mfbridge.core.record.ReplayHandle;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.transport.InboundFrame;
import io.github.pix3lpirat3.mfbridge.core.transport.NetConnection;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpened;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionResumed;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The recorder and replayer. Frames are captured on the client thread,
 * where they are already in hand, and written by one background thread, so the client thread never
 * waits on the disk.
 */
@ClientThread
final class Recorder implements RecorderControls {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    /** Request ids of synthesized prelude frames; far above the ids a plugin counts up from 1. */
    private static final long SYNTHETIC_REQ = 1L << 50;
    private static final int UPSERT_BATCH = 256;

    /** One connection's open recording file. The writer is only touched by the worker thread. */
    static final class Track {
        final Path file;
        private RecordingFormat.@Nullable Writer writer;
        private boolean failed;

        Track(Path file) {
            this.file = file;
        }
    }

    private final BridgeClock clock;
    private final SessionTable table;
    private final ObjectStore store;
    private final Path directory;
    private final Logger log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mfbridge-recorder");
        t.setDaemon(true);
        return t;
    });
    private boolean recording;
    private String stamp = "";
    private final List<Path> written = new ArrayList<>();
    private long nextSyntheticReq = SYNTHETIC_REQ;
    private final List<Replay> replays = new ArrayList<>();

    Recorder(BridgeClock clock, SessionTable table, ObjectStore store, Path directory, Logger log) {
        this.clock = clock;
        this.table = table;
        this.store = store;
        this.directory = directory;
        this.log = log;
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public boolean recording() {
        return recording;
    }

    @Override
    public void start() throws IOException {
        if (recording) {
            return;
        }
        Files.createDirectories(directory);
        recording = true;
        stamp = STAMP.format(Instant.ofEpochMilli(clock.currentTimeMillis()));
        written.clear();
        for (Connection c : table.connections) {
            if (c.replay) {
                continue;
            }
            open(c);
            prelude(c);
        }
    }

    @Override
    public List<Path> stop() {
        if (!recording) {
            return List.of();
        }
        recording = false;
        for (Connection c : table.connections) {
            close(c);
        }
        awaitWrites();
        List<Path> out = new ArrayList<>(written);
        out.sort(Comparator.naturalOrder());
        written.clear();
        return List.copyOf(out);
    }

    @Override
    public List<Path> files() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory, "*" + RecordingFormat.EXTENSION)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException e) {
            log.warn("Cannot list recordings in {}: {}", directory, e.toString());
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return List.copyOf(out);
    }

    @Override
    public List<Path> export(List<TrafficEntry> entries) throws IOException {
        Files.createDirectories(directory);
        Map<Long, List<RecordedFrame>> byConnection = new LinkedHashMap<>();
        for (TrafficEntry e : entries) {
            byConnection.computeIfAbsent(e.connectionId(), k -> new ArrayList<>()).add(new RecordedFrame(
                    e.inbound() ? RecordingFormat.FROM_NODE : RecordingFormat.FROM_MOD, e.timeMs(), e.frameBytes()));
        }
        String exportStamp = STAMP.format(Instant.ofEpochMilli(clock.currentTimeMillis()));
        List<Path> out = new ArrayList<>();
        for (Map.Entry<Long, List<RecordedFrame>> e : byConnection.entrySet()) {
            Path file = unique(exportStamp + "-c" + idLabel(e.getKey()) + "-traffic");
            RecordingFormat.writeAll(file, e.getValue());
            out.add(file);
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    @Override
    public ReplayHandle replay(Path file, double speed) throws IOException {
        List<RecordedFrame> frames = RecordingFormat.readAll(file);
        Replay r = new Replay(file, frames, speed);
        replays.add(r);
        table.adoptVirtual(r.net);
        return r;
    }

    @Override
    public @Nullable ReplayHandle lastReplay() {
        return replays.isEmpty() ? null : replays.getLast();
    }

    /** Opens a track for a new connection while recording. */
    void connectionAccepted(Connection c) {
        if (recording && !c.replay) {
            open(c);
        }
    }

    /** Closes the track of a connection that went away. */
    void connectionClosed(Connection c) {
        close(c);
    }

    /** Writes the prelude of a session that resumed onto a recorded connection. */
    void sessionResumed(Connection c, Session s) {
        if (c.recording != null) {
            sessionPrelude(c, s);
        }
    }

    /**
     * Captures one frame of a recorded connection.
     *
     * @param inbound true for a frame Node sent
     * @param payload shared and never modified, so the worker may read it later
     */
    void capture(Connection c, boolean inbound, long wallMs, Envelope env, byte[] payload) {
        Track t = c.recording;
        if (t == null) {
            return;
        }
        int direction = inbound ? RecordingFormat.FROM_NODE : RecordingFormat.FROM_MOD;
        worker.execute(() -> write(t, direction, wallMs, env, payload));
    }

    /** Feeds due frames of running replays; called at the start of each tick. */
    void tick(long nowMs) {
        for (int i = replays.size() - 1; i >= 0; i--) {
            Replay r = replays.get(i);
            if (r.stopped && r.net.isClosed() && r.net.queued() == 0) {
                replays.remove(i);
            } else {
                r.pump(nowMs);
            }
        }
    }

    /** Stops recording and replays and waits briefly for pending writes. */
    void shutdown() {
        stop();
        for (Replay r : List.copyOf(replays)) {
            r.stop();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Recorder did not finish writing within 2 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits up to two seconds for queued writes, so the files are complete once {@link #stop()}
     * returns. Stopping is a player action, so the short wait on the client thread is acceptable.
     */
    private void awaitWrites() {
        try {
            worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            log.warn("Recording files may be incomplete: {}", e.toString());
        }
    }

    private void open(Connection c) {
        if (c.recording != null) {
            return;
        }
        Path file = unique(stamp + "-c" + idLabel(c.net.id()));
        Track t = new Track(file);
        c.recording = t;
        written.add(file);
        worker.execute(() -> {
            try {
                t.writer = RecordingFormat.Writer.create(file);
            } catch (IOException e) {
                t.failed = true;
                log.warn("Cannot create recording {}: {}", file, e.toString());
            }
        });
    }

    private void close(Connection c) {
        Track t = c.recording;
        if (t == null) {
            return;
        }
        c.recording = null;
        worker.execute(() -> {
            RecordingFormat.Writer w = t.writer;
            t.writer = null;
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    log.warn("Cannot finish recording {}: {}", t.file, e.toString());
                }
            }
        });
    }

    private void write(Track t, int direction, long wallMs, Envelope env, byte[] payload) {
        RecordingFormat.Writer w = t.writer;
        if (w == null || t.failed) {
            return;
        }
        try {
            w.write(direction, wallMs, TrafficEntry.frameBytes(env, new byte[0]), payload);
        } catch (IOException e) {
            t.failed = true;
            log.warn("Recording {} stopped after a write error: {}", t.file, e.toString());
        }
    }

    private Path unique(String base) {
        Path p = directory.resolve(base + RecordingFormat.EXTENSION);
        for (int n = 2; Files.exists(p) || written.contains(p); n++) {
            p = directory.resolve(base + "-" + n + RecordingFormat.EXTENSION);
        }
        return p;
    }

    private static String idLabel(long id) {
        return id < 0 ? "r" + (-id) : Long.toString(id);
    }

    /**
     * A connection recorded from the middle starts with synthesized frames
     * that rebuild its state, so its replay shows what the player saw.
     */
    private void prelude(Connection c) {
        if (c.hello == null) {
            return;
        }
        long wall = clock.currentTimeMillis();
        capture(c, true, wall, Envelope.control(CoreMessages.HELLO.id(), 0), Encoding.encode(CoreMessages.HELLO.codec(), c.hello));
        for (Session s : List.copyOf(c.sessions.values())) {
            sessionPrelude(c, s);
        }
    }

    private void sessionPrelude(Connection c, Session s) {
        SessionOpen o = s.opened;
        if (o == null) {
            return;
        }
        long wall = clock.currentTimeMillis();
        long req = nextSyntheticReq++;
        capture(c, true, wall, new Envelope(0, CoreMessages.SESSION_OPEN.id(), 0, Envelope.NONE, Envelope.NONE, req),
                encode(CoreMessages.SESSION_OPEN, o));
        capture(c, false, wall, new Envelope(Envelope.RESPONSE, CoreMessages.SESSION_OPENED.id(), 0, Envelope.NONE,
                Envelope.NONE, req), encode(CoreMessages.SESSION_OPENED, new SessionOpened(s.id, s.resumeToken)));
        if (s.world != null || s.position != null) {
            capture(c, true, wall, Envelope.control(CoreMessages.WORLD_REPORT.id(), s.id),
                    encode(CoreMessages.WORLD_REPORT, new WorldReport(s.world, s.position)));
        }
        ConnRegistry reg = c.registry;
        if (reg == null) {
            return;
        }
        List<ObjectWrite> batch = new ArrayList<>();
        for (StoredObject obj : store.under(s.ownerPrefix)) {
            StoredObject.State st = obj.state();
            int kind = reg.kindId(st.kind());
            if (kind < 0) {
                continue;
            }
            batch.add(new ObjectWrite.Full(new ObjectFull(obj.key(), kind, st.rev(), st.layer(), st.lifetime(), st.scope(),
                    st.world(), st.ttlMs(), st.data())));
            if (batch.size() == UPSERT_BATCH) {
                capture(c, true, wall, Envelope.control(CoreMessages.OBJ_UPSERT.id(), s.id),
                        encode(CoreMessages.OBJ_UPSERT, new ObjUpsert(List.copyOf(batch))));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            capture(c, true, wall, Envelope.control(CoreMessages.OBJ_UPSERT.id(), s.id),
                    encode(CoreMessages.OBJ_UPSERT, new ObjUpsert(List.copyOf(batch))));
        }
    }

    private static <T> byte[] encode(MessageType<T> type, T value) {
        return Encoding.encode(type.codec(), value);
    }

    /** One replay: a virtual connection fed from a recording's Node frames. */
    private final class Replay implements ReplayHandle {
        final Path file;
        final List<RecordedFrame> frames;
        final double speed;
        final NetConnection net;
        final int total;
        final long firstEpochMs;
        long startMs = -1;
        int cursor;
        int delivered;
        boolean stopped;
        /** Recorded session id to the replay's session id. */
        final Int2IntOpenHashMap sessions = new Int2IntOpenHashMap();
        /** Requests of delivered {@code session.open} and {@code session.resume} frames awaiting an answer. */
        final LongOpenHashSet awaiting = new LongOpenHashSet();
        /** Replay session ids the engine answered with, by request id. */
        final Long2IntOpenHashMap answered = new Long2IntOpenHashMap();
        final LongOpenHashSet failed = new LongOpenHashSet();

        Replay(Path file, List<RecordedFrame> frames, double speed) {
            this.file = file;
            this.frames = frames;
            this.speed = speed;
            this.net = NetConnection.virtual("replay:" + file.getFileName(), this::sink);
            int n = 0;
            long first = 0;
            boolean seen = false;
            for (RecordedFrame f : frames) {
                if (f.direction() == RecordingFormat.FROM_NODE) {
                    n++;
                }
                if (!seen) {
                    first = f.epochMs();
                    seen = true;
                }
            }
            this.total = n;
            this.firstEpochMs = first;
            sessions.defaultReturnValue(-1);
            answered.defaultReturnValue(-1);
        }

        /** Learns the replay's answers to session requests from what the engine writes. */
        private void sink(ByteBuf frame) {
            try {
                Envelope env = Envelope.read(new WireReader(frame));
                if (!env.has(Envelope.RESPONSE) || env.session() != 0 || !awaiting.contains(env.req())) {
                    return;
                }
                byte[] payload = new byte[frame.readableBytes()];
                frame.readBytes(payload);
                if (env.type() == CoreMessages.SESSION_OPENED.id()) {
                    answered.put(env.req(), Encoding.decode(SessionOpened.CODEC, payload).sessionId());
                } else if (env.type() == CoreMessages.SESSION_RESUMED.id()) {
                    answered.put(env.req(), Encoding.decode(SessionResumed.CODEC, payload).sessionId());
                } else {
                    failed.add(env.req());
                }
            } catch (WireException e) {
                log.debug("Replay {} ignored an undecodable answer: {}", file, e.getMessage());
            } finally {
                frame.release();
            }
        }

        void pump(long nowMs) {
            if (stopped) {
                return;
            }
            if (startMs < 0) {
                startMs = nowMs;
            }
            double elapsed = (nowMs - startMs) * speed;
            while (cursor < frames.size()) {
                RecordedFrame f = frames.get(cursor);
                if (speed > 0 && f.epochMs() - firstEpochMs > elapsed) {
                    return;
                }
                ByteBuf buf = Unpooled.wrappedBuffer(f.bytes());
                Envelope env;
                try {
                    env = Envelope.read(new WireReader(buf));
                } catch (WireException e) {
                    log.debug("Replay {} skipped a malformed frame: {}", file, e.getMessage());
                    cursor++;
                    continue;
                }
                byte[] payload = new byte[buf.readableBytes()];
                buf.readBytes(payload);
                if (f.direction() == RecordingFormat.FROM_NODE) {
                    deliver(env, payload);
                    cursor++;
                    delivered++;
                    continue;
                }
                if (!learn(env, payload)) {
                    // The engine has not answered our copy of this session request yet; wait a tick.
                    return;
                }
                cursor++;
            }
        }

        private void deliver(Envelope env, byte[] payload) {
            Envelope e = env;
            if (env.session() != 0) {
                int mapped = sessions.get(env.session());
                if (mapped < 0) {
                    return;
                }
                e = new Envelope(env.flags(), env.type(), mapped, env.seq(), env.ack(), env.req());
            } else if (env.req() != Envelope.NONE && !env.has(Envelope.RESPONSE)
                    && (env.type() == CoreMessages.SESSION_OPEN.id() || env.type() == CoreMessages.SESSION_RESUME.id())) {
                awaiting.add(env.req());
            }
            net.deliver(new InboundFrame(e, payload, null));
        }

        /** Maps a recorded session answer; returns false while the replay's own answer is pending. */
        private boolean learn(Envelope env, byte[] payload) {
            boolean answer = env.has(Envelope.RESPONSE) && env.session() == 0
                    && (env.type() == CoreMessages.SESSION_OPENED.id() || env.type() == CoreMessages.SESSION_RESUMED.id());
            if (!answer || !awaiting.contains(env.req())) {
                return true;
            }
            int mine = answered.get(env.req());
            if (mine < 0 && !failed.contains(env.req())) {
                return net.isClosed();
            }
            awaiting.remove(env.req());
            if (mine >= 0) {
                try {
                    int recorded = env.type() == CoreMessages.SESSION_OPENED.id()
                            ? Encoding.decode(SessionOpened.CODEC, payload).sessionId()
                            : Encoding.decode(SessionResumed.CODEC, payload).sessionId();
                    sessions.put(recorded, mine);
                } catch (WireException e) {
                    log.debug("Replay {} could not map a session: {}", file, e.getMessage());
                }
            }
            return true;
        }

        @Override
        public Path file() {
            return file;
        }

        @Override
        public int delivered() {
            return delivered;
        }

        @Override
        public int total() {
            return total;
        }

        @Override
        public boolean finished() {
            return cursor >= frames.size();
        }

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            int dropped = 0;
            while (net.poll() != null) {
                dropped++;
            }
            if (dropped > 0) {
                log.debug("Replay {} stopped with {} frames not taken in", file, dropped);
            }
            net.markVirtualClosed();
        }
    }
}
