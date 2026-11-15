package io.github.pix3lpirat3.mfbridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerData;
import org.junit.jupiter.api.Test;

class ApiTypesTest {
    @Test
    void extensionIdParsesAndValidates() {
        ExtensionId id = ExtensionId.parse("acme:radar");
        assertEquals("acme", id.namespace());
        assertEquals("radar", id.name());
        assertEquals("acme:radar", id.toString());
        assertThrows(IllegalArgumentException.class, () -> ExtensionId.parse("radar"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionId.parse("acme:ra/dar"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionId.parse(":x"));
    }

    @Test
    void semVerParsesAndOrders() {
        SemVer v = SemVer.parse("1.2.3-beta.1");
        assertEquals(new SemVer(1, 2, 3, "-beta.1"), v);
        assertEquals("1.2.3-beta.1", v.toString());
        assertTrue(SemVer.parse("1.10.0").compareTo(SemVer.parse("1.9.9")) > 0);
        assertEquals(0, SemVer.parse("2.0.0+b").compareTo(SemVer.parse("2.0.0")));
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("1.2"));
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("1.x.3"));
    }

    @Test
    void settingValidatesType() {
        Setting<Integer> s = new Setting<>("render.max_distance", Integer.class, 64, "Max distance");
        assertEquals(64, s.defaultValue());
        assertThrows(IllegalArgumentException.class, () -> new Setting<>("x", Object.class, new Object(), ""));
        assertThrows(IllegalArgumentException.class, () -> new Setting<>("", String.class, "a", ""));
    }

    @Test
    void objectKindDefaultsAcceptEverything() {
        ObjectKind<LayerData> kind = ObjectKind.of(CoreKinds.LAYER, SemVer.parse("1.0.0"));
        assertNull(kind.validator().validate(new LayerData(null, true)));
        kind.handler().upserted("k", 1, new LayerData(null, true));
        kind.handler().removed("k");
    }

    @Test
    void bridgeExceptionCarriesCode() {
        BridgeException e = new BridgeException("conflict", "rev mismatch", true, 3L);
        assertEquals("conflict", e.code());
        assertTrue(e.retryable());
        assertEquals(3L, e.details());
        assertNull(new BridgeException("x", "y").details());
    }
}
