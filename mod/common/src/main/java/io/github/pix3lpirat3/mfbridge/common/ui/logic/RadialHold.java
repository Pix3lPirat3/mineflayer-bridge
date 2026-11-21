package io.github.pix3lpirat3.mfbridge.common.ui.logic;

/**
 * What letting go of a radial menu's hold key does. Held and let go over an entry, the key
 * chooses it; tapped, it leaves the menu open for a click, so a quick or accidental press never
 * runs whatever happened to be under the cursor. Game-free.
 */
public final class RadialHold {

    /** A press shorter than this is a tap: the menu stays open. */
    public static final long TAP_MS = 250;

    /** What a release does. */
    public enum Release {
        /** Run the hovered entry and close. */
        CHOOSE,
        /** Keep the menu open; a click chooses, the key or Escape closes. */
        STAY
    }

    private final long openedAt;
    private boolean sticky;

    /**
     * Starts the hold.
     *
     * @param openedAt when the menu opened, in ms
     */
    public RadialHold(final long openedAt) {
        this.openedAt = openedAt;
    }

    /**
     * The key was let go.
     *
     * @param now the time, in ms
     * @return what to do
     */
    public Release released(final long now) {
        if (sticky) {
            return Release.STAY;
        }
        if (now - openedAt < TAP_MS) {
            sticky = true;
            return Release.STAY;
        }
        return Release.CHOOSE;
    }

    /**
     * Whether the menu was tapped open and waits for a click; the key pressed again then closes it.
     *
     * @return whether it stays open after its key was let go
     */
    public boolean sticky() {
        return sticky;
    }
}
