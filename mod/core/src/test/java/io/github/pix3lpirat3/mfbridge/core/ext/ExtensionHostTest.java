package io.github.pix3lpirat3.mfbridge.core.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.ObjectKind;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.Setting;
import io.github.pix3lpirat3.mfbridge.api.SettingValue;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExtensionHostTest {
    static BridgeExtension ext(String id, Consumer<ExtensionRegistrar> body) {
        return new BridgeExtension() {
            @Override
            public ExtensionId id() {
                return ExtensionId.parse(id);
            }

            @Override
            public SemVer version() {
                return SemVer.parse("1.0.0");
            }

            @Override
            public void register(ExtensionRegistrar registrar) {
                body.accept(registrar);
            }
        };
    }

    static ExtensionHost host() {
        return new ExtensionHost(new WorldMatchers(), new EventBus((t, e) -> {}));
    }

    @Test
    void rejectsForeignNamespacesAndDuplicates() {
        ExtensionHost h = host();
        KindType<LabelData> other = new KindType<>(0, "other:blip", LabelData.CODEC);
        assertThrows(IllegalArgumentException.class,
                () -> h.register(ext("acme:a", r -> r.kind(ObjectKind.of(other, SemVer.parse("1.0.0"))))));
        assertThrows(IllegalArgumentException.class,
                () -> h.register(ext("core:x", r -> r.kind(ObjectKind.of(CoreKinds.BOX, SemVer.parse("1.0.0"))))));
        h.register(ext("acme:b", r -> {}));
        assertThrows(IllegalArgumentException.class, () -> h.register(ext("acme:b", r -> {})));
    }

    @Test
    void negotiatesIdsInHelloOrderAndResolvesKinds() {
        ExtensionHost h = host();
        KindType<LabelData> blip = new KindType<>(7, "acme:blip", LabelData.CODEC);
        h.register(ext("acme:radar", r -> r.kind(ObjectKind.of(blip, SemVer.parse("1.0.0")))));
        ConnRegistry reg = h.negotiate(List.of(), List.of("x:none", "acme:blip", "acme:blip"));
        assertEquals(1, reg.entries().size());
        assertEquals(0x100, reg.entries().get(0).id());
        assertSame(blip, reg.kind(0x100));
        assertSame(CoreKinds.BOX, reg.kind(16));
        assertNull(reg.kind(0x101));
        assertEquals(0x100, reg.kindId(blip));
        assertEquals(16, reg.kindId(CoreKinds.BOX));
        assertSame(CoreKinds.LABEL, h.kindByName("core:label"));
        assertEquals(-1, h.kindByName("gone:kind").id());
    }

    @Test
    void settingsAreTypedAndUnique() {
        ExtensionHost h = host();
        SettingValue<?>[] holder = new SettingValue<?>[1];
        h.register(ext("acme:s", r -> holder[0] = r.setting(new Setting<>("max", Integer.class, 5, "max"))));
        @SuppressWarnings("unchecked")
        SettingValue<Integer> v = (SettingValue<Integer>) holder[0];
        assertEquals(5, v.get());
        v.set(9);
        assertEquals(9, h.setting("acme:s/max").get());
        assertThrows(IllegalArgumentException.class, () -> h.register(ext("acme:t", r -> {
            r.setting(new Setting<>("k", String.class, "a", ""));
            r.setting(new Setting<>("k", String.class, "b", ""));
        })));
    }

    @Test
    void settingsAreSavedAndRestored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("extension-settings.json");
        Files.writeString(file, "{\"acme:s/max\": 7, \"acme:s/on\": \"oops\"}");
        Logger log = LoggerFactory.getLogger(ExtensionHostTest.class);
        ExtensionSettingsFile saved = ExtensionSettingsFile.load(file, log);
        List<byte[]> writes = new ArrayList<>();
        saved.writeWith(writes::add);
        ExtensionHost h = new ExtensionHost(new WorldMatchers(), new EventBus((t, e) -> {}), saved);
        h.register(ext("acme:s", r -> {
            r.setting(new Setting<>("max", Integer.class, 5, "max"));
            r.setting(new Setting<>("on", Boolean.class, true, "on"));
            r.setting(new Setting<>("ratio", Double.class, 0.5, "ratio"));
        }));
        assertEquals(7, h.setting("acme:s/max").get());
        assertEquals(true, h.setting("acme:s/on").get(), "a mistyped value falls back to the default");
        assertEquals(List.of("max", "on", "ratio"), h.settings("acme:s").stream().map(v -> v.setting().key()).toList());
        @SuppressWarnings("unchecked")
        SettingValue<Double> ratio = (SettingValue<Double>) h.setting("acme:s/ratio");
        ratio.set(0.25);
        ratio.set(0.25);
        assertEquals(1, writes.size(), "an unchanged value writes nothing");
        Files.write(file, writes.get(0));
        ExtensionHost again = new ExtensionHost(new WorldMatchers(), new EventBus((t, e) -> {}), ExtensionSettingsFile.load(file, log));
        again.register(ext("acme:s", r -> r.setting(new Setting<>("ratio", Double.class, 0.5, "ratio"))));
        assertEquals(0.25, again.setting("acme:s/ratio").get());
    }

    @Test
    void parsesTypedInput() {
        assertEquals(12, ExtensionSettingsFile.parse(new Setting<>("a", Integer.class, 0, ""), " 12 "));
        assertNull(ExtensionSettingsFile.parse(new Setting<>("a", Integer.class, 0, ""), "1.5"));
        assertEquals(0.5, ExtensionSettingsFile.parse(new Setting<>("b", Double.class, 0.0, ""), "0.5"));
        assertNull(ExtensionSettingsFile.parse(new Setting<>("b", Double.class, 0.0, ""), "NaN"));
        assertEquals(" x ", ExtensionSettingsFile.parse(new Setting<>("c", String.class, "", ""), " x "));
        assertEquals(false, ExtensionSettingsFile.parse(new Setting<>("d", Boolean.class, true, ""), "FALSE"));
    }
}
