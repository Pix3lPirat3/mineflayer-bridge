package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RadialHoldTest {
    @Test
    void aHoldChoosesOnReleaseAndATapKeepsTheMenuOpen() {
        final RadialHold held = new RadialHold(1000);
        assertEquals(RadialHold.Release.CHOOSE, held.released(1000 + RadialHold.TAP_MS));
        assertFalse(held.sticky());

        final RadialHold tapped = new RadialHold(1000);
        assertEquals(RadialHold.Release.STAY, tapped.released(1100), "a quick press runs nothing");
        assertTrue(tapped.sticky());
        // Once open for a click, a later release of the key runs nothing either.
        assertEquals(RadialHold.Release.STAY, tapped.released(5000));
    }
}
