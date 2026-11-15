package io.github.pix3lpirat3.mfbridge.common.display.logic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnceLogTest {

    @Test
    void eachBadValueIsLoggedOnceWhileRemembered() {
        final OnceLog log = new OnceLog(2);
        assertTrue(log.first("{Invisible:1b"));
        assertFalse(log.first("{Invisible:1b"), "the same broken SNBT warns once");
        assertTrue(log.first("{bad"));
        assertTrue(log.first("{worse"));
        assertTrue(log.first("{Invisible:1b"), "the oldest problem was forgotten to stay bounded");
    }
}
