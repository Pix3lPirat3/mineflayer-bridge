package io.github.pix3lpirat3.mfbridge.common.ui.logic;

/** Resolves a translation key; the game passes {@code I18n::get}. */
@FunctionalInterface
public interface Text {

    /**
     * Returns the text of a key.
     *
     * @param key the translation key
     * @param args values for its {@code %s} placeholders
     * @return the formatted text
     */
    String get(String key, Object... args);
}
