package io.github.pix3lpirat3.mfbridge.common.render.retained;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.game.RenderFrame26x;
import io.github.pix3lpirat3.mfbridge.common.render.FrustumView;
import io.github.pix3lpirat3.mfbridge.common.render.cull.ViewVolume;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobDecoders;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.core.retained.CellSet;
import io.github.pix3lpirat3.mfbridge.core.retained.Colormaps;
import io.github.pix3lpirat3.mfbridge.core.retained.DiffClassifier;
import io.github.pix3lpirat3.mfbridge.core.retained.GhostVolume;
import io.github.pix3lpirat3.mfbridge.core.retained.MeshWriter;
import io.github.pix3lpirat3.mfbridge.core.retained.SectionKeys;
import io.github.pix3lpirat3.mfbridge.core.retained.SectionMeshing;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.BlockHighlightData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode;
import io.github.pix3lpirat3.mfbridge.protocol.core.GhostBlocksData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HeatmapBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.HeatmapData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HighlightBlob;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import io.github.pix3lpirat3.mfbridge.protocol.core.VolumeBlob;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The retained render tier: ghost blocks, heatmaps and block highlights meshed
 * per 16x16x16 section on a worker pool and kept in GPU arenas.
 *
 * <ul>
 *   <li><b>Sync</b> (render thread, when the snapshot changes): the entries the snapshot lists as
 *       changed become objects; the others are left alone. A change of
 *       geometry inputs (blob, origin, diff flags, cell size, positions, mode) rebuilds the object;
 *       a change of colour, alpha, style, colormap or value range only updates uniforms.
 *   <li><b>Prepare</b> (worker): the decoded blob is bucketed into world sections; ghost palettes are
 *       parsed. Objects whose blob has not arrived wait without drawing.
 *   <li><b>Diff</b> (render thread, at most {@link #WORLD_READS_PER_FRAME} sections per frame): with
 *       {@code diffAgainstWorld}, the client level's states of a section are copied whenever the game
 *       marks that section dirty, and the section is reclassified and re-meshed on a worker.
 *   <li><b>Mesh</b> (worker, nearest sections first): vertex buffers in the core's layouts.
 *   <li><b>Upload</b> (render thread): finished meshes enter the arena within
 *       {@link #UPLOAD_BUDGET_NANOS} and {@link #UPLOAD_BUDGET_BYTES} per frame; the rest waits.
 *   <li><b>Draw</b> (render thread, one pass after vanilla translucency): per section frustum and
 *       distance culling; heatmaps beyond {@link #HEATMAP_LOD_BLOCKS} are skipped and ghost blocks
 *       beyond {@link #GHOST_LOD_BLOCKS} draw outlines only. Depth modes
 *       follow the immediate tier: {@code both} draws dimmed through walls, then depth-tested.
 * </ul>
 *
 * <p>Each owner may hold at most {@link #QUOTA_BYTES} of mesh memory; sections over
 * the quota are not uploaded and a warning is logged once per owner.
 */
public final class RetainedRenderer {
    /** Upload time budget per frame, in ns. */
    static final long UPLOAD_BUDGET_NANOS = 1_500_000L;
    /** Upload byte budget per frame. */
    static final long UPLOAD_BUDGET_BYTES = 4L << 20;
    /** Retained geometry per owner, in bytes. */
    static final long QUOTA_BYTES = 64L << 20;
    /** Heatmap sections farther than this are not drawn, in blocks. */
    static final double HEATMAP_LOD_BLOCKS = 64;
    /** Ghost sections farther than this draw outlines instead of block faces, in blocks. */
    static final double GHOST_LOD_BLOCKS = 48;
    /** Nothing farther than this is drawn, in blocks. */
    static final double MAX_DISTANCE_BLOCKS = 768;
    /** World sections copied for diffing per frame. */
    static final int WORLD_READS_PER_FRAME = 32;
    /** Alpha multiplier of the through-walls half of depth mode {@code both}. */
    private static final float XRAY_DIM = 0.4f;
    /** Fill alpha of highlight faces when the style sets none. */
    private static final float DEFAULT_FACE_ALPHA = 0.25f;
    private static final int AIR_ID = Block.getId(Blocks.AIR.defaultBlockState());

    private enum Kind { GHOST, HEAT, HIGHLIGHT }

    /** One section's GPU meshes; {@code faces} hold quads, {@code near} and {@code far} lines. */
    private static final class SectionMeshes {
        GpuArena.@Nullable Slot faces;
        int faceVertices;
        GpuArena.@Nullable Slot near;
        int nearVertices;
        GpuArena.@Nullable Slot far;
        int farVertices;
    }

    /** One retained object; render thread state except where noted. */
    private static final class Obj {
        final String key;
        final String owner;
        final Kind kind;
        final Object geometry;
        final @Nullable BlobRef blob;
        final int generation;
        /** The per-frame part of {@code visibleWhen}, or null when always shown. */
        @Nullable Visibility visibility;
        /** Positions carried in the object itself (highlights without a blob), or null. */
        int @Nullable [] inline;
        /** Where the object's blocks or cells actually are, once prepared. */
        ObjectBounds.@Nullable Box bounds;
        boolean preparing;
        boolean ready;
        boolean failed;
        // Prepared inputs, immutable once set; read by workers.
        @Nullable GhostVolume volume;
        @Nullable GhostStates states;
        @Nullable CellSet cells;
        float @Nullable [] values;
        float[] range = {0f, 0f};
        boolean diff;
        boolean hideMatching;
        String mode = "outline";
        float cellSize = 1f;
        // Uniform inputs, updated without re-meshing.
        float alpha = 1f;
        int argb = 0xFFFFFFFF;
        float fill;
        DepthMode depth = DepthMode.TEST;
        int colormapRow;
        @Nullable Float min;
        @Nullable Float max;
        final Long2ObjectOpenHashMap<SectionMeshes> sections = new Long2ObjectOpenHashMap<>();
        /** Section keys far to near from the camera section {@link #orderFrom}; rebuilt when either changes. */
        long[] order = new long[0];
        long orderFrom = Long.MIN_VALUE;
        boolean orderStale = true;
        final Long2ObjectOpenHashMap<int[]> sectionGen = new Long2ObjectOpenHashMap<>();
        /** Overlay classes per section; written by workers, read by neighbouring sections' workers. */
        final ConcurrentHashMap<Long, byte[]> classes = new ConcurrentHashMap<>();
        final LongLinkedOpenHashSet worldPending = new LongLinkedOpenHashSet();
        long bytes;

        Obj(final String key, final String owner, final Kind kind, final Object geometry, final @Nullable BlobRef blob,
                final int generation) {
            this.key = key;
            this.owner = owner;
            this.kind = kind;
            this.geometry = geometry;
            this.blob = blob;
            this.generation = generation;
        }
    }

    private record GhostGeometry(String blob, int ox, int oy, int oz, boolean diff, boolean hideMatching) {}

    private record HeatGeometry(String blob, float cellSize) {}

    private record HighlightGeometry(int positionsHash, int positionsLength, @Nullable String blob, String mode,
            float lineWidth) {}

    /** Work for the mesh pool, nearest first. */
    private abstract static class Job implements Runnable, Comparable<Job> {
        final double priority;

        Job(final double priority) {
            this.priority = priority;
        }

        @Override
        public int compareTo(final Job o) {
            return Double.compare(priority, o.priority);
        }
    }

    private record Prepared(Obj obj, @Nullable GhostVolume volume, @Nullable GhostStates states, @Nullable CellSet cells,
            float @Nullable [] values, float[] range, ObjectBounds.@Nullable Box bounds, @Nullable Throwable error) {}

    private record Meshed(Obj obj, long key, int gen, @Nullable ByteBuffer faces, int faceVertices,
            @Nullable ByteBuffer near, int nearVertices, @Nullable ByteBuffer far, int farVertices) {}

    private static volatile @Nullable RetainedRenderer current;

    private final ThreadPoolExecutor workers;
    private final ConcurrentLinkedQueue<Prepared> prepared = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Meshed> meshed = new ConcurrentLinkedQueue<>();
    private final Map<String, Obj> objects = new HashMap<>();
    private final LongOpenHashSet dirtyWorld = new LongOpenHashSet();
    private final Object2LongOpenHashMap<String> ownerBytes = new Object2LongOpenHashMap<>();
    private final Set<String> quotaWarned = new HashSet<>();
    private final Matrix4f view = new Matrix4f();
    private @Nullable GpuArena arena;
    private @Nullable DynamicTexture colormap;
    private @Nullable BridgeCore core;
    private @Nullable RenderSnapshot synced;
    private @Nullable Object modelSet;
    private @Nullable ClientLevel level;
    private final FrustumView frustumView = new FrustumView();
    private ViewVolume volume = new ViewVolume();
    /** Distance of each section looked at this frame, negative when culled; filled on first use. */
    private final Long2DoubleOpenHashMap sectionDistance = new Long2DoubleOpenHashMap();
    private double camX;
    private double camY;
    private double camZ;
    private int nextGeneration = 1;
    /** Changes whenever an object's bounds appear or go, so hit testing can rebuild. */
    private long boundsVersion;
    private Supplier<@Nullable String> lookedAt = () -> null;
    private boolean disabled;
    private boolean frameReady;
    /** Render-thread time spent in this frame so far. */
    private long frameNanos;

    /** Creates the renderer and its worker pool of {@code max(1, cores / 4)} daemon threads. */
    public RetainedRenderer() {
        final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        final AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(),
                r -> {
                    final Thread t = new Thread(r, "mfbridge-mesh-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
        this.workers.allowCoreThreadTimeOut(true);
        this.sectionDistance.defaultReturnValue(Double.NaN);
        final String problem = RetainedPipelines.layoutProblem();
        if (problem != null) {
            disabled = true;
            Constants.LOG.error("Retained rendering disabled: vertex layouts differ from the game's ({})", problem);
        }
    }

    /** Returns the renderer the game hooks call into, or null before the client starts or after it stops. */
    public static @Nullable RetainedRenderer current() {
        return current;
    }

    /** Makes this the renderer the game hooks call into. Client thread, at client start. */
    public void install() {
        current = this;
    }

    /**
     * Records that the game marked a world section dirty; diffing ghost volumes re-read it. Client
     * thread, called from the level extractor hook for every block or section change.
     */
    public void sectionDirty(final int sx, final int sy, final int sz) {
        if (!objects.isEmpty()) {
            dirtyWorld.add(SectionKeys.pack(sx, sy, sz));
        }
    }

    /**
     * Extract phase: follows the running core's snapshot, starts preparation and mesh work, and
     * captures the camera for {@link #draw}. Render thread, from the world render listener.
     *
     * @param frame the frame
     * @param running the running core, or null
     */
    public void frame(final RenderFrame frame, final @Nullable BridgeCore running) {
        final long start = System.nanoTime();
        try {
            extract(frame, running);
        } finally {
            frameNanos += System.nanoTime() - start;
        }
    }

    /**
     * Returns the render-thread time spent in {@link #frame} and {@link #draw} since the last call,
     * and starts counting again. Render thread.
     *
     * @return nanoseconds
     */
    public long takeFrameNanos() {
        final long n = frameNanos;
        frameNanos = 0;
        return n;
    }

    /**
     * Returns the GPU memory held by retained meshes. Render thread.
     *
     * @return bytes
     */
    public long gpuBytes() {
        final GpuArena a = arena;
        return a == null ? 0 : a.usedBytes();
    }

    /**
     * Returns the meshing jobs and finished meshes waiting for upload. Render thread; costs a walk of
     * the queues, so callers sample it rather than reading it every frame.
     *
     * @return queued items
     */
    public int queueDepth() {
        return prepared.size() + meshed.size() + workers.getQueue().size();
    }

    private void extract(final RenderFrame frame, final @Nullable BridgeCore running) {
        frameReady = false;
        if (disabled || !(frame instanceof RenderFrame26x f)) {
            return;
        }
        if (running != core) {
            clear();
            core = running;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (running == null || minecraft.level == null) {
            if (!objects.isEmpty()) {
                clear();
            }
            return;
        }
        camX = f.cameraX();
        camY = f.cameraY();
        camZ = f.cameraZ();
        volume = frustumView.look(f.camera().cullFrustum);
        final BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
        if (models != modelSet || minecraft.level != level) {
            // A resource reload rebakes the atlas, and a new level invalidates every diff.
            final boolean reload = modelSet != null;
            modelSet = models;
            level = minecraft.level;
            if (reload) {
                clear();
            }
        }
        final RenderSnapshot snapshot = running.renderSnapshot();
        if (snapshot != synced) {
            sync(snapshot);
            synced = snapshot;
        }
        advance(snapshot.blobs(), models, minecraft.getBlockColors());
        frameReady = !objects.isEmpty();
    }

    private void sync(final RenderSnapshot snapshot) {
        final RenderSnapshot before = synced;
        final RenderSnapshot.Change change = before == null ? null : snapshot.changesSince(before.version());
        if (change != null) {
            for (final RenderEntry e : change.removed()) {
                drop(e.key());
            }
            for (final RenderEntry e : change.upserted()) {
                if (!upsert(e)) {
                    // The key may have held a retained object before.
                    drop(e.key());
                }
            }
            return;
        }
        // The snapshots in between were missed: go through every entry.
        final Set<String> seen = new HashSet<>();
        for (final RenderEntry e : snapshot.entries()) {
            if (upsert(e)) {
                seen.add(e.key());
            }
        }
        final Iterator<Map.Entry<String, Obj>> it = objects.entrySet().iterator();
        while (it.hasNext()) {
            final Obj obj = it.next().getValue();
            if (!seen.contains(obj.key)) {
                it.remove();
                if (obj.bounds != null) {
                    boundsVersion++;
                }
                dispose(obj);
            }
        }
    }

    private void drop(final String key) {
        final Obj obj = objects.remove(key);
        if (obj != null) {
            if (obj.bounds != null) {
                boundsVersion++;
            }
            dispose(obj);
        }
    }

    /** Creates or updates the object of an entry; returns false when the entry is not a retained kind. */
    private boolean upsert(final RenderEntry e) {
        final Object data = e.data();
        final Kind kind;
        final Object geometry;
        int[] inline = null;
        switch (data) {
            case GhostBlocksData g -> {
                kind = Kind.GHOST;
                final var o = g.origin();
                geometry = new GhostGeometry(String.valueOf(e.blob()), o == null ? 0 : o.x(), o == null ? 0 : o.y(),
                        o == null ? 0 : o.z(), g.diffAgainstWorld(), g.hideMatching());
            }
            case HeatmapData h -> {
                kind = Kind.HEAT;
                geometry = new HeatGeometry(String.valueOf(e.blob()), h.cellSize());
            }
            case BlockHighlightData b -> {
                kind = Kind.HIGHLIGHT;
                final int[] p = b.positions();
                inline = p;
                final StyleData st = e.style();
                final BlobRef ref = e.blob();
                // The lines shader takes width per vertex, so the style's width is part of the mesh.
                geometry = new HighlightGeometry(p == null ? 0 : Arrays.hashCode(p), p == null ? -1 : p.length,
                        ref == null ? null : ref.hex(), b.mode(), st == null || !(st.lineWidth() > 0f) ? 2f : st.lineWidth());
            }
            case null, default -> {
                return false;
            }
        }
        Obj obj = objects.get(e.key());
        if (obj == null || !obj.geometry.equals(geometry)) {
            if (obj != null) {
                if (obj.bounds != null) {
                    boundsVersion++;
                }
                dispose(obj);
            }
            obj = new Obj(e.key(), e.owner(), kind, geometry, e.blob(), nextGeneration++);
            obj.inline = inline;
            objects.put(e.key(), obj);
        }
        uniforms(obj, data, e.style());
        final Visibility v = Traits.visibleWhen(e.traits());
        obj.visibility = Traits.perFrame(v) ? v : null;
        return true;
    }

    private static DepthMode styleDepth(final @Nullable StyleData style) {
        return style == null ? DepthMode.TEST : style.depth();
    }

    private static void uniforms(final Obj obj, final Object data, final @Nullable StyleData style) {
        switch (data) {
            case GhostBlocksData g -> {
                obj.alpha = clamp01(g.alpha());
                obj.depth = styleDepth(style);
                obj.diff = g.diffAgainstWorld();
                obj.hideMatching = g.hideMatching();
            }
            case HeatmapData h -> {
                obj.alpha = clamp01(h.alpha());
                obj.depth = h.depth();
                obj.colormapRow = Colormaps.row(h.colormap());
                obj.cellSize = h.cellSize();
                if (!Objects.equals(obj.min, h.min()) || !Objects.equals(obj.max, h.max())) {
                    obj.min = h.min();
                    obj.max = h.max();
                    final float[] values = obj.values;
                    if (values != null) {
                        obj.range = Colormaps.range(values, obj.min, obj.max);
                    }
                }
            }
            case BlockHighlightData b -> {
                obj.mode = b.mode();
                obj.depth = styleDepth(style);
                obj.argb = style == null ? 0xFFFFFFFF : style.color();
                obj.fill = style == null || !(style.fill() > 0f) ? DEFAULT_FACE_ALPHA : clamp01(style.fill());
            }
            default -> {
            }
        }
    }

    private static float clamp01(final float v) {
        return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(1f, v));
    }

    /** Prepares, collects worker results and reads dirty world sections for diffing ghosts. */
    private void advance(final BlobStore blobs, final BlockStateModelSet models, final BlockColors colors) {
        for (final Obj obj : objects.values()) {
            if (obj.preparing || obj.ready || obj.failed) {
                continue;
            }
            startPrepare(obj, blobs);
        }
        Prepared p;
        while ((p = prepared.poll()) != null) {
            final Obj obj = p.obj();
            if (objects.get(obj.key) != obj) {
                continue;
            }
            obj.preparing = false;
            obj.ready = true;
            if (p.error() != null) {
                obj.failed = true;
                Constants.LOG.warn("Retained object {} could not be prepared: {}", obj.key, p.error().toString());
                continue;
            }
            obj.volume = p.volume();
            obj.states = p.states();
            obj.cells = p.cells();
            obj.values = p.values();
            obj.range = p.range();
            obj.bounds = p.bounds();
            boundsVersion++;
            scheduleAll(obj, models, colors);
        }
        if (!dirtyWorld.isEmpty()) {
            final LongIterator it = dirtyWorld.iterator();
            while (it.hasNext()) {
                final long key = it.nextLong();
                for (final Obj obj : objects.values()) {
                    if (obj.kind == Kind.GHOST && obj.diff && obj.volume != null && obj.volume.section(key) != null) {
                        obj.worldPending.add(key);
                    }
                }
            }
            dirtyWorld.clear();
        }
        int reads = WORLD_READS_PER_FRAME;
        for (final Obj obj : objects.values()) {
            while (reads > 0 && !obj.worldPending.isEmpty()) {
                final long key = obj.worldPending.removeFirstLong();
                scheduleGhost(obj, key, readWorld(key), models, colors);
                reads--;
            }
        }
    }

    private void startPrepare(final Obj obj, final BlobStore blobs) {
        final BlobRef ref = obj.blob;
        final Object decoded;
        if (ref == null) {
            decoded = null;
        } else {
            final Object value = switch (obj.kind) {
                case GHOST -> blobs.decodedNow(ref, BlobDecoders.VOLUME);
                case HEAT -> blobs.decodedNow(ref, BlobDecoders.HEATMAP);
                case HIGHLIGHT -> blobs.decodedNow(ref, BlobDecoders.HIGHLIGHT);
            };
            if (value == null) {
                // Blob references are kept but not drawn until the blob arrives and decodes.
                return;
            }
            decoded = value;
        }
        final int[] inline = obj.inline;
        if (decoded == null && inline == null) {
            obj.failed = true;
            return;
        }
        obj.preparing = true;
        final GhostGeometry gg = obj.geometry instanceof GhostGeometry g ? g : null;
        final Float min = obj.min;
        final Float max = obj.max;
        final double cellSize = obj.cellSize > 0f && Float.isFinite(obj.cellSize) ? obj.cellSize : 1d;
        workers.execute(new Job(-1) {
            @Override
            public void run() {
                try {
                    prepared.add(switch (decoded) {
                        case VolumeBlob v -> {
                            final GhostVolume volume = GhostVolume.build(v, gg == null ? 0 : gg.ox(),
                                    gg == null ? 0 : gg.oy(), gg == null ? 0 : gg.oz());
                            yield new Prepared(obj, volume, new GhostStates(volume.palette()), null, null, new float[2],
                                    volume.bounds(), null);
                        }
                        case HeatmapBlob h -> {
                            final CellSet cells = CellSet.of(h.cells());
                            yield new Prepared(obj, null, null, cells, h.values(), Colormaps.range(h.values(), min, max),
                                    cells.bounds(cellSize), null);
                        }
                        case HighlightBlob h -> {
                            final CellSet cells = CellSet.of(h.positions());
                            yield new Prepared(obj, null, null, cells, null, new float[2], cells.bounds(1), null);
                        }
                        case null, default -> {
                            final CellSet cells = CellSet.of(Objects.requireNonNull(inline));
                            yield new Prepared(obj, null, null, cells, null, new float[2], cells.bounds(1), null);
                        }
                    });
                } catch (final RuntimeException e) {
                    prepared.add(new Prepared(obj, null, null, null, null, new float[2], null, e));
                }
            }
        });
    }

    private void scheduleAll(final Obj obj, final BlockStateModelSet models, final BlockColors colors) {
        switch (obj.kind) {
            case GHOST -> {
                final GhostVolume volume = Objects.requireNonNull(obj.volume);
                for (final long key : volume.sections().keySet()) {
                    if (obj.diff) {
                        obj.worldPending.add(key);
                    } else {
                        scheduleGhost(obj, key, null, models, colors);
                    }
                }
            }
            case HEAT, HIGHLIGHT -> {
                final CellSet cells = Objects.requireNonNull(obj.cells);
                for (final Long2ObjectMap.Entry<int[]> e : cells.sections().long2ObjectEntrySet()) {
                    scheduleCells(obj, e.getLongKey(), e.getValue());
                }
            }
        }
    }

    private int nextGen(final Obj obj, final long key) {
        int[] g = obj.sectionGen.get(key);
        if (g == null) {
            g = new int[1];
            obj.sectionGen.put(key, g);
        }
        return ++g[0];
    }

    private double priority(final long key) {
        final double dx = (SectionKeys.x(key) << 4) + 8 - camX;
        final double dy = (SectionKeys.y(key) << 4) + 8 - camY;
        final double dz = (SectionKeys.z(key) << 4) + 8 - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void scheduleGhost(final Obj obj, final long key, final int @Nullable [] world,
            final BlockStateModelSet models, final BlockColors colors) {
        final GhostVolume volume = Objects.requireNonNull(obj.volume);
        final GhostStates states = Objects.requireNonNull(obj.states);
        final short[] ids = volume.section(key);
        if (ids == null) {
            return;
        }
        final int gen = nextGen(obj, key);
        final boolean hide = obj.hideMatching;
        workers.execute(new Job(priority(key)) {
            @Override
            public void run() {
                final byte[] classes = new byte[4096];
                DiffClassifier.classify(ids, world, states, classes);
                obj.classes.put(key, classes);
                final GhostMesher.Meshes m = GhostMesher.mesh(volume, states, key, classes,
                        k -> k == key ? classes : obj.classes.get(k), hide, models, colors);
                meshed.add(new Meshed(obj, key, gen, m.faces().finish(), m.faces().vertices(), m.near().finish(),
                        m.near().vertices(), m.far().finish(), m.far().vertices()));
            }
        });
    }

    private void scheduleCells(final Obj obj, final long key, final int[] indexes) {
        final CellSet cells = Objects.requireNonNull(obj.cells);
        final int gen = nextGen(obj, key);
        final Kind kind = obj.kind;
        final float[] values = obj.values;
        final float cellSize = obj.cellSize;
        final String mode = obj.mode;
        final float width = obj.geometry instanceof HighlightGeometry hg ? hg.lineWidth() : 2f;
        workers.execute(new Job(priority(key)) {
            @Override
            public void run() {
                if (kind == Kind.HEAT) {
                    final MeshWriter w = new MeshWriter(MeshWriter.Format.TEX, indexes.length * 24);
                    SectionMeshing.heatmap(cells, values == null ? new float[0] : values, indexes, key, cellSize, w);
                    meshed.add(new Meshed(obj, key, gen, w.finish(), w.vertices(), null, 0, null, 0));
                    return;
                }
                MeshWriter faces = null;
                MeshWriter lines = null;
                if (!"outline".equals(mode)) {
                    faces = new MeshWriter(MeshWriter.Format.COLOR, indexes.length * 8);
                    SectionMeshing.faces(key, cells, 0xFFFFFFFF, faces);
                }
                if (!"faces".equals(mode)) {
                    lines = new MeshWriter(MeshWriter.Format.LINE, indexes.length * 16);
                    SectionMeshing.outline(key, cells, 0xFFFFFFFF, width, lines);
                }
                meshed.add(new Meshed(obj, key, gen, faces == null ? null : faces.finish(),
                        faces == null ? 0 : faces.vertices(), lines == null ? null : lines.finish(),
                        lines == null ? 0 : lines.vertices(), null, 0));
            }
        });
    }

    /** Copies one world section's state ids, or null when its chunk is not loaded. */
    private int @Nullable [] readWorld(final long key) {
        final ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null) {
            return null;
        }
        final int sx = SectionKeys.x(key);
        final int sy = SectionKeys.y(key);
        final int sz = SectionKeys.z(key);
        final LevelChunk chunk = lvl.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }
        final int[] out = new int[4096];
        final int index = lvl.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= lvl.getSectionsCount()) {
            Arrays.fill(out, AIR_ID);
            return out;
        }
        final LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) {
            Arrays.fill(out, AIR_ID);
            return out;
        }
        for (int i = 0; i < 4096; i++) {
            out[i] = Block.getId(section.getBlockState(i & 15, i >> 8, (i >> 4) & 15));
        }
        return out;
    }

    private void upload() {
        final long start = System.nanoTime();
        long bytes = 0;
        Meshed m;
        while (bytes < UPLOAD_BUDGET_BYTES && System.nanoTime() - start < UPLOAD_BUDGET_NANOS
                && (m = meshed.poll()) != null) {
            final Obj obj = m.obj();
            final int[] gen = obj.sectionGen.get(m.key());
            if (objects.get(obj.key) != obj || gen == null || gen[0] != m.gen()) {
                continue;
            }
            final long size = remaining(m.faces()) + remaining(m.near()) + remaining(m.far());
            SectionMeshes old = obj.sections.get(m.key());
            final long oldBytes = old == null ? 0 : slotBytes(old);
            final long ownerTotal = ownerBytes.getLong(obj.owner) - oldBytes + size;
            if (ownerTotal > QUOTA_BYTES) {
                if (quotaWarned.add(obj.owner)) {
                    Constants.LOG.warn("Retained geometry of {} exceeds its {} MiB quota; further sections are skipped",
                            obj.owner, QUOTA_BYTES >> 20);
                }
                continue;
            }
            if (old != null) {
                freeSlots(obj, old);
            } else {
                old = new SectionMeshes();
                obj.sections.put(m.key(), old);
                obj.orderStale = true;
            }
            final GpuArena a = arena();
            if (m.faceVertices() > 0) {
                old.faces = a.upload(Objects.requireNonNull(m.faces()));
                old.faceVertices = m.faceVertices();
            }
            if (m.nearVertices() > 0) {
                old.near = a.upload(Objects.requireNonNull(m.near()));
                old.nearVertices = m.nearVertices();
            }
            if (m.farVertices() > 0) {
                old.far = a.upload(Objects.requireNonNull(m.far()));
                old.farVertices = m.farVertices();
            }
            final long now = slotBytes(old);
            obj.bytes += now;
            ownerBytes.addTo(obj.owner, now);
            bytes += now;
        }
    }

    private static long remaining(final @Nullable ByteBuffer b) {
        return b == null ? 0 : b.remaining();
    }

    private static long slotBytes(final SectionMeshes s) {
        return (s.faces == null ? 0 : s.faces.bytes()) + (s.near == null ? 0 : s.near.bytes())
                + (s.far == null ? 0 : s.far.bytes());
    }

    private GpuArena arena() {
        GpuArena a = arena;
        if (a == null) {
            a = new GpuArena();
            arena = a;
        }
        return a;
    }

    private void freeSlots(final Obj obj, final SectionMeshes s) {
        final long b = slotBytes(s);
        final GpuArena a = arena;
        if (a != null) {
            if (s.faces != null) {
                a.free(s.faces);
            }
            if (s.near != null) {
                a.free(s.near);
            }
            if (s.far != null) {
                a.free(s.far);
            }
        }
        s.faces = null;
        s.near = null;
        s.far = null;
        s.faceVertices = 0;
        s.nearVertices = 0;
        s.farVertices = 0;
        obj.bytes -= b;
        ownerBytes.addTo(obj.owner, -b);
    }

    private void dispose(final Obj obj) {
        for (final SectionMeshes s : obj.sections.values()) {
            freeSlots(obj, s);
        }
        obj.sections.clear();
        obj.orderStale = true;
        obj.sectionGen.clear();
        obj.classes.clear();
        obj.worldPending.clear();
    }

    private void clear() {
        for (final Obj obj : objects.values()) {
            dispose(obj);
        }
        objects.clear();
        prepared.clear();
        meshed.clear();
        dirtyWorld.clear();
        ownerBytes.clear();
        quotaWarned.clear();
        synced = null;
    }

    /**
     * Draw phase: uploads finished meshes within the frame budget, then draws every visible section in
     * one render pass over the main target. Called on the render thread after vanilla's translucent
     * pass, from the level renderer hook.
     */
    public void draw() {
        if (disabled || objects.isEmpty()) {
            return;
        }
        final long start = System.nanoTime();
        try {
            drawSections();
        } finally {
            frameNanos += System.nanoTime() - start;
        }
    }

    // LOCATION_BLOCKS is deprecated in 26.3, yet it is what LevelRenderer binds for terrain in the same version.
    @SuppressWarnings("deprecation")
    private void drawSections() {
        upload();
        final GpuArena a = arena;
        if (!frameReady || a == null) {
            return;
        }
        sectionDistance.clear();
        int maxQuadIndices = 0;
        int maxLineIndices = 0;
        for (final Obj obj : objects.values()) {
            for (final SectionMeshes s : obj.sections.values()) {
                maxQuadIndices = Math.max(maxQuadIndices, s.faceVertices / 4 * 6);
                maxLineIndices = Math.max(maxLineIndices, Math.max(s.nearVertices, s.farVertices) / 4 * 6);
            }
        }
        final RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        final RenderSystem.AutoStorageIndexBuffer lineIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
        final GpuBuffer quadBuffer = quadIndices.getBuffer(Math.max(6, maxQuadIndices));
        final GpuBuffer lineBuffer = lineIndices.getBuffer(Math.max(6, maxLineIndices));
        view.set(RenderSystem.getModelViewMatrixCopy());
        final Minecraft minecraft = Minecraft.getInstance();
        final RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        final GpuTextureView atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        final GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        final GpuTextureView colormapView = colormap().getTextureView();
        final List<Obj> order = new ArrayList<>(objects.size());
        final String looked = lookedAt.get();
        for (final Obj obj : objects.values()) {
            if (shown(obj, looked)) {
                order.add(obj);
            }
        }
        // Faces blend without depth writes, so far objects and sections draw first.
        order.sort((x, y) -> Double.compare(centreDistanceSq(y), centreDistanceSq(x)));
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Bridge retained tier", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            for (int xray = 1; xray >= 0; xray--) {
                final boolean depthTest = xray == 0;
                for (final Kind kind : Kind.values()) {
                    RenderPipeline current = null;
                    for (final Obj obj : order) {
                        if (obj.kind != kind || !inPass(obj.depth, depthTest)) {
                            continue;
                        }
                        final float dim = !depthTest && obj.depth == DepthMode.BOTH ? XRAY_DIM : 1f;
                        current = drawFaces(pass, obj, current, depthTest, dim, a, quadBuffer, quadIndices,
                                atlas, nearest, colormapView, linear);
                    }
                }
                RenderPipeline current = null;
                for (final Obj obj : order) {
                    if (!inPass(obj.depth, depthTest)) {
                        continue;
                    }
                    final float dim = !depthTest && obj.depth == DepthMode.BOTH ? XRAY_DIM : 1f;
                    current = drawLines(pass, obj, current, depthTest, dim, a, lineBuffer, lineIndices);
                }
            }
        }
    }

    private double centreDistanceSq(final Obj obj) {
        final ObjectBounds.Box b = obj.bounds;
        if (b == null) {
            return 0d;
        }
        final double dx = (b.minX() + b.maxX()) / 2 - camX;
        final double dy = (b.minY() + b.maxY()) / 2 - camY;
        final double dz = (b.minZ() + b.maxZ()) / 2 - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns an object's section keys far to near. Sorted again only when
     * the camera enters another section or the object's sections change, so a still camera allocates
     * nothing. Quads inside one section keep their mesh order.
     */
    private long[] farToNear(final Obj obj) {
        final long from = SectionKeys.ofBlock((int) Math.floor(camX), (int) Math.floor(camY), (int) Math.floor(camZ));
        if (!obj.orderStale && obj.orderFrom == from) {
            return obj.order;
        }
        final long[] keys = obj.sections.keySet().toLongArray();
        final int cx = SectionKeys.x(from);
        final int cy = SectionKeys.y(from);
        final int cz = SectionKeys.z(from);
        LongArrays.quickSort(keys, (a, b) -> Long.compare(sectionDistanceSq(b, cx, cy, cz), sectionDistanceSq(a, cx, cy, cz)));
        obj.order = keys;
        obj.orderFrom = from;
        obj.orderStale = false;
        return keys;
    }

    private static long sectionDistanceSq(final long key, final int cx, final int cy, final int cz) {
        final long dx = SectionKeys.x(key) - cx;
        final long dy = SectionKeys.y(key) - cy;
        final long dz = SectionKeys.z(key) - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** {@code visibleWhen}: the distance to the object's centre and lookedAt, this frame. */
    private boolean shown(final Obj obj, final @Nullable String looked) {
        final Visibility v = obj.visibility;
        if (v == null) {
            return true;
        }
        return Traits.shown(v, obj.bounds == null ? Double.NaN : centreDistanceSq(obj), obj.key.equals(looked));
    }

    /**
     * Returns where a retained object's blocks or cells are, once prepared; hit testing uses it in place
     * of the declared origin. Client thread.
     *
     * @param key the object key
     * @return the box, or null when the object is unknown or not prepared yet
     */
    public ObjectBounds.@Nullable Box bounds(final String key) {
        final Obj obj = objects.get(key);
        return obj == null ? null : obj.bounds;
    }

    /** Returns a counter that changes whenever {@link #bounds(String)} could answer differently. */
    public long boundsVersion() {
        return boundsVersion;
    }

    /**
     * Sets where to read the key of the object under the crosshair, for {@code visibleWhen.lookedAt}.
     *
     * @param source the current target key, or null when none
     */
    public void lookedAt(final Supplier<@Nullable String> source) {
        this.lookedAt = source;
    }

    private static boolean inPass(final DepthMode mode, final boolean depthTest) {
        return switch (mode) {
            case TEST -> depthTest;
            case XRAY -> !depthTest;
            case BOTH -> true;
        };
    }

    /**
     * Distance from the camera to a section's box, or a negative value when it is culled. A section
     * is tested once per frame; the passes and objects that share it reuse the answer.
     */
    private double visibleDistance(final long key) {
        final double known = sectionDistance.get(key);
        if (!Double.isNaN(known)) {
            return known;
        }
        final double x0 = SectionKeys.x(key) << 4;
        final double y0 = SectionKeys.y(key) << 4;
        final double z0 = SectionKeys.z(key) << 4;
        final double dx = Math.max(0, Math.max(x0 - camX, camX - (x0 + 16)));
        final double dy = Math.max(0, Math.max(y0 - camY, camY - (y0 + 16)));
        final double dz = Math.max(0, Math.max(z0 - camZ, camZ - (z0 + 16)));
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > MAX_DISTANCE_BLOCKS || !volume.box(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16)) {
            d = -1;
        }
        sectionDistance.put(key, d);
        return d;
    }

    private void transform(final RenderPass pass, final long key, final float r, final float g, final float b,
            final float alpha, final boolean heat, final Obj obj) {
        final float[] uv = heat ? Colormaps.uvTransform(obj.range[0], obj.range[1], obj.colormapRow) : null;
        pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(SectionTransforms.section(view,
                (float) ((SectionKeys.x(key) << 4) - camX), (float) ((SectionKeys.y(key) << 4) - camY),
                (float) ((SectionKeys.z(key) << 4) - camZ), r, g, b, alpha, uv)));
    }

    private @Nullable RenderPipeline drawFaces(final RenderPass pass, final Obj obj, final @Nullable RenderPipeline current,
            final boolean depthTest, final float dim, final GpuArena a, final GpuBuffer indices,
            final RenderSystem.AutoStorageIndexBuffer indexStorage, final GpuTextureView atlas, final GpuSampler nearest,
            final GpuTextureView colormapView, final GpuSampler linear) {
        final RenderPipeline pipeline = switch (obj.kind) {
            case GHOST -> depthTest ? RetainedPipelines.GHOST : RetainedPipelines.GHOST_XRAY;
            case HEAT -> depthTest ? RetainedPipelines.HEAT : RetainedPipelines.HEAT_XRAY;
            case HIGHLIGHT -> RetainedPipelines.faces(depthTest);
        };
        RenderPipeline bound = current;
        for (final long key : farToNear(obj)) {
            final SectionMeshes s = obj.sections.get(key);
            if (s == null || s.faces == null || s.faceVertices == 0) {
                continue;
            }
            final double d = visibleDistance(key);
            if (d < 0 || obj.kind == Kind.HEAT && d > HEATMAP_LOD_BLOCKS || obj.kind == Kind.GHOST && d > GHOST_LOD_BLOCKS) {
                continue;
            }
            if (bound != pipeline) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setIndexBuffer(indices, indexStorage.type());
                if (obj.kind == Kind.GHOST) {
                    pass.setUniform("Sampler0", atlas, nearest);
                } else if (obj.kind == Kind.HEAT) {
                    pass.setUniform("Sampler0", colormapView, linear);
                }
                bound = pipeline;
            }
            switch (obj.kind) {
                case GHOST, HEAT -> transform(pass, key, 1f, 1f, 1f, obj.alpha * dim, obj.kind == Kind.HEAT, obj);
                case HIGHLIGHT -> transform(pass, key, red(obj.argb), green(obj.argb), blue(obj.argb),
                        obj.fill * dim, false, obj);
            }
            pass.setVertexBuffer(0, a.slice(s.faces));
            pass.drawIndexed(s.faceVertices / 4 * 6, 1, 0, 0, 0);
        }
        return bound;
    }

    private @Nullable RenderPipeline drawLines(final RenderPass pass, final Obj obj, final @Nullable RenderPipeline current,
            final boolean depthTest, final float dim, final GpuArena a, final GpuBuffer indices,
            final RenderSystem.AutoStorageIndexBuffer indexStorage) {
        if (obj.kind == Kind.HEAT) {
            return current;
        }
        final RenderPipeline pipeline = RetainedPipelines.lines(depthTest);
        RenderPipeline bound = current;
        for (final Long2ObjectMap.Entry<SectionMeshes> e : obj.sections.long2ObjectEntrySet()) {
            final SectionMeshes s = e.getValue();
            final double d = visibleDistance(e.getLongKey());
            if (d < 0) {
                continue;
            }
            final boolean far = obj.kind == Kind.GHOST && d > GHOST_LOD_BLOCKS;
            final GpuArena.Slot slot = far ? s.far : s.near;
            final int vertices = far ? s.farVertices : s.nearVertices;
            if (slot == null || vertices == 0) {
                continue;
            }
            if (bound != pipeline) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                pass.setIndexBuffer(indices, indexStorage.type());
                bound = pipeline;
            }
            if (obj.kind == Kind.HIGHLIGHT) {
                transform(pass, e.getLongKey(), red(obj.argb), green(obj.argb), blue(obj.argb),
                        alpha(obj.argb) * dim, false, obj);
            } else {
                transform(pass, e.getLongKey(), 1f, 1f, 1f, dim, false, obj);
            }
            pass.setVertexBuffer(0, a.slice(slot));
            pass.drawIndexed(vertices / 4 * 6, 1, 0, 0, 0);
        }
        return bound;
    }

    private static float red(final int argb) {
        return ((argb >> 16) & 0xFF) / 255f;
    }

    private static float green(final int argb) {
        return ((argb >> 8) & 0xFF) / 255f;
    }

    private static float blue(final int argb) {
        return (argb & 0xFF) / 255f;
    }

    private static float alpha(final int argb) {
        return (argb >>> 24) / 255f;
    }

    private DynamicTexture colormap() {
        DynamicTexture t = colormap;
        if (t == null) {
            t = new DynamicTexture(() -> "Bridge heatmap colormaps", Colormaps.WIDTH, Colormaps.ROWS, false);
            final int[] texels = Colormaps.texture();
            for (int y = 0; y < Colormaps.ROWS; y++) {
                for (int x = 0; x < Colormaps.WIDTH; x++) {
                    t.getPixels().setPixel(x, y, texels[y * Colormaps.WIDTH + x]);
                }
            }
            t.upload();
            colormap = t;
        }
        return t;
    }

    /** Releases GPU memory and stops the workers. Render thread, on client stop. */
    public void close() {
        if (current == this) {
            current = null;
        }
        clear();
        workers.shutdownNow();
        final GpuArena a = arena;
        if (a != null) {
            a.close();
            arena = null;
        }
        final DynamicTexture t = colormap;
        if (t != null) {
            t.close();
            colormap = null;
        }
    }
}
