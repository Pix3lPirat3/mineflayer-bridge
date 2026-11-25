package io.github.pix3lpirat3.mfbridge.common.version;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.TextInputManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/**
 * The game calls that only moved or were renamed between the supported versions, each found once by
 * all of its names. Calls through here cost what direct calls do.
 *
 * <p>Since 26.2 the screen, overlay and toasts live on {@code Gui} and the HUD is {@code Gui.hud};
 * before, they were on {@code Minecraft} and {@code Gui} was the HUD.
 */
public final class Game {

    private static final MethodHandle SCREEN;
    private static final MethodHandle SET_SCREEN;
    private static final MethodHandle OVERLAY;
    private static final MethodHandle TOASTS;
    private static final MethodHandle CHAT;
    private static final MethodHandle BOSS_OVERLAY;
    private static final MethodHandle HUD_HIDDEN;
    private static final MethodHandle OVERLAY_MESSAGE;
    private static final MethodHandle TITLE_TIMES;
    private static final MethodHandle TITLE;
    private static final MethodHandle SUBTITLE;
    private static final MethodHandle MAIN_CAMERA = Members.first("GameRenderer.mainCamera",
            Members.virtual(GameRenderer.class, MethodType.methodType(Camera.class), "mainCamera", "getMainCamera"));
    private static final MethodHandle LIGHT_COORDS = Members.first("getLightCoords", Members.statics(LightCoordsUtil.class,
            MethodType.methodType(int.class, BlockAndLightGetter.class, BlockPos.class), "getLightCoords"),
            Members.statics(LevelRenderer.class, MethodType.methodType(int.class, BlockAndLightGetter.class, BlockPos.class), "getLightCoords"));
    private static final MethodHandle ROTATE = Members.first("PoseStack.rotate",
            Members.virtual(PoseStack.class, MethodType.methodType(void.class, Quaternionfc.class), "rotate", "mulPose"));
    private static final MethodHandle SHORTCUT_KEY = Members.first("KeyEvent.shortcutKey",
            Members.virtual(KeyEvent.class, MethodType.methodType(int.class), "shortcutKey", "key"));
    private static final @Nullable MethodHandle KEY_DOWN = Members.statics(InputConstants.class,
            MethodType.methodType(boolean.class, int.class), "isKeyDown");
    private static final @Nullable MethodHandle KEY_DOWN_IN = Members.statics(InputConstants.class,
            MethodType.methodType(boolean.class, Window.class, int.class), "isKeyDown");
    private static final MethodHandle STOP_TEXT_INPUT;
    private static final @Nullable MethodHandle MATCHES_KEY = Members.virtual(KeyMapping.class,
            MethodType.methodType(boolean.class, InputConstants.Key.class), "matches");
    private static final @Nullable MethodHandle CAMERA_PARTIAL = Members.getter(CameraRenderState.class, float.class,
            "cameraEntityPartialTicks");

    /** The keyboard key type, {@code KEYBOARD} or {@code KEYSYM}. */
    public static final InputConstants.Type KEYBOARD = (InputConstants.Type) java.util.Objects.requireNonNull(
            Members.staticValue(InputConstants.Type.class, InputConstants.Type.class, "KEYBOARD", "KEYSYM"),
            "InputConstants.Type.KEYBOARD");

    static {
        final MethodHandle gui = Members.first("Minecraft.gui", Members.getter(Minecraft.class, Gui.class, "gui"));
        final MethodHandle screen = Members.virtual(Gui.class, MethodType.methodType(Screen.class), "screen");
        if (screen != null) {
            SCREEN = MethodHandles.filterArguments(screen, 0, gui);
            SET_SCREEN = MethodHandles.filterArguments(Members.first("Gui.setScreen",
                    Members.virtual(Gui.class, MethodType.methodType(void.class, Screen.class), "setScreen")), 0, gui);
            OVERLAY = MethodHandles.filterArguments(Members.first("Gui.overlay",
                    Members.virtual(Gui.class, MethodType.methodType(Overlay.class), "overlay")), 0, gui);
            TOASTS = MethodHandles.filterArguments(Members.first("Gui.toastManager",
                    Members.virtual(Gui.class, MethodType.methodType(ToastManager.class), "toastManager")), 0, gui);
        } else {
            SCREEN = Members.first("Minecraft.screen", Members.getter(Minecraft.class, Screen.class, "screen"));
            SET_SCREEN = Members.first("Minecraft.setScreen",
                    Members.virtual(Minecraft.class, MethodType.methodType(void.class, Screen.class), "setScreen"));
            OVERLAY = Members.first("Minecraft.getOverlay",
                    Members.virtual(Minecraft.class, MethodType.methodType(Overlay.class), "getOverlay"));
            TOASTS = Members.first("Minecraft.getToastManager",
                    Members.virtual(Minecraft.class, MethodType.methodType(ToastManager.class), "getToastManager"));
        }
        // The HUD: Gui.hud where it exists, else Gui itself.
        MethodHandle hud = gui;
        Class<?> hudType = Gui.class;
        try {
            final java.lang.reflect.Field f = Gui.class.getField("hud");
            hud = MethodHandles.filterArguments(MethodHandles.publicLookup().unreflectGetter(f), 0, gui);
            hudType = f.getType();
        } catch (final NoSuchFieldException | IllegalAccessException e) {
            // Gui is the HUD
        }
        hud = hud.asType(MethodType.methodType(hudType, Minecraft.class));
        CHAT = MethodHandles.filterArguments(Members.first("getChat",
                Members.virtual(hudType, MethodType.methodType(ChatComponent.class), "getChat")), 0, hud);
        BOSS_OVERLAY = MethodHandles.filterArguments(Members.first("getBossOverlay",
                Members.virtual(hudType, MethodType.methodType(BossHealthOverlay.class), "getBossOverlay")), 0, hud);
        OVERLAY_MESSAGE = MethodHandles.filterArguments(Members.first("setOverlayMessage", Members.virtual(hudType,
                MethodType.methodType(void.class, Component.class, boolean.class), "setOverlayMessage")), 0, hud);
        TITLE_TIMES = MethodHandles.filterArguments(Members.first("setTimes", Members.virtual(hudType,
                MethodType.methodType(void.class, int.class, int.class, int.class), "setTimes")), 0, hud);
        TITLE = MethodHandles.filterArguments(Members.first("setTitle", Members.virtual(hudType,
                MethodType.methodType(void.class, Component.class), "setTitle")), 0, hud);
        SUBTITLE = MethodHandles.filterArguments(Members.first("setSubtitle", Members.virtual(hudType,
                MethodType.methodType(void.class, Component.class), "setSubtitle")), 0, hud);
        final MethodHandle hidden = Members.virtual(hudType, MethodType.methodType(boolean.class), "isHidden");
        HUD_HIDDEN = hidden != null ? MethodHandles.filterArguments(hidden, 0, hud)
                : MethodHandles.filterArguments(Members.first("Options.hideGui", Members.getter(Options.class, boolean.class, "hideGui")),
                        0, Members.first("Minecraft.options", Members.getter(Minecraft.class, Options.class, "options")));
        final MethodHandle stopOwned = Members.virtual(TextInputManager.class, MethodType.methodType(void.class, Object.class),
                "stopTextInput");
        STOP_TEXT_INPUT = stopOwned != null ? stopOwned : MethodHandles.dropArguments(Members.first("stopTextInput",
                Members.virtual(TextInputManager.class, MethodType.methodType(void.class), "stopTextInput")), 1, Object.class);
    }

    private Game() {
    }

    private static RuntimeException rethrow(final Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    /** Returns the open screen, or null. */
    public static @Nullable Screen screen() {
        try {
            return (Screen) SCREEN.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Opens a screen, or closes the open one with null. */
    public static void setScreen(final @Nullable Screen screen) {
        try {
            SET_SCREEN.invokeExact(Minecraft.getInstance(), screen);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the loading overlay, or null. */
    public static @Nullable Overlay overlay() {
        try {
            return (Overlay) OVERLAY.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the toast manager. */
    public static ToastManager toasts() {
        try {
            return (ToastManager) TOASTS.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the chat. */
    public static ChatComponent chat() {
        try {
            return (ChatComponent) CHAT.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the boss bars. */
    public static BossHealthOverlay bossOverlay() {
        try {
            return (BossHealthOverlay) BOSS_OVERLAY.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns whether the HUD is hidden (F1). */
    public static boolean hudHidden() {
        try {
            return (boolean) HUD_HIDDEN.invokeExact(Minecraft.getInstance());
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Shows a line above the hotbar. */
    public static void overlayMessage(final Component message, final boolean animate) {
        try {
            OVERLAY_MESSAGE.invokeExact(Minecraft.getInstance(), message, animate);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Shows a title.
     *
     * @param fadeIn ticks
     * @param stay ticks
     * @param fadeOut ticks
     * @param title the title, or null to keep the shown one
     * @param subtitle the subtitle, or null to keep the shown one
     */
    public static void title(final int fadeIn, final int stay, final int fadeOut, final @Nullable Component title,
            final @Nullable Component subtitle) {
        final Minecraft mc = Minecraft.getInstance();
        try {
            TITLE_TIMES.invokeExact(mc, fadeIn, stay, fadeOut);
            if (subtitle != null) {
                SUBTITLE.invokeExact(mc, subtitle);
            }
            if (title != null) {
                TITLE.invokeExact(mc, title);
            }
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the main camera. */
    public static Camera mainCamera() {
        try {
            return (Camera) MAIN_CAMERA.invokeExact(Minecraft.getInstance().gameRenderer);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the packed light at a position. */
    public static int lightCoords(final BlockAndLightGetter level, final BlockPos pos) {
        try {
            return (int) LIGHT_COORDS.invokeExact(level, pos);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Rotates a pose stack. */
    public static void rotate(final PoseStack pose, final Quaternionfc rotation) {
        try {
            ROTATE.invokeExact(pose, rotation);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the key a key event stands for as a shortcut. */
    public static int shortcutKey(final KeyEvent event) {
        try {
            return (int) SHORTCUT_KEY.invokeExact(event);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns whether a keyboard key is held. */
    public static boolean keyDown(final int key) {
        try {
            if (KEY_DOWN != null) {
                return (boolean) KEY_DOWN.invokeExact(key);
            }
            return (boolean) java.util.Objects.requireNonNull(KEY_DOWN_IN, "InputConstants.isKeyDown")
                    .invokeExact(Minecraft.getInstance().getWindow(), key);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Stops text input that an owner started. */
    public static void stopTextInput(final Object owner) {
        try {
            STOP_TEXT_INPUT.invokeExact(Minecraft.getInstance().textInputManager(), owner);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns whether a key mapping is bound to a key. */
    public static boolean matches(final KeyMapping mapping, final InputConstants.Key key) {
        if (MATCHES_KEY == null) {
            return mapping.saveString().equals(key.getName());
        }
        try {
            return (boolean) MATCHES_KEY.invokeExact(mapping, key);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Builds the tab bar of a screen: {@code MenuTabBar} where it exists, the plain bar before. Screen
     * setup only, so plain reflection.
     */
    public static TabNavigationBar tabBar(final TabManager manager, final int width, final Tab... tabs) {
        Class<?> type;
        try {
            type = Class.forName("net.minecraft.client.gui.components.tabs.MenuTabBar");
        } catch (final ClassNotFoundException e) {
            type = TabNavigationBar.class;
        }
        try {
            Object b = type.getMethod("builder", TabManager.class, int.class).invoke(null, manager, width);
            b = b.getClass().getMethod("addTabs", Tab[].class).invoke(b, (Object) tabs);
            return (TabNavigationBar) b.getClass().getMethod("build").invoke(b);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("building a tab bar", e);
        }
    }

    /** Lays a tab bar out across a width (older bars keep the width they were built with). */
    public static void arrangeTabs(final TabNavigationBar bar, final int width) {
        try {
            bar.getClass().getMethod("arrangeElements", int.class).invoke(bar, width);
        } catch (final NoSuchMethodException e) {
            try {
                bar.getClass().getMethod("arrangeElements").invoke(bar);
            } catch (final ReflectiveOperationException inner) {
                throw new IllegalStateException("arranging a tab bar", inner);
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("arranging a tab bar", e);
        }
    }

    /** Draws part of a texture with the textured GUI pipeline. */
    public static void blit(final GuiGraphicsExtractor g, final Identifier texture, final int x, final int y, final float u,
            final float v, final int width, final int height, final int textureWidth, final int textureHeight) {
        Versions.CURRENT.blit(g, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /** Draws a GUI sprite with the textured GUI pipeline. */
    public static void blitSprite(final GuiGraphicsExtractor g, final Identifier sprite, final int x, final int y,
            final int width, final int height) {
        Versions.CURRENT.blitSprite(g, sprite, x, y, width, height);
    }

    /** Draws part of a GUI sprite with the textured GUI pipeline. */
    public static void blitSprite(final GuiGraphicsExtractor g, final Identifier sprite, final int spriteWidth,
            final int spriteHeight, final int u, final int v, final int x, final int y, final int width, final int height) {
        Versions.CURRENT.blitSprite(g, sprite, spriteWidth, spriteHeight, u, v, x, y, width, height);
    }

    /** Returns the camera entity's partial tick of a frame. */
    public static float partialTick(final CameraRenderState camera) {
        if (CAMERA_PARTIAL == null) {
            return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        }
        try {
            return (float) CAMERA_PARTIAL.invokeExact(camera);
        } catch (final Throwable t) {
            throw rethrow(t);
        }
    }
}
