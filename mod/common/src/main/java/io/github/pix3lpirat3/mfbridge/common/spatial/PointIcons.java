package io.github.pix3lpirat3.mfbridge.common.spatial;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pix3lpirat3.mfbridge.common.game.RenderFrame26x;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Draws the icon item of {@code core:point} objects above their marker, facing the camera, in the
 * same collection pass as the shapes. Item render states are resolved once per point and icon and
 * kept until the point disappears. Render thread, which is the client thread in 26.x.
 */
public final class PointIcons {

    /** Icon height above the point, in blocks. */
    private static final float OFFSET = 1.1f;

    /** Icon size, in blocks. */
    private static final float SCALE = 0.6f;

    private record Cached(String icon, ItemStackRenderState state) {
    }

    private final Supplier<@Nullable BridgeCore> core;
    private final Map<String, Cached> states = new HashMap<>();
    private long version = Long.MIN_VALUE;

    /**
     * Creates the icon renderer.
     *
     * @param core the running core, or null while it is down
     */
    public PointIcons(final Supplier<@Nullable BridgeCore> core) {
        this.core = core;
    }

    /**
     * Submits the icons of every visible point.
     *
     * @param frame this frame
     */
    public void render(final RenderFrame frame) {
        final BridgeCore c = core.get();
        final ClientLevel level = Minecraft.getInstance().level;
        if (!(frame instanceof RenderFrame26x f) || c == null || level == null) {
            if (!states.isEmpty()) {
                states.clear();
            }
            return;
        }
        final SpatialIndex index = c.spatial();
        if (index.version() != version) {
            version = index.version();
            states.keySet().removeIf(k -> index.get(k) == null);
        }
        final PoseStack pose = f.poseStack();
        for (final SpatialIndex.Item item : index.all()) {
            if (!(item.data() instanceof PointData p) || p.icon() == null) {
                continue;
            }
            final ItemStackRenderState state = state(item.key(), p.icon(), level);
            if (state.isEmpty()) {
                continue;
            }
            pose.pushPose();
            pose.translate(p.pos().x() - f.cameraX(), p.pos().y() + OFFSET - f.cameraY(), p.pos().z() - f.cameraZ());
            Game.rotate(pose, f.camera().orientation);
            pose.scale(SCALE, SCALE, SCALE);
            state.submit(pose, f.collector(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }

    private ItemStackRenderState state(final String key, final String icon, final ClientLevel level) {
        final Cached cached = states.get(key);
        if (cached != null && cached.icon().equals(icon)) {
            return cached.state();
        }
        final Item known = SlotItems.item(icon);
        final Item item = known == null ? Items.BARRIER : known;
        final ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(state, new ItemStack(item), ItemDisplayContext.FIXED, level,
                null, key.hashCode());
        states.put(key, new Cached(icon, state));
        return state;
    }
}
