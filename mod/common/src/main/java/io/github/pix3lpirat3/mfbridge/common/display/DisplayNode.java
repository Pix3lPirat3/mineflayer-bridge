package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.display.logic.Animation;
import io.github.pix3lpirat3.mfbridge.common.display.logic.BurstClock;
import io.github.pix3lpirat3.mfbridge.common.display.logic.DisplayPose;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayBlockData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTransform;
import io.github.pix3lpirat3.mfbridge.protocol.core.GroupData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ParticleEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SoundEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import org.jspecify.annotations.Nullable;

/**
 * Client state of one display, group or particle emitter: its data at the current revision, the
 * interpolated pose, the transform resolved for this frame and the vanilla render objects built for
 * it (text lines, item and block render states, the detached entity). Rebuilt objects are cached per
 * revision, so a steady display allocates nothing per frame. Client thread only.
 */
final class DisplayNode {
    /** What a node draws. */
    enum Kind {
        TEXT(true), ITEM(true), BLOCK(true), ENTITY(true), GROUP(false), EMITTER(false), SOUND(false);

        /** Whether the kind is drawn and hit-tested; groups and emitters only take part in placement. */
        final boolean drawn;

        Kind(final boolean drawn) {
            this.drawn = drawn;
        }
    }

    private static final float[] NO_ROTATION = new float[0];

    final String key;
    final String owner;
    /** {@code <owner>/<extension>/<layer>/}, the prefix group references resolve against, or null. */
    final @Nullable String layerPrefix;
    final Kind kind;
    long rev = Long.MIN_VALUE;
    Object data;
    @Nullable DisplayFields fields;
    /** Object traits at the current revision, or null. */
    @Nullable ObjectTraits traits;
    /** Variables version the text lines were built with; templated text rebuilds when it changes. */
    long varsVersion = -1L;
    /** Text with its templates resolved, or null to draw the data's text as written. */
    @Nullable String text;
    /** Full key of the parent group, or null. */
    @Nullable String groupKey;
    /** Set when the revision changed and the vanilla render objects must be rebuilt. */
    boolean dirty = true;

    final DisplayPose from = new DisplayPose();
    final DisplayPose to = new DisplayPose();
    final DisplayPose pose = new DisplayPose();
    long interpolationStartMs;
    int interpolationMs;
    private boolean posed;

    final Animation.Sample animation = new Animation.Sample();
    int resolvedFrame = -1;
    boolean resolving;
    /** Whether the node passed distance and frustum culling this frame. */
    boolean visible;
    double wx;
    double wy;
    double wz;
    final Quaternionf rotation = new Quaternionf();
    float scaleX = 1f;
    float scaleY = 1f;
    float scaleZ = 1f;
    boolean hovered;
    /** Set when drawing threw; cleared by the next revision so a fixed display renders again. */
    boolean failed;

    List<FormattedCharSequence> lines = List.of();
    int[] lineWidths = new int[0];
    int textWidth;
    @Nullable String hoverText;
    List<FormattedCharSequence> hoverLines = List.of();
    int[] hoverLineWidths = new int[0];
    int hoverTextWidth;
    @Nullable FormattedCharSequence itemName;
    int itemNameWidth;
    @Nullable ItemStackRenderState item;
    @Nullable BlockModelRenderState block;
    @Nullable Entity entity;
    @Nullable EntityRenderer<Entity, EntityRenderState> entityRenderer;
    @Nullable EntityRenderState entityState;
    /** Level the detached entity was created for; a new level needs a new entity. */
    @Nullable Object entityLevel;
    /** Appearance the detached entity was created with; a change needs a new entity. */
    @Nullable Object entityLook;
    /** Status effect swirl particles of an entity display. */
    List<ParticleOptions> effectParticles = List.of();

    /** Hit box centre relative to the display origin, before scale, blocks. */
    double boxX;
    double boxY;
    double boxZ;
    /** Hit box half extents before scale, blocks. */
    double halfX = 0.25d;
    double halfY = 0.25d;
    double halfZ = 0.25d;
    @Nullable AABB cullBox;

    final BurstClock bursts = new BurstClock();
    long lastEmitMs = Long.MIN_VALUE;
    @Nullable ParticleOptions particle;
    @Nullable String particleProblem;

    DisplayNode(final String key, final String owner, final @Nullable String layerPrefix, final Kind kind, final Object data) {
        this.key = key;
        this.owner = owner;
        this.layerPrefix = layerPrefix;
        this.kind = kind;
        this.data = data;
    }

    /** Returns the kind for decoded data, or null when it is not a display, group or emitter. */
    static @Nullable Kind kindOf(final @Nullable Object data) {
        return switch (data) {
            case DisplayTextData d -> Kind.TEXT;
            case DisplayItemData d -> Kind.ITEM;
            case DisplayBlockData d -> Kind.BLOCK;
            case DisplayEntityData d -> Kind.ENTITY;
            case GroupData g -> Kind.GROUP;
            case ParticleEmitterData e -> Kind.EMITTER;
            case SoundEmitterData s -> Kind.SOUND;
            case null, default -> null;
        };
    }

    /**
     * Takes a new revision. The pose interpolates from where the display is now to the new target
     * over {@code interpolateMs}; the first revision snaps.
     */
    void update(final Object newData, final @Nullable ObjectTraits newTraits, final long newRev, final long nowMs) {
        data = newData;
        traits = newTraits;
        varsVersion = -1L;
        text = null;
        rev = newRev;
        failed = false;
        dirty = true;
        final DisplayFields f = DisplayFields.of(newData, newTraits);
        fields = f;
        if (f == null) {
            pose.x = 0d;
            pose.y = 0d;
            pose.z = 0d;
            groupKey = null;
            return;
        }
        final String group = f.group();
        groupKey = group == null || layerPrefix == null ? null : layerPrefix + group;
        final DisplayTransform t = f.transform();
        final Vec3d offset = t == null ? null : t.offset();
        final Vec3d scale = t == null ? null : t.scale();
        final float[] rotation = t == null || t.rotation() == null ? NO_ROTATION : t.rotation();
        final double s = f.scale() > 0f ? f.scale() : 1d;
        final double[] posArr = {f.pos().x(), f.pos().y(), f.pos().z()};
        final double[] offArr = offset == null ? new double[3] : new double[] {offset.x(), offset.y(), offset.z()};
        final double[] scaleArr = scale == null ? new double[] {s, s, s} : new double[] {scale.x() * s, scale.y() * s, scale.z() * s};
        if (posed && f.interpolateMs() > 0) {
            sample(nowMs);
            from.copy(pose);
            to.set(posArr, offArr, rotation, scaleArr);
            interpolationStartMs = nowMs;
            interpolationMs = f.interpolateMs();
        } else {
            to.set(posArr, offArr, rotation, scaleArr);
            from.copy(to);
            pose.copy(to);
            interpolationMs = 0;
        }
        posed = true;
    }

    /** Moves {@link #pose} along the running interpolation. */
    void sample(final long nowMs) {
        if (interpolationMs > 0) {
            final double t = DisplayPose.progress(interpolationStartMs, interpolationMs, nowMs);
            pose.lerp(from, to, t);
            if (t >= 1d) {
                interpolationMs = 0;
            }
        }
    }

    /** Places a node that is not backed by a store object, such as a world menu entry, without interpolation. */
    void place(final double x, final double y, final double z, final double scale) {
        to.x = x;
        to.y = y;
        to.z = z;
        to.sx = scale;
        to.sy = scale;
        to.sz = scale;
        pose.copy(to);
        from.copy(to);
        interpolationMs = 0;
        posed = true;
    }
}
