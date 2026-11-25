package io.github.pix3lpirat3.mfbridge.common.display;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Axis;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.game.RenderFrame26x;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotItems;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.common.version.Versions;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayBlockData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws display nodes with vanilla renderers through the 26.3 submit path: text
 * through the text and text-background submits, items through {@link ItemStackRenderState}, blocks
 * (including special models such as chests, signs, banners and heads) through {@link
 * BlockModelRenderState}, and entities through the entity render dispatcher with a cached render
 * state. The pose conventions follow vanilla display entities (26.3 {@code DisplayRenderer}), so a
 * display looks like the equivalent vanilla display entity. Nothing is added to the client level.
 *
 * <p>Render objects are rebuilt only when a node's revision changed; entity render states are
 * extracted only for visible entities. Client (render) thread only.
 */
final class DisplayDrawer {
    /** Vanilla text display scale: one font pixel is 1/40 of a block. */
    private static final float TEXT_SCALE = 0.025f;
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_ARGB = 0xFFFFFFFF;
    private static final float ITEM_NAME_OFFSET = 0.45f;

    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final Vector3f scratch = new Vector3f();
    private final Quaternionf inverse = new Quaternionf();
    private @Nullable BlockModelResolver blockModels;

    DisplayDrawer() {
    }

    /** Rebuilds the vanilla render objects of a node whose revision changed. */
    void prepare(final DisplayNode n, final ClientLevel level) {
        if (!n.dirty && (n.kind != DisplayNode.Kind.ENTITY || n.entityLevel == level)) {
            return;
        }
        n.dirty = false;
        final Minecraft minecraft = Minecraft.getInstance();
        switch (n.data) {
            case DisplayTextData d -> prepareText(n, d, minecraft.font);
            case DisplayItemData d -> prepareItem(n, d, minecraft, level);
            case DisplayBlockData d -> prepareBlock(n, d, minecraft);
            case DisplayEntityData d -> prepareEntity(n, d, level);
            default -> {
            }
        }
        final HoverStyle hover = n.fields == null ? null : n.fields.hover();
        final String hoverText = hover == null ? null : hover.text();
        n.hoverText = hoverText;
        if (hoverText != null && n.data instanceof DisplayTextData d) {
            n.hoverLines = List.copyOf(minecraft.font.split(Component.literal(hoverText), Math.max(1, d.lineWidth())));
            n.hoverLineWidths = widths(minecraft.font, n.hoverLines);
            n.hoverTextWidth = max(n.hoverLineWidths);
        }
        n.cullBox = null;
    }

    private static void prepareText(final DisplayNode n, final DisplayTextData d, final Font font) {
        final String text = n.text != null ? n.text : d.text();
        n.lines = List.copyOf(font.split(Component.literal(text), Math.max(1, d.lineWidth())));
        n.lineWidths = widths(font, n.lines);
        n.textWidth = max(n.lineWidths);
        textBox(n, n.textWidth, n.lines.size());
    }

    /** The text box rises from the anchor and is centred on it horizontally, as in vanilla text displays. */
    private static void textBox(final DisplayNode n, final int width, final int lineCount) {
        final int height = Math.max(1, lineCount * LINE_HEIGHT - 1);
        n.boxX = 0d;
        n.boxY = height * TEXT_SCALE / 2d;
        n.boxZ = 0d;
        n.halfX = (width + 2) * TEXT_SCALE / 2d;
        n.halfY = (height + 2) * TEXT_SCALE / 2d;
        n.halfZ = 0.05d;
    }

    private static int[] widths(final Font font, final List<FormattedCharSequence> lines) {
        final int[] out = new int[lines.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = font.width(lines.get(i));
        }
        return out;
    }

    private static int max(final int[] values) {
        int m = 0;
        for (final int v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static void prepareItem(final DisplayNode n, final DisplayItemData d, final Minecraft minecraft, final ClientLevel level) {
        ItemStackRenderState state = n.item;
        if (state == null) {
            state = new ItemStackRenderState();
            n.item = state;
        }
        final ItemStack stack = SlotItems.stack(d.item());
        minecraft.getItemModelResolver().updateForTopItem(state, stack, itemContext(d.mode()), level, null, n.key.hashCode());
        final net.minecraft.network.chat.Component name = SlotItems.name(d.item());
        n.itemName = name == null ? null : name.getVisualOrderText();
        n.itemNameWidth = name == null ? 0 : minecraft.font.width(name);
        n.boxX = 0d;
        n.boxY = 0d;
        n.boxZ = 0d;
        n.halfX = 0.3d;
        n.halfY = 0.3d;
        n.halfZ = 0.3d;
    }

    /** Item display modes {@code ground | fixed | head | gui}. */
    static ItemDisplayContext itemContext(final String mode) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "fixed" -> ItemDisplayContext.FIXED;
            case "head" -> ItemDisplayContext.HEAD;
            case "gui" -> ItemDisplayContext.GUI;
            default -> ItemDisplayContext.GROUND;
        };
    }

    private void prepareBlock(final DisplayNode n, final DisplayBlockData d, final Minecraft minecraft) {
        BlockModelResolver resolver = blockModels;
        if (resolver == null) {
            resolver = new BlockModelResolver(minecraft.getModelManager());
            blockModels = resolver;
        }
        BlockModelRenderState state = n.block;
        if (state == null) {
            state = new BlockModelRenderState();
            n.block = state;
        }
        resolver.update(state, blockState(d.state()), DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
        n.boxX = 0.5d;
        n.boxY = 0.5d;
        n.boxZ = 0.5d;
        n.halfX = 0.5d;
        n.halfY = 0.5d;
        n.halfZ = 0.5d;
    }

    /** Parses a block state such as {@code minecraft:chest[facing=north]}; unknown states show as a barrier. */
    static BlockState blockState(final String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (final CommandSyntaxException e) {
            Constants.LOG.debug("Block display state '{}' is invalid: {}", text, e.getMessage());
            return Blocks.BARRIER.defaultBlockState();
        }
    }

    private static void prepareEntity(final DisplayNode n, final DisplayEntityData d, final ClientLevel level) {
        Entity entity = n.entity;
        final DisplayEntities.Look look = DisplayEntities.Look.of(d);
        if (entity == null || n.entityLevel != level || !look.equals(n.entityLook)) {
            entity = DisplayEntities.create(level, d);
            n.entity = entity;
            n.entityLevel = level;
            n.entityLook = look;
            n.effectParticles = DisplayEntities.effectParticles(d);
            n.entityRenderer = null;
            n.entityState = null;
        }
        if (entity == null) {
            return;
        }
        DisplayEntities.configure(entity, d);
        n.boxX = 0d;
        n.boxY = entity.getBbHeight() / 2d;
        n.boxZ = 0d;
        n.halfX = Math.max(0.1d, entity.getBbWidth() / 2d);
        n.halfY = Math.max(0.1d, entity.getBbHeight() / 2d);
        n.halfZ = n.halfX;
    }

    /**
     * Returns whether a resolved node is within its distance and in the frustum, caching its culling
     * box while it does not move.
     */
    boolean cull(final DisplayNode n, final CameraRenderState camera) {
        final DisplayFields f = n.fields;
        if (f == null) {
            return false;
        }
        final double dx = n.wx - camera.pos.x;
        final double dy = n.wy - camera.pos.y;
        final double dz = n.wz - camera.pos.z;
        final double max = f.maxDistance() > 0f ? f.maxDistance() : 64d;
        if (dx * dx + dy * dy + dz * dz > max * max) {
            return false;
        }
        final double r = radius(n);
        AABB box = n.cullBox;
        if (box == null || box.minX != n.wx - r || box.minY != n.wy - r || box.minZ != n.wz - r) {
            box = new AABB(n.wx - r, n.wy - r, n.wz - r, n.wx + r, n.wy + r, n.wz + r);
            n.cullBox = box;
        }
        return camera.cullFrustum.isVisible(box);
    }

    /** A sphere around the display origin that holds its visual for any rotation, blocks. */
    static double radius(final DisplayNode n) {
        final double s = Math.max(Math.abs(n.scaleX), Math.max(Math.abs(n.scaleY), Math.abs(n.scaleZ)));
        final double cx = Math.abs(n.boxX) + n.halfX;
        final double cy = Math.abs(n.boxY) + n.halfY;
        final double cz = Math.abs(n.boxZ) + n.halfZ;
        final double extra = n.itemName != null ? ITEM_NAME_OFFSET : 0d;
        return (Math.sqrt(cx * cx + cy * cy + cz * cz) + extra) * s + 0.1d;
    }

    /** Submits one resolved, visible node. */
    void submit(final DisplayNode n, final RenderFrame26x frame, final ClientLevel level, final int outlineArgb) {
        final DisplayFields f = n.fields;
        if (f == null) {
            return;
        }
        final PoseStack pose = frame.poseStack();
        final SubmitNodeCollector collector = frame.collector();
        final int light = light(f, level, n);
        pose.pushPose();
        pose.translate(n.wx - frame.cameraX(), n.wy - frame.cameraY(), n.wz - frame.cameraZ());
        Game.rotate(pose, n.rotation);
        pose.scale(n.scaleX, n.scaleY, n.scaleZ);
        switch (n.data) {
            case DisplayTextData d -> submitText(n, d, pose, collector, light, outlineArgb);
            case DisplayItemData d -> submitItem(n, frame, pose, collector, light, outlineArgb);
            case DisplayBlockData d -> {
                final BlockModelRenderState block = n.block;
                if (block != null && !block.isEmpty()) {
                    block.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, outlineArgb);
                }
            }
            case DisplayEntityData d -> submitEntity(n, frame, pose, collector, light, outlineArgb);
            default -> {
            }
        }
        pose.popPose();
    }

    private int light(final DisplayFields f, final ClientLevel level, final DisplayNode n) {
        final Integer brightness = f.brightness();
        if (brightness != null) {
            final int b = Math.clamp(brightness, 0, 15);
            return LightCoordsUtil.pack(b, b);
        }
        lightPos.set(n.wx, n.wy + n.boxY * n.scaleY, n.wz);
        return Game.lightCoords(level, lightPos);
    }

    private static void submitText(final DisplayNode n, final DisplayTextData d, final PoseStack pose, final SubmitNodeCollector collector,
            final int light, final int outlineArgb) {
        final boolean hoverText = n.hovered && n.hoverText != null;
        final List<FormattedCharSequence> lines = hoverText ? n.hoverLines : n.lines;
        final int[] lineWidths = hoverText ? n.hoverLineWidths : n.lineWidths;
        final int width = hoverText ? n.hoverTextWidth : n.textWidth;
        final Font.DisplayMode mode = d.seeThrough() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.POLYGON_OFFSET;
        Game.rotate(pose, Axis.YP.rotation((float) Math.PI));
        pose.scale(-TEXT_SCALE, -TEXT_SCALE, -TEXT_SCALE);
        final int height = lines.size() * LINE_HEIGHT - 1;
        pose.translate(1f - width / 2f, -height, 0f);
        // One background behind the block where the version draws one, else one behind each line.
        final int lineBackground = d.background() != 0
                && !Versions.CURRENT.textBackground(collector, pose, -1f, -1f, width, height, d.background(), mode, light)
                ? d.background() : 0;
        final String align = d.align().toLowerCase(Locale.ROOT);
        float y = 0f;
        for (int i = 0; i < lines.size(); i++) {
            final float x = switch (align) {
                case "left" -> 0f;
                case "right" -> width - lineWidths[i];
                default -> width / 2f - lineWidths[i] / 2f;
            };
            // Text has no entity outline pass; glow uses the glyph outline vanilla draws for glowing signs.
            collector.submitText(pose, x, y, lines.get(i), d.shadow(), mode, light, TEXT_ARGB, lineBackground, outlineArgb);
            y += LINE_HEIGHT;
        }
    }

    private void submitItem(final DisplayNode n, final RenderFrame26x frame, final PoseStack pose, final SubmitNodeCollector collector,
            final int light, final int outlineArgb) {
        final ItemStackRenderState item = n.item;
        if (item == null || item.isEmpty()) {
            return;
        }
        final FormattedCharSequence name = n.itemName;
        if (name != null) {
            pose.pushPose();
            pose.translate(0f, ITEM_NAME_OFFSET, 0f);
            Game.rotate(pose, scratchInverse(n));
            Game.rotate(pose, frame.camera().orientation);
            pose.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
            collector.submitText(pose, -n.itemNameWidth / 2f, -4f, name, false, Font.DisplayMode.NORMAL, light, TEXT_ARGB,
                    0x40000000, 0);
            pose.popPose();
        }
        // Vanilla item displays face the other way round (26.3 DisplayRenderer.ItemDisplayRenderer).
        Game.rotate(pose, Axis.YP.rotation((float) Math.PI));
        item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, outlineArgb);
    }

    /** The inverse of the node's rotation, so the item name can face the camera from inside the posed frame. */
    private Quaternionf scratchInverse(final DisplayNode n) {
        return inverse.set(n.rotation).conjugate();
    }

    private static void submitEntity(final DisplayNode n, final RenderFrame26x frame, final PoseStack pose, final SubmitNodeCollector collector,
            final int light, final int outlineArgb) {
        final Entity entity = n.entity;
        if (entity == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        DisplayEntities.place(entity, n.wx, n.wy, n.wz);
        final EntityRenderer<Entity, EntityRenderState> renderer = renderer(dispatcher, entity);
        EntityRenderState state = n.entityState;
        if (state == null || renderer != n.entityRenderer) {
            state = renderer.createRenderState();
            n.entityState = state;
            n.entityRenderer = renderer;
        }
        // 26.3 resets the dispatcher camera after extraction; name tags need it while we extract.
        final boolean ownCamera = dispatcher.camera == null;
        if (ownCamera) {
            dispatcher.prepare(Game.mainCamera(), minecraft.crosshairPickEntity);
        }
        try {
            renderer.extractRenderState(entity, state, frame.partialTick());
        } finally {
            if (ownCamera) {
                dispatcher.resetCamera();
            }
        }
        state.lightCoords = light;
        state.outlineColor = outlineArgb;
        state.shadowPieces.clear();
        dispatcher.submit(state, frame.camera(), 0d, 0d, 0d, pose, collector);
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<Entity, EntityRenderState> renderer(final EntityRenderDispatcher dispatcher, final Entity entity) {
        return (EntityRenderer<Entity, EntityRenderState>) (EntityRenderer<?, ?>) dispatcher.getRenderer(entity);
    }

    /** Returns a node's hit box centre in world space into {@code out}, blocks. */
    Vector3f boxCentre(final DisplayNode n, final Vector3f out) {
        out.set((float) (n.boxX * n.scaleX), (float) (n.boxY * n.scaleY), (float) (n.boxZ * n.scaleZ));
        n.rotation.transform(out);
        return out;
    }

    /** Returns this drawer's scratch vector, for callers on the same thread. */
    Vector3f scratch() {
        return scratch;
    }
}
