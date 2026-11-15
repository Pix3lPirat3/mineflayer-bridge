package io.github.pix3lpirat3.mfbridge.common.ext.map;

import net.minecraft.client.Camera;
import org.joml.Vector3fc;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.ext.map.wire.MapOrderType;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Selecting bots from the player's own view: the cursor is free over the world, a drag boxes the bots
 * on screen, a click on a bot toggles it, and right-click gives the selected bots an order where the
 * cursor points, as on the map: a move to a block (Shift queues it) or the actions bots offer for an
 * entity. S stops them. The selection is the map's. Client thread only.
 */
final class WorldSelectScreen extends ChildScreen {

    /** How far a right-click reaches, in blocks. */
    private static final double RANGE = 192d;
    private static final double DRAG_THRESHOLD = 4;
    private static final double PICK_RADIUS = 12;
    private static final int SELECTED_ARGB = 0xFFFFD54F;
    private static final int BOT_ARGB = 0xFF4FC3F7;
    private static final int BOX_ARGB = 0x30FFD54F;

    private final MapExtension map;
    private final Set<String> selected;
    private double pressX = Double.NaN;
    private double pressY;
    private double dragX;
    private double dragY;
    private boolean boxing;

    WorldSelectScreen(final MapExtension map) {
        super(Component.translatable("mineflayer_bridge_map.world_select"), null);
        this.map = map;
        this.selected = map.selection();
    }

    /** Camera axes and field of view for this frame, to place world points on the screen. */
    private record View(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up, double tanHalf, double aspect) {
    }

    private View view() {
        final Camera camera = minecraft.gameRenderer.mainCamera();
        final Vector3fc look = camera.forwardVector();
        final Vec3 forward = new Vec3(look.x(), look.y(), look.z()).normalize();
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) {
            right = new Vec3(1, 0, 0);
        }
        right = right.normalize();
        final Vec3 up = right.cross(forward).normalize();
        final double fov = Math.toRadians(minecraft.options.fov().get());
        return new View(camera.position(), forward, right, up, Math.tan(fov / 2), width / (double) Math.max(1, height));
    }

    /** Where a world point shows on screen, or null when it is behind the camera. */
    private static double @Nullable [] screen(final View v, final Vec3 p, final int width, final int height) {
        final Vec3 d = p.subtract(v.eye());
        final double z = d.dot(v.forward());
        if (z < 0.1) {
            return null;
        }
        final double nx = d.dot(v.right()) / (z * v.tanHalf() * v.aspect());
        final double ny = d.dot(v.up()) / (z * v.tanHalf());
        return new double[] {(nx + 1) / 2 * width, (1 - ny) / 2 * height};
    }

    /** The world direction through a screen point. */
    private Vec3 ray(final View v, final double x, final double y) {
        final double nx = x / width * 2 - 1;
        final double ny = 1 - y / height * 2;
        return v.forward().add(v.right().scale(nx * v.tanHalf() * v.aspect())).add(v.up().scale(ny * v.tanHalf())).normalize();
    }

    /** A bot's on-screen point: the middle of its body. */
    private static Vec3 body(final MapExtension.Bot b) {
        return new Vec3(b.pos().x(), b.pos().y() + 1, b.pos().z());
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            pressX = event.x();
            pressY = event.y();
            dragX = pressX;
            dragY = pressY;
            boxing = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            order(event.x(), event.y(), event.hasShiftDown());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && !Double.isNaN(pressX)) {
            dragX = event.x();
            dragY = event.y();
            boxing |= Math.abs(dragX - pressX) > DRAG_THRESHOLD || Math.abs(dragY - pressY) > DRAG_THRESHOLD;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || Double.isNaN(pressX)) {
            return super.mouseReleased(event);
        }
        if (!event.hasShiftDown()) {
            selected.clear();
        }
        final View v = view();
        if (boxing) {
            final double x0 = Math.min(pressX, dragX);
            final double x1 = Math.max(pressX, dragX);
            final double y0 = Math.min(pressY, dragY);
            final double y1 = Math.max(pressY, dragY);
            for (final MapExtension.Bot b : map.bots()) {
                final double[] s = screen(v, body(b), width, height);
                if (s != null && s[0] >= x0 && s[0] <= x1 && s[1] >= y0 && s[1] <= y1) {
                    selected.add(b.owner());
                }
            }
        } else {
            final MapExtension.Bot hit = botAt(v, event.x(), event.y());
            if (hit != null && !selected.remove(hit.owner())) {
                selected.add(hit.owner());
            }
        }
        pressX = Double.NaN;
        boxing = false;
        return true;
    }

    private MapExtension.@Nullable Bot botAt(final View v, final double x, final double y) {
        MapExtension.Bot best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (final MapExtension.Bot b : map.bots()) {
            final double[] s = screen(v, body(b), width, height);
            if (s == null) {
                continue;
            }
            final double d = (s[0] - x) * (s[0] - x) + (s[1] - y) * (s[1] - y);
            if (d <= bestD) {
                best = b;
                bestD = d;
            }
        }
        return best;
    }

    /** Right-click: the actions bots offer for an entity under the cursor, else a move to the block there. */
    private void order(final double x, final double y, final boolean queue) {
        if (minecraft.level == null || minecraft.getCameraEntity() == null) {
            return;
        }
        final View v = view();
        final Vec3 from = v.eye();
        final Vec3 to = from.add(ray(v, x, y).scale(RANGE));
        final Entity viewer = minecraft.getCameraEntity();
        final BlockHitResult block = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        final double reachSq = block.getType() == HitResult.Type.MISS ? RANGE * RANGE : block.getLocation().distanceToSqr(from);
        final EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(viewer, from, to, new AABB(from, to).inflate(1),
                e -> e != viewer && !e.isSpectator() && e.isPickable(), reachSq);
        if (entityHit != null && !queue) {
            final ClientBridge bridge = map.bridge();
            if (bridge != null && bridge.openActionMenu(entityHit.getEntity(), null, null, entityHit.getEntity().getName().getString(),
                    selectedNames(), this)) {
                return;
            }
        }
        if (block.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (selected.isEmpty()) {
            map.toast(I18n.get("mineflayer_bridge_map.select_first"));
            return;
        }
        final BlockPos stand = block.getBlockPos().relative(block.getDirection());
        map.order(List.copyOf(selected), MapOrderType.MOVE, new Vec3d(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), queue);
    }

    private List<String> selectedNames() {
        final List<String> out = new ArrayList<>();
        for (final MapExtension.Bot b : map.bots()) {
            if (selected.contains(b.owner())) {
                out.add(b.name());
            }
        }
        return out;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.key() == InputConstants.KEY_S && !selected.isEmpty()) {
            map.order(List.copyOf(selected), MapOrderType.STOP, null, false);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        // The world shows through, unblurred.
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
        final View v = view();
        for (final MapExtension.Bot b : map.bots()) {
            final double[] s = screen(v, body(b), width, height);
            if (s == null) {
                continue;
            }
            final boolean on = selected.contains(b.owner());
            final int x = (int) s[0];
            final int y = (int) s[1];
            final int c = on ? SELECTED_ARGB : BOT_ARGB;
            outline(g, x - 8, y - 14, x + 8, y + 14, c);
            g.centeredText(font, b.name(), x, y - 26, c);
        }
        if (boxing) {
            final int x0 = (int) Math.min(pressX, dragX);
            final int y0 = (int) Math.min(pressY, dragY);
            final int x1 = (int) Math.max(pressX, dragX);
            final int y1 = (int) Math.max(pressY, dragY);
            g.fill(x0, y0, x1, y1, BOX_ARGB);
            outline(g, x0, y0, x1, y1, SELECTED_ARGB);
        }
        final String help = selected.isEmpty() ? I18n.get("mineflayer_bridge_map.world_select.help.none")
                : I18n.get("mineflayer_bridge_map.world_select.help.selected", selected.size());
        g.centeredText(font, help, width / 2, height - 40, UiColors.LABEL);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private static void outline(final GuiGraphicsExtractor g, final int x0, final int y0, final int x1, final int y1, final int argb) {
        g.fill(x0, y0, x1, y0 + 1, argb);
        g.fill(x0, y1 - 1, x1, y1, argb);
        g.fill(x0, y0, x0 + 1, y1, argb);
        g.fill(x1 - 1, y0, x1, y1, argb);
    }
}
