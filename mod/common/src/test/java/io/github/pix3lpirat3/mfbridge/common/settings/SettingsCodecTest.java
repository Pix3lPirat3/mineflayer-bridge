package io.github.pix3lpirat3.mfbridge.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SettingsCodecTest {

    @Test
    void chatPrefixAndCommandNameRoundTripAndAreChecked() {
        final BridgeSettings settings = BridgeSettings.DEFAULTS.withCommands("!", "mfb");
        final String text = SettingsCodec.encode(settings);
        assertTrue(text.contains("\"chat.prefix\": \"!\""));
        assertTrue(text.contains("\"commands.name\": \"mfb\""));
        assertEquals(settings, SettingsCodec.decode(text));
        assertThrows(IllegalArgumentException.class, () -> BridgeSettings.DEFAULTS.withCommands("/x", "bridge"));
        assertThrows(IllegalArgumentException.class, () -> BridgeSettings.DEFAULTS.withCommands("@", "Bridge Two"));
        assertThrows(IllegalArgumentException.class, () -> BridgeSettings.DEFAULTS.withCommands("", "bridge"));
    }

    @Test
    void defaultsWriteNoValues() {
        final String text = SettingsCodec.encode(BridgeSettings.DEFAULTS);
        assertFalse(text.contains("\"transport.port\":"));
        assertTrue(text.contains("// transport.port:"));
        assertEquals(BridgeSettings.DEFAULTS, SettingsCodec.decode(text));
    }

    @Test
    void changedValuesRoundTrip() {
        final BridgeSettings settings = new BridgeSettings("0.0.0.0", 25_600, AttachPolicy.MANUAL, false);
        final String text = SettingsCodec.encode(settings);
        assertTrue(text.contains("\"transport.bind_address\": \"0.0.0.0\""));
        assertEquals(settings, SettingsCodec.decode(text));
    }

    @Test
    void transportSecurityRoundTrips() {
        final BridgeSettings settings = new BridgeSettings("0.0.0.0", 25_590, AttachPolicy.WORLD_MATCH, true, true, " pc.lan ");
        assertEquals("pc.lan", settings.advertiseHost());
        final String text = SettingsCodec.encode(settings);
        assertTrue(text.contains("\"transport.require_tls_loopback\": true"));
        assertTrue(text.contains("\"transport.advertise_host\": \"pc.lan\""));
        assertEquals(settings, SettingsCodec.decode(text));
        assertTrue(BridgeSettings.DEFAULTS.transportDiffers(settings));
        assertFalse(settings.transportDiffers(new BridgeSettings("0.0.0.0", 25_590, AttachPolicy.MANUAL, false, true, "pc.lan")));
    }

    @Test
    void acceptsHandEditedJson5() {
        final String text = """
                // edited by hand
                {
                  'transport.port': 30000, // trailing comment
                  attach_policy_unknown_key: 1,
                  "attach.policy": "always_paired"
                }
                """;
        final BridgeSettings settings = SettingsCodec.decode(text);
        assertEquals(30_000, settings.port());
        assertEquals(AttachPolicy.ALWAYS_PAIRED, settings.attachPolicy());
        assertEquals(BridgeSettings.DEFAULTS.bindAddress(), settings.bindAddress());
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> SettingsCodec.decode("{\"transport.port\": 70000}"));
        assertThrows(IllegalArgumentException.class, () -> SettingsCodec.decode("{\"attach.policy\": \"sometimes\"}"));
        assertThrows(IllegalArgumentException.class, () -> SettingsCodec.decode("[1, 2]"));
    }
}
