package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Picks the surface a prompt or form is shown on. A surface that does
 * not fit the prompt kind falls back to {@link Surface#HUD}. Confirm and choice prompts with surface
 * or placement {@code world} become a world menu. Game-free.
 */
public final class Surfaces {

    private Surfaces() {
    }

    /** Where a prompt or form is shown. */
    public enum Surface {
        /** The bridge prompt card, or the bridge form screen for forms. */
        HUD,
        /** Vanilla {@code DialogScreen} with a bridge connection adapter. */
        DIALOG,
        /** An anvil-styled screen; text prompts only. */
        ANVIL,
        /** A sign edit screen; text and lines prompts. */
        SIGN,
        /** A book edit screen; text and pages prompts. */
        BOOK,
        /** The next chat line; text and confirm prompts. */
        CHAT,
        /** A world menu of the choices; confirm and choice prompts. */
        WORLD,
        /** A stonecutter-styled list of {@code options}; answers an option id. */
        STONECUTTER,
        /** An enchanting-table-styled pick of up to three {@code options}; answers an option id. */
        ENCHANTING,
        /** A villager-trade-styled list of {@code offers}; answers an offer id. */
        MERCHANT,
        /** A crafting-table-styled 3x3 grid from the {@code options} palette; answers nine item ids or nulls. */
        CRAFTING
    }

    /**
     * Returns the wire name reported in {@code prompt.result.surface}.
     *
     * @param surface the surface
     * @return {@code hud}, {@code dialog}, {@code anvil}, {@code sign}, {@code book}, {@code chat}, {@code world},
     *     {@code stonecutter}, {@code enchanting}, {@code merchant} or {@code crafting}
     */
    public static String wireName(final Surface surface) {
        return surface.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the surface for a prompt.
     *
     * @param request the prompt
     * @return the surface to use
     */
    public static Surface forPrompt(final PromptRequest request) {
        final PromptKind kind = request.kind();
        final boolean entries = kind == PromptKind.CONFIRM || kind == PromptKind.CHOICE;
        if (entries && "world".equalsIgnoreCase(request.placement())) {
            return Surface.WORLD;
        }
        return switch (request.surface().toLowerCase(Locale.ROOT)) {
            case "dialog" -> Surface.DIALOG;
            case "anvil" -> kind == PromptKind.TEXT ? Surface.ANVIL : Surface.HUD;
            case "sign" -> kind == PromptKind.TEXT || kind == PromptKind.LINES ? Surface.SIGN : Surface.HUD;
            case "book" -> kind == PromptKind.TEXT || kind == PromptKind.PAGES ? Surface.BOOK : Surface.HUD;
            case "chat" -> kind == PromptKind.TEXT || kind == PromptKind.CONFIRM ? Surface.CHAT : Surface.HUD;
            case "world" -> entries ? Surface.WORLD : Surface.HUD;
            case "stonecutter" -> hasOptions(request) ? Surface.STONECUTTER : Surface.HUD;
            case "enchanting" -> hasOptions(request) ? Surface.ENCHANTING : Surface.HUD;
            case "merchant" -> Surface.MERCHANT;
            case "crafting" -> Surface.CRAFTING;
            default -> Surface.HUD;
        };
    }

    private static boolean hasOptions(final PromptRequest request) {
        return request.options() != null && !request.options().isEmpty();
    }

    /**
     * Returns whether a surface is one of the menu-styled surfaces,
     * whose answer comes from the screen rather than the card, so Enter on the card reopens it.
     *
     * @param surface the prompt's surface
     * @return true for stonecutter, enchanting, merchant and crafting
     */
    public static boolean menuStyled(final Surface surface) {
        return switch (surface) {
            case STONECUTTER, ENCHANTING, MERCHANT, CRAFTING -> true;
            case HUD, DIALOG, ANVIL, SIGN, BOOK, CHAT, WORLD -> false;
        };
    }

    /**
     * Returns whether a surface has no card fallback: crafting and merchant answers are not a prompt
     * kind's value, so when they cannot be shown the prompt is rejected with {@code unsupported}.
     *
     * @param surface the prompt's surface
     * @return true for merchant and crafting
     */
    public static boolean rejectsFallback(final Surface surface) {
        return surface == Surface.MERCHANT || surface == Surface.CRAFTING;
    }

    /**
     * Returns why a prompt's surface cannot be shown, before trying to open it.
     *
     * @param request the prompt
     * @return the reason, or null when it can be shown
     */
    public static @Nullable String unavailable(final PromptRequest request) {
        if (forPrompt(request) == Surface.MERCHANT && (request.offers() == null || request.offers().isEmpty())) {
            return "a merchant prompt needs at least one offer";
        }
        return null;
    }

    /**
     * Returns the surface for a form: {@link Surface#DIALOG} or {@link Surface#HUD} (the form screen).
     *
     * @param form the form
     * @return the surface to use
     */
    public static Surface forForm(final FormOpen form) {
        // Extra buttons need the Bridge form screen; a vanilla dialog has no place for them.
        final boolean buttons = form.buttons() != null && !form.buttons().isEmpty();
        return "dialog".equalsIgnoreCase(form.surface()) && !buttons ? Surface.DIALOG : Surface.HUD;
    }

    /**
     * Returns whether a prompt's surface is a screen that opens by itself when no other screen is open.
     *
     * @param surface the prompt's surface
     * @return true for dialog, anvil, sign, book, stonecutter, enchanting, merchant and crafting
     */
    public static boolean opensScreen(final Surface surface) {
        return switch (surface) {
            case DIALOG, ANVIL, SIGN, BOOK, STONECUTTER, ENCHANTING, MERCHANT, CRAFTING -> true;
            case HUD, CHAT, WORLD -> false;
        };
    }
}
