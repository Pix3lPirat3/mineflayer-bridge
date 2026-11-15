package io.github.pix3lpirat3.mfbridge.common.display;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.display.logic.ElementGrid;
import io.github.pix3lpirat3.mfbridge.common.display.logic.ElementPointer;
import io.github.pix3lpirat3.mfbridge.common.game.RenderFrame26x;
import io.github.pix3lpirat3.mfbridge.common.render.command.DepthMode;
import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.render.retained.RetainedRenderer;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.sound.BotSounds;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.core.vars.TemplateCache;
import io.github.pix3lpirat3.mfbridge.core.vars.Templates;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.SoundEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Displays, element interactions, world menus and client-only effects.
 *
 * <ul>
 *   <li>{@link #render} draws the current snapshot's displays with vanilla renderers while the game
 *       collects level submits. Nothing enters the client level.
 *   <li>{@link #afterPick} runs right after vanilla's crosshair pick: it raycasts the interactive
 *       elements near the player and, when one is closer than vanilla's target, replaces that target
 *       with a miss so attack and use can never reach a block or entity behind it.
 *   <li>The input methods take clicks, scroll and keys at their place in the arbitration order
 *       (after picks and world menus, before tools); a hit on an element consumes the click.
 * </ul>
 *
 * <p>Client thread only.
 */
public final class Displays {
    private static final int TOOLTIP_ARGB = 0xFFFFFFFF;
    private static final int TOOLTIP_BACKGROUND = 0xA0000000;
    /** Outline of the editable object Shift+click would edit, {@code 0xRRGGBBAA}. */
    private static final int EDIT_HINT_RGBA = 0xFFFFFF90;
    /** Shift+click edit range of objects that are editable but not interactive, blocks. */
    private static final double EDIT_RANGE = 16d;

    private final UiContext ui;
    private final DisplayDrawer drawer = new DisplayDrawer();
    private final DisplayScene scene = new DisplayScene(Displays::dispose);
    private final ElementGrid grid = new ElementGrid();
    private final ArrayList<DisplayNode> gridNodes = new ArrayList<>();
    private final ArrayList<ObjectTargets.Target> gridObjects = new ArrayList<>();
    private final ObjectTargets objects = new ObjectTargets();
    private final TemplateCache templates = new TemplateCache();
    private static final double[] IDENTITY = {0d, 0d, 0d, 1d};
    private final ElementPointer pointer;
    private final WorldMenus menus;
    private final ClientEffects effects;
    private final double[] quaternion = new double[4];
    private final Vector3f centre = new Vector3f();
    private final Quaternionf inverse = new Quaternionf();
    private final Vector3f local = new Vector3f();
    private @Nullable DisplayNode hoveredNode;
    private ObjectTargets.@Nullable Target hoveredObject;
    /** The non-display object under the crosshair, interactive or not, for its hover glow. */
    private ObjectTargets.@Nullable Target aimedObject;

    /** Key of whatever traits-bearing object is under the crosshair, interactive or not. */
    private @Nullable String targetKey;
    /** Whether {@link #targetKey} declares editable fields. */
    private boolean targetEditable;
    private @Nullable String dragButton;
    private double dragPlaneX;
    private double dragPlaneY;
    private double dragPlaneZ;
    private double dragNormalX;
    private double dragNormalY;
    private double dragNormalZ;
    private @Nullable BridgeCore lastCore;

    /**
     * Creates the display layer.
     *
     * @param ui the UI services
     */
    public Displays(final UiContext ui) {
        this.ui = ui;
        this.pointer = new ElementPointer(this::send);
        this.menus = new WorldMenus(ui);
        this.effects = new ClientEffects(ui.sounds());
    }

    /**
     * Returns the world menu controller.
     *
     * @return the controller
     */
    public WorldMenus menus() {
        return menus;
    }

    private static void dispose(final DisplayNode n) {
        n.entity = null;
        n.entityState = null;
        n.entityRenderer = null;
    }

    /**
     * Follows the running core: effects subscribe to the new one and every display of the old one is dropped.
     *
     * @param core the running core, or null
     */
    public void coreChanged(final @Nullable BridgeCore core) {
        if (core == lastCore) {
            return;
        }
        lastCore = core;
        pointer.reset();
        hoveredNode = null;
        hoveredObject = null;
        aimedObject = null;
        targetKey = null;
        scene.clear();
        objects.clear();
        ui.sounds().stopAll();
        effects.coreChanged(core);
        menus.end();
    }

    /** Client tick: particle and sound emitters, placed through their groups, and idle entity animation. */
    public void tick() {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final LocalPlayer player = minecraft.player;
        final BotSounds sounds = ui.sounds();
        if (level == null || player == null) {
            sounds.stopAll();
            return;
        }
        final long now = Util.getMillis();
        final BridgeCore core = ui.core();
        final BlobStore blobs = core == null ? null : core.blobs();
        scene.beginTick(level);
        sounds.beginTick();
        for (final DisplayNode n : scene.nodes()) {
            if (n.kind == DisplayNode.Kind.EMITTER) {
                scene.resolve(n, now, 1d);
                effects.emit(n, level, player, now);
            } else if (n.kind == DisplayNode.Kind.SOUND && n.data instanceof SoundEmitterData d) {
                scene.resolve(n, now, 1d);
                sounds.emitter(n.key, d, n.wx, n.wy, n.wz, blobs);
            } else if (n.kind == DisplayNode.Kind.ENTITY && n.visible) {
                final Entity e = n.entity;
                if (e != null) {
                    // Detached entities never tick; advancing their age keeps idle animations alive.
                    e.tickCount++;
                    effectSwirl(n, e, level);
                }
            }
        }
        sounds.endTick();
    }

    /**
     * Spawns status effect swirl particles around an entity display, at vanilla's rate of one particle
     * every four ticks on average, or every fifteen for invisible entities (26.3 LivingEntity.tickEffects).
     */
    private static void effectSwirl(final DisplayNode n, final Entity e, final ClientLevel level) {
        final List<ParticleOptions> particles = n.effectParticles;
        if (particles.isEmpty() || level.getRandom().nextInt(e.isInvisible() ? 15 : 4) != 0) {
            return;
        }
        final ParticleOptions particle = particles.get(level.getRandom().nextInt(particles.size()));
        final double width = e.getBbWidth() * n.scaleX;
        final double height = e.getBbHeight() * n.scaleY;
        final double x = n.wx + (level.getRandom().nextDouble() - 0.5d) * width;
        final double y = n.wy + level.getRandom().nextDouble() * height;
        final double z = n.wz + (level.getRandom().nextDouble() - 0.5d) * width;
        level.addParticle(particle, x, y, z, 1d, 1d, 1d);
    }

    /**
     * Draws every display for this frame. Called on the render thread during the level submit collection.
     *
     * @param frame the frame
     */
    public void render(final RenderFrame frame) {
        if (!(frame instanceof RenderFrame26x f)) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final BridgeCore core = ui.core();
        if (level == null || core == null) {
            return;
        }
        final long now = Util.getMillis();
        scene.sync(core.renderSnapshot(), now);
        scene.beginFrame(level, f.camera().yRot, f.camera().xRot, f.partialTick());
        menus.frame(scene, f.camera().pos, now);
        final String hovered = pointer.hovered();
        final Variables vars = core.variables();
        draw(scene.nodes(), f, level, now, hovered, vars);
        draw(scene.extra(), f, level, now, hovered, vars);
    }

    private void draw(final List<DisplayNode> nodes, final RenderFrame26x frame, final ClientLevel level, final long now,
            final @Nullable String hovered, final Variables vars) {
        for (int i = 0; i < nodes.size(); i++) {
            final DisplayNode n = nodes.get(i);
            final DisplayFields fields = n.fields;
            if (fields == null || !n.kind.drawn) {
                if (n.kind == DisplayNode.Kind.GROUP) {
                    scene.resolve(n, now, 1d);
                }
                continue;
            }
            n.hovered = n.hovered && n.key.startsWith(WorldMenus.KEY_PREFIX) || n.key.equals(hovered);
            final HoverStyle hover = n.hovered ? fields.hover() : null;
            final double hoverScale = hover != null && hover.scale() != null && hover.scale() > 0f ? hover.scale() : 1d;
            if (n.data instanceof DisplayTextData d && Templates.templated(d.text()) && n.varsVersion != vars.version()) {
                // Templated text rebuilds its lines when a variable changes.
                n.varsVersion = vars.version();
                n.text = templates.resolve(d.text(), n.key, vars);
                n.dirty = true;
            }
            drawer.prepare(n, level);
            scene.resolve(n, now, hoverScale);
            n.visible = drawer.cull(n, frame.camera());
            if (!n.visible || !shown(n, frame.camera().pos.x, frame.camera().pos.y, frame.camera().pos.z)) {
                continue;
            }
            final Integer glow = hover != null && hover.glow() != null ? hover.glow() : fields.glow();
            if (n.failed) {
                continue;
            }
            try {
                drawer.submit(n, frame, level, glow == null ? 0 : glow | 0xFF000000);
            } catch (final RuntimeException e) {
                // A bot-supplied display must never crash the game; skip it until its next revision.
                n.failed = true;
                Constants.LOG.warn("Display {} failed to render and is skipped until it changes", n.key, e);
            }
        }
    }

    /**
     * Runs right after vanilla's crosshair pick:
     * finds the nearest interactive element on the crosshair ray within its range and in front of
     * vanilla's target. On a hit, vanilla's target becomes a miss for this frame, the hover state and
     * tooltip switch, and running drags move.
     */
    public void afterPick() {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;
        final BridgeCore core = ui.core();
        if (player == null || minecraft.level == null || core == null || minecraft.gui.screen() != null) {
            hover(null, null);
            targetKey = null;
            return;
        }
        final Camera camera = minecraft.gameRenderer.mainCamera();
        final Vec3 eye = camera.position();
        final Vector3fc look = camera.forwardVector();
        if (menus.active()) {
            // An active world menu comes before interactive elements.
            menus.pick(eye, look, drawer);
            hover(null, null);
            targetKey = null;
            return;
        }
        final double reach = player.blockInteractionRange();
        double longest = 0d;
        grid.clear();
        gridNodes.clear();
        gridObjects.clear();
        final List<DisplayNode> nodes = scene.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            final DisplayNode n = nodes.get(i);
            final DisplayFields f = n.fields;
            final Interactive spec = f == null ? null : f.interactive();
            if (f == null || !n.kind.drawn || !n.visible || n.resolvedFrame < 0
                    || spec == null && !Traits.targetable(n.traits, n.data)) {
                continue;
            }
            final double range = range(spec, n.traits, reach);
            final double dx = n.wx - eye.x;
            final double dy = n.wy - eye.y;
            final double dz = n.wz - eye.z;
            final double r = DisplayDrawer.radius(n);
            if (dx * dx + dy * dy + dz * dz > (range + r) * (range + r)) {
                continue;
            }
            addBox(n, spec);
            gridNodes.add(n);
            longest = Math.max(longest, range);
        }
        for (final ObjectTargets.Target t : objects.sync(core.renderSnapshot(), RetainedRenderer.current())) {
            final Interactive spec = t.entry().interactive();
            final double range = range(spec, t.entry().traits(), reach);
            final double d2 = t.distanceSq(eye.x, eye.y, eye.z);
            // Distance rules hide an object from hit testing too; lookedAt is what this raycast decides.
            if (!Traits.targetable(t.entry().traits(), t.entry().data()) || !Traits.shown(Traits.visibleWhen(t.entry().traits()), d2, true)) {
                continue;
            }
            final double hx = (t.maxX() - t.minX()) / 2d;
            final double hy = (t.maxY() - t.minY()) / 2d;
            final double hz = (t.maxZ() - t.minZ()) / 2d;
            final double r = Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (d2 > (range + r) * (range + r)) {
                continue;
            }
            grid.add(t.cx(), t.cy(), t.cz(), IDENTITY, hx, hy, hz, 0d, 0d, 0d);
            gridObjects.add(t);
            longest = Math.max(longest, range);
        }
        DisplayNode hitNode = null;
        ObjectTargets.Target hitObject = null;
        String key = null;
        Interactive hitSpec = null;
        boolean editable = false;
        if (grid.size() > 0) {
            final int id = grid.raycast(eye.x, eye.y, eye.z, look.x(), look.y(), look.z(), longest);
            if (id >= 0) {
                final DisplayNode n = id < gridNodes.size() ? gridNodes.get(id) : null;
                final ObjectTargets.Target t = n == null ? gridObjects.get(id - gridNodes.size()) : null;
                final Interactive spec = n != null ? n.fields == null ? null : n.fields.interactive() : t == null ? null : t.entry().interactive();
                final ObjectTraits traits = n != null ? n.traits : t == null ? null : t.entry().traits();
                final HitResult vanilla = minecraft.hitResult;
                final double vanillaDistance = vanilla == null || vanilla.getType() == HitResult.Type.MISS
                        ? Double.POSITIVE_INFINITY : vanilla.getLocation().distanceTo(eye);
                if (grid.hitDistance() <= range(spec, traits, reach) && grid.hitDistance() < vanillaDistance) {
                    hitNode = n;
                    hitObject = t;
                    hitSpec = spec;
                    key = n != null ? n.key : t == null ? null : t.entry().key();
                    editable = !Traits.editable(traits).isEmpty();
                }
            }
        }
        targetKey = key;
        targetEditable = editable;
        aimedObject = hitObject;
        // Only interactive objects, or an editable one under Shift, take the click from vanilla.
        if (hitSpec != null || editable && minecraft.hasShiftDown()) {
            final Vec3 at = new Vec3(grid.hitX(), grid.hitY(), grid.hitZ());
            // A hit cancels vanilla's crosshair target, so no attack or interact packet can follow.
            minecraft.hitResult = BlockHitResult.miss(at, Direction.getApproximateNearest(-look.x(), -look.y(), -look.z()),
                    BlockPos.containing(at));
            minecraft.crosshairPickEntity = null;
        }
        hover(hitSpec == null ? null : hitNode, hitSpec == null ? null : hitObject);
        if (pointer.dragging()) {
            dragMove(eye, look);
        }
    }

    private void addBox(final DisplayNode n, final @Nullable Interactive spec) {
        drawer.boxCentre(n, centre);
        quaternion[0] = n.rotation.x;
        quaternion[1] = n.rotation.y;
        quaternion[2] = n.rotation.z;
        quaternion[3] = n.rotation.w;
        // Editable-only objects have no interactive spec, so they use the visual bounds.
        final Vec3d custom = spec == null ? null : spec.hitbox();
        final double min = ObjectTargets.MIN_EXTENT / 2d;
        final double hx = custom != null ? custom.x() / 2d : Math.max(min, n.halfX * Math.abs(n.scaleX));
        final double hy = custom != null ? custom.y() / 2d : Math.max(min, n.halfY * Math.abs(n.scaleY));
        final double hz = custom != null ? custom.z() / 2d : Math.max(min, n.halfZ * Math.abs(n.scaleZ));
        grid.add(n.wx + centre.x, n.wy + centre.y, n.wz + centre.z, quaternion, hx, hy, hz,
                -n.boxX * n.scaleX, -n.boxY * n.scaleY, -n.boxZ * n.scaleZ);
    }

    /**
     * Returns how far an object can be targeted from: an interactive object's {@code maxDistance}, else
     * the block reach, or at least {@link #EDIT_RANGE} for objects that are only editable.
     */
    private static double range(final @Nullable Interactive spec, final @Nullable ObjectTraits traits, final double reach) {
        if (spec != null) {
            return spec.maxDistance() != null && spec.maxDistance() > 0f ? spec.maxDistance() : reach;
        }
        return Traits.editable(traits).isEmpty() ? reach : Math.max(reach, EDIT_RANGE);
    }

    private void hover(final @Nullable DisplayNode node, final ObjectTargets.@Nullable Target object) {
        if (!pointer.dragging()) {
            hoveredNode = node;
            hoveredObject = object;
        }
        pointer.hover(node != null ? node.key : object != null ? object.entry().key() : null);
    }

    /** {@code visibleWhen}: the distance range and lookedAt, for this frame. */
    private boolean shown(final DisplayNode n, final double camX, final double camY, final double camZ) {
        final var v = Traits.visibleWhen(n.traits);
        if (!Traits.perFrame(v)) {
            return true;
        }
        final double dx = n.wx - camX;
        final double dy = n.wy - camY;
        final double dz = n.wz - camZ;
        return Traits.shown(v, dx * dx + dy * dy + dz * dz, n.key.equals(targetKey));
    }

    /**
     * Returns the object under the crosshair that has traits worth targeting (interactive, editable,
     * bound actions or lookedAt), whether or not it takes clicks from vanilla.
     *
     * @return its key, or null
     */
    public @Nullable String targetKey() {
        return targetKey;
    }

    /**
     * Adds this frame's outlines: the hovered object's hover glow ({@code
     * hover.glow} for kinds other than displays), the editable object under the crosshair while Shift
     * is held, and the selected object.
     *
     * @param out the frame's command list
     * @param selected the selected object's key, or null
     * @param selectedRgba outline colour of the selection, {@code 0xRRGGBBAA}
     */
    public void outlines(final List<DrawCommand> out, final @Nullable String selected, final int selectedRgba) {
        final ObjectTargets.Target h = pointer.dragging() ? hovering() ? hoveredObject : null : aimedObject;
        final var hover = h == null ? null : h.entry().hover();
        if (h != null && hover != null && hover.glow() != null) {
            final int argb = hover.glow();
            outline(out, h.minX(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ(), (argb << 8) | 0xFF);
        }
        final String target = targetKey;
        if (target != null && targetEditable && !target.equals(selected) && Minecraft.getInstance().hasShiftDown()) {
            outline(out, target, EDIT_HINT_RGBA);
        }
        if (selected != null) {
            outline(out, selected, selectedRgba);
        }
    }

    private void outline(final List<DrawCommand> out, final String key, final int rgba) {
        final ObjectTargets.Target t = objects.get(key);
        if (t != null) {
            outline(out, t.minX(), t.minY(), t.minZ(), t.maxX(), t.maxY(), t.maxZ(), rgba);
            return;
        }
        final DisplayNode n = scene.byKey(key);
        if (n != null && n.resolvedFrame >= 0) {
            final double hx = n.halfX * Math.abs(n.scaleX);
            final double hy = n.halfY * Math.abs(n.scaleY);
            final double hz = Math.max(n.halfZ * Math.abs(n.scaleZ), hx);
            final double cy = n.wy + n.boxY * n.scaleY;
            outline(out, n.wx - Math.max(hx, hz), cy - hy, n.wz - Math.max(hx, hz), n.wx + Math.max(hx, hz), cy + hy,
                    n.wz + Math.max(hx, hz), rgba);
        }
    }

    private static void outline(final List<DrawCommand> out, final double x0, final double y0, final double z0, final double x1,
            final double y1, final double z1, final int rgba) {
        final double g = 0.02d;
        out.add(new DrawCommand.Box(new io.github.pix3lpirat3.mfbridge.common.seam.Vec3d(x0 - g, y0 - g, z0 - g),
                new io.github.pix3lpirat3.mfbridge.common.seam.Vec3d(x1 + g, y1 + g, z1 + g), rgba, 0f, DepthMode.BOTH));
    }

    /** Drags follow the ray across the plane through the grab point that faced the camera when the drag began. */
    private void dragMove(final Vec3 eye, final Vector3fc look) {
        if (hoveredNode == null && hoveredObject == null) {
            return;
        }
        final double denom = look.x() * dragNormalX + look.y() * dragNormalY + look.z() * dragNormalZ;
        if (Math.abs(denom) < 1e-6) {
            return;
        }
        final double t = ((dragPlaneX - eye.x) * dragNormalX + (dragPlaneY - eye.y) * dragNormalY
                + (dragPlaneZ - eye.z) * dragNormalZ) / denom;
        if (t < 0d) {
            return;
        }
        localOf(eye.x + look.x() * t, eye.y + look.y() * t, eye.z + look.z() * t);
        pointer.move(local.x, local.y, local.z);
    }

    /** Element-local position of a world point: rotated into a display's frame, or relative to an object's box centre. */
    private void localOf(final double x, final double y, final double z) {
        final DisplayNode n = hoveredNode;
        final ObjectTargets.Target o = hoveredObject;
        if (n != null) {
            toLocal(n, x, y, z);
        } else if (o != null) {
            local.set((float) (x - o.cx()), (float) (y - o.cy()), (float) (z - o.cz()));
        }
    }

    private void toLocal(final DisplayNode n, final double x, final double y, final double z) {
        local.set((float) (x - n.wx), (float) (y - n.wy), (float) (z - n.wz));
        inverse.set(n.rotation).conjugate().transform(local);
    }

    private void send(final String element, final ElementInput input, final @Nullable Float delta, final @Nullable String key,
            final boolean hasLocal, final double lx, final double ly, final double lz) {
        if (element.startsWith(WorldMenus.KEY_PREFIX)) {
            return;
        }
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final int modifiers = (minecraft.hasShiftDown() ? 1 : 0) | (minecraft.hasControlDown() ? 2 : 0) | (minecraft.hasAltDown() ? 4 : 0);
        core.elementEvent(element, input, delta, key, hasLocal ? new Vec3d(lx, ly, lz) : null, modifiers);
    }

    /**
     * Returns whether the crosshair is on an interactive element, or a drag on one is running.
     *
     * @return true while clicks belong to an element
     */
    public boolean hovering() {
        return pointer.hovered() != null;
    }

    /**
     * Takes a press of attack, use or pick-block while an element is hovered.
     *
     * @param input {@link ElementInput#PRIMARY}, {@link ElementInput#SECONDARY} or {@link ElementInput#MIDDLE}
     * @param button the key name of the pressed button, to match its release
     * @return whether the press was consumed
     */
    public boolean press(final ElementInput input, final String button) {
        if (hoveredNode == null && hoveredObject == null || !hovering()) {
            return false;
        }
        localOf(grid.hitX(), grid.hitY(), grid.hitZ());
        final boolean wasDragging = pointer.dragging();
        pointer.press(input, local.x, local.y, local.z);
        if (!wasDragging && pointer.dragging()) {
            dragButton = button;
            final Vector3fc look = Minecraft.getInstance().gameRenderer.mainCamera().forwardVector();
            dragPlaneX = grid.hitX();
            dragPlaneY = grid.hitY();
            dragPlaneZ = grid.hitZ();
            dragNormalX = -look.x();
            dragNormalY = -look.y();
            dragNormalZ = -look.z();
        }
        return true;
    }

    /**
     * Reports the release of a consumed button; the button that started a drag ends it.
     *
     * @param button the key name of the released button
     */
    public void release(final String button) {
        if (button.equals(dragButton)) {
            dragButton = null;
            pointer.release();
        }
    }

    /**
     * Offers scroll steps to the hovered element; only elements that declare scroll take them, so the
     * hotbar keeps scrolling otherwise.
     *
     * @param steps whole scroll steps, positive up
     * @return whether the element took them
     */
    public boolean scroll(final int steps) {
        return declares(ElementInput.SCROLL, null) && pointer.scroll(steps);
    }

    /**
     * Returns whether the hovered element declares scroll input.
     *
     * @return true when scroll belongs to the element
     */
    public boolean takesScroll() {
        return declares(ElementInput.SCROLL, null);
    }

    /**
     * Offers a key press to the hovered element; only keys it declares are taken,
     * and only while hovering.
     *
     * @param key vanilla key name
     * @return whether the element took the key
     */
    public boolean key(final String key) {
        return declares(ElementInput.KEY, key) && pointer.key(key);
    }

    private @Nullable Interactive hoveredInteractive() {
        if (!hovering()) {
            return null;
        }
        final DisplayNode n = hoveredNode;
        final ObjectTargets.Target o = hoveredObject;
        return n != null ? n.fields == null ? null : n.fields.interactive() : o == null ? null : o.entry().interactive();
    }

    private boolean declares(final ElementInput input, final @Nullable String key) {
        final Interactive spec = hoveredInteractive();
        if (spec == null || !spec.inputs().contains(input)) {
            return false;
        }
        final List<String> keys = spec.keys();
        return key == null || keys != null && keys.contains(key);
    }

    /**
     * Draws the hovered element's tooltip and the world menu hint.
     *
     * @param graphics the frame's GUI extractor
     */
    public void renderHud(final GuiGraphicsExtractor graphics) {
        final Font font = Minecraft.getInstance().font;
        final Interactive spec = hoveredInteractive();
        final String tooltip = spec == null ? null : spec.tooltip();
        if (tooltip != null && !tooltip.isEmpty()) {
            line(graphics, font, tooltip, graphics.guiHeight() / 2 + 12);
        }
        final String hint = menus.hint();
        if (hint != null) {
            line(graphics, font, hint, graphics.guiHeight() - 100);
        }
    }

    private static void line(final GuiGraphicsExtractor graphics, final Font font, final String text, final int y) {
        final int w = font.width(text);
        final int x = graphics.guiWidth() / 2 - w / 2;
        graphics.fill(x - 3, y - 2, x + w + 3, y + 9, TOOLTIP_BACKGROUND);
        graphics.text(font, text, x, y, TOOLTIP_ARGB);
    }
}
