package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.PlainKinds;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.StoreHandler;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistryEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A third-party extension that declares an object kind with plain values. */
class PlainKindTest {
    static final KindType<@Nullable Object> NOTE = PlainKinds.type("acme:note");
    static final String P = "bot:miner@example.com/";

    static final class Notes implements BridgeExtension {
        final List<String> seen = new ArrayList<>();

        @Override
        public ExtensionId id() {
            return ExtensionId.parse("acme:notes");
        }

        @Override
        public SemVer version() {
            return SemVer.parse("1.0.0");
        }

        @Override
        public void register(ExtensionRegistrar r) {
            r.kind(PlainKinds.kind("acme:note", SemVer.parse("1.0.0"),
                    value -> value instanceof Map<?, ?> m && m.get("text") instanceof String ? null : "a note needs a text",
                    new StoreHandler<>() {
                        @Override
                        public void upserted(String key, long rev, @Nullable Object data) {
                            seen.add(key + "=" + ((Map<?, ?>) data).get("text"));
                        }

                        @Override
                        public void removed(String key) {
                            seen.add(key + " removed");
                        }
                    }));
        }
    }

    @TempDir
    Path dir;

    private static int kindId(Welcome w) {
        for (RegistryEntry e : w.registry()) {
            if (e.name().equals(NOTE.name())) {
                return e.id();
            }
        }
        throw new AssertionError("acme:note not in welcome");
    }

    @Test
    void aPlainKindIsStoredValidatedAndShown() throws Exception {
        Notes notes = new Notes();
        try (Harness h = new Harness(dir, b -> b.extension(notes).attachPolicy(AttachPolicy.ALWAYS), DeviceApprover.ALLOW_ONCE)) {
            FakePlugin p = FakePlugin.connect(h, "dev");
            int kind = kindId(p.handshake(h.localToken(), List.of(), List.of("acme:note")));
            int s = p.openBot("Miner", "example.com").sessionId();

            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("text", "buy wheat");
            data.put("pages", List.of(1, 2.5));
            p.upsert(s, new ObjectFull(P + "acme:notes/n1", kind, 1, null, Lifetime.SESSION, Scope.GLOBAL, null, null,
                    NOTE.encode(data)));
            h.tickUntil("the note is visible", () -> h.core.renderSnapshot().find(P + "acme:notes/n1") != null);
            RenderEntry entry = h.core.renderSnapshot().find(P + "acme:notes/n1");
            assertEquals("acme:note", entry.kind().name());
            assertEquals(Map.of("text", "buy wheat", "pages", List.of(1L, 2.5)), entry.data(), "decoded as plain values");
            assertEquals(List.of(P + "acme:notes/n1=buy wheat"), notes.seen, "the store handler saw the write");

            // The validator is the schema of a plain kind.
            p.upsert(s, new ObjectFull(P + "acme:notes/n2", kind, 1, null, Lifetime.SESSION, Scope.GLOBAL, null, null,
                    NOTE.encode(List.of("no text"))));
            ErrorMessage refused = p.await(CoreMessages.ERROR, s);
            assertEquals("invalid_message", refused.error().code());
            assertTrue(refused.error().message().contains("a note needs a text"), refused.error().message());
            assertSame(entry, h.core.renderSnapshot().find(P + "acme:notes/n1"));
        }
    }
}
