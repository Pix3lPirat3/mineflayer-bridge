package io.github.pix3lpirat3.mfbridge.core.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireException;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoreUnitTest {
    static byte[] msgpack(Consumer<WireWriter> body) {
        ByteBuf buf = Unpooled.buffer();
        body.accept(new WireWriter(buf));
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    static List<@Nullable Object> array(byte[] data) {
        @SuppressWarnings("unchecked")
        List<@Nullable Object> list = (List<@Nullable Object>) new WireReader(Unpooled.wrappedBuffer(data)).readAny();
        return list;
    }

    @Test
    void patchReplacesAndPads() {
        byte[] data = msgpack(w -> {
            w.writeArrayHeader(3);
            w.writeUInt(1);
            w.writeString("two");
            w.writeArrayHeader(1);
            w.writeBool(true);
        });
        byte[] patch = msgpack(w -> {
            w.writeMapHeader(2);
            w.writeUInt(1);
            w.writeString("TWO");
            w.writeUInt(5);
            w.writeUInt(6);
        });
        List<@Nullable Object> out = array(PatchApplier.apply(data, patch));
        List<@Nullable Object> expected = new ArrayList<>();
        expected.add(1L);
        expected.add("TWO");
        expected.add(List.of(true));
        expected.add(null);
        expected.add(null);
        expected.add(6L);
        assertEquals(expected, out);
    }

    @Test
    void emptyPatchIsIdentityAndMalformedInputIsRejected() {
        byte[] data = msgpack(w -> {
            w.writeArrayHeader(2);
            w.writeUInt(1);
            w.writeNil();
        });
        assertArrayEquals(data, PatchApplier.apply(data, msgpack(w -> w.writeMapHeader(0))));
        assertThrows(WireException.class, () -> PatchApplier.apply(data, msgpack(w -> {
            w.writeMapHeader(1);
            w.writeString("x");
            w.writeUInt(1);
        })));
        assertThrows(WireException.class, () -> PatchApplier.apply(data, msgpack(w -> {
            w.writeMapHeader(1);
            w.writeUInt(PatchApplier.MAX_FIELD_INDEX + 1);
            w.writeUInt(1);
        })));
        assertThrows(WireException.class, () -> PatchApplier.apply(new byte[] {(byte) 0x91}, msgpack(w -> w.writeMapHeader(0))));
    }

    @Test
    void keysParseOwnersIncludingControllers() {
        assertEquals(new ObjectKeys.Parts("bot:abc", "bridge:viz", "route/1"), ObjectKeys.parse("bot:abc/bridge:viz/route/1"));
        assertEquals(new ObjectKeys.Parts("ctl:dev/mgr", "ext", "x"), ObjectKeys.parse("ctl:dev/mgr/ext/x"));
        assertEquals(new ObjectKeys.Parts("shared", "bridge:zones", "z"), ObjectKeys.parse("shared/bridge:zones/z"));
        for (String bad : List.of("", "noslash", "/a/b", "a//b", "a/b", "a/b/", "ctl:dev/x")) {
            assertThrows(BridgeException.class, () -> ObjectKeys.parse(bad), bad);
        }
    }

    @Test
    void storeInternsHandlesRemovesByPrefixAndExpires() {
        List<String> removed = new ArrayList<>();
        ObjectStore store = new ObjectStore(new ObjectStore.Listener() {
            @Override
            public void upserted(StoredObject object, StoredObject.@Nullable State previous) {}

            @Override
            public void removed(StoredObject object) {
                removed.add(object.key());
            }
        });
        StoredObject a = put(store, "bot:x/e/a", -1);
        put(store, "bot:x/e/b", 100);
        put(store, "bot:x/f/c", -1);
        put(store, "bot:xy/e/d", -1);
        assertEquals(3, store.countOwner("bot:x"));
        StoredObject a2 = put(store, "bot:x/e/a", -1);
        assertEquals(a.handle(), a2.handle());
        assertEquals("bot:x/e/a", store.keyOf(a.handle()));

        assertEquals(0, store.expire(99));
        assertEquals(1, store.expire(100));
        assertEquals(List.of("bot:x/e/b"), removed);

        assertEquals(2, store.removePrefix("bot:x/"));
        assertNull(store.get("bot:x/f/c"));
        assertEquals(1, store.size());
        assertEquals(0, store.countOwner("bot:x"));
    }

    private static ObjectStore quiet() {
        return new ObjectStore(new ObjectStore.Listener() {
            @Override
            public void upserted(StoredObject object, StoredObject.@Nullable State previous) {}

            @Override
            public void removed(StoredObject object) {}
        });
    }

    @Test
    void handlesOfRemovedObjectsAreReusedAfterRecycling() {
        ObjectStore store = quiet();
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 10; i++) {
                put(store, "bot:x/e/r" + round + "-" + i, -1);
            }
            assertEquals(10, store.removePrefix("bot:x/"));
            store.recycleHandles();
        }
        assertEquals(10, store.handleCount(), "500 keys came and went through ten handles");

        StoredObject a = put(store, "bot:x/e/a", -1);
        int handle = a.handle();
        store.remove("bot:x/e/a");
        StoredObject b = put(store, "bot:x/e/b", -1);
        assertNotEquals(handle, b.handle(), "a handle is not reused before recycling");
        assertNull(store.keyOf(handle));
        store.recycleHandles();
        assertEquals(handle, put(store, "bot:x/e/c", -1).handle());
        assertEquals("bot:x/e/c", store.keyOf(handle));
        assertNull(store.get("bot:x/e/a"));
    }

    @Test
    void aRefreshedTtlMovesItsHeapEntry() {
        ObjectStore store = quiet();
        for (int i = 0; i < 1000; i++) {
            put(store, "bot:x/e/a", 100 + i);
        }
        assertEquals(1, store.expiring(), "one entry however often the TTL is refreshed");
        put(store, "bot:x/e/b", 50);
        put(store, "bot:x/e/c", 2000);
        put(store, "bot:x/e/d", 70);
        assertEquals(4, store.expiring());
        assertEquals(2, store.expire(70));
        assertNull(store.get("bot:x/e/b"));
        assertNull(store.get("bot:x/e/d"));
        assertEquals(0, store.expire(1098));
        assertEquals(1, store.expire(1099));
        put(store, "bot:x/e/c", -1);
        assertEquals(0, store.expiring(), "a write without a TTL takes the object out");
        assertEquals(0, store.expire(Long.MAX_VALUE));
        assertEquals(1, store.size());

        // Random order in, sorted order out.
        java.util.Random random = new java.util.Random(7);
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long at = random.nextInt(10_000);
            times.add(at);
            put(store, "bot:y/e/" + i, at);
        }
        for (int i = 0; i < 200; i += 3) {
            long at = random.nextInt(10_000);
            times.set(i, at);
            put(store, "bot:y/e/" + i, at);
        }
        for (int i = 1; i < 200; i += 7) {
            times.set(i, -1L);
            store.remove("bot:y/e/" + i);
        }
        for (long now = 0; now <= 10_000; now += 500) {
            store.expire(now);
            for (int i = 0; i < 200; i++) {
                long at = times.get(i);
                assertEquals(at > now, store.get("bot:y/e/" + i) != null, "object " + i + " at " + now);
            }
        }
    }

    private static StoredObject put(ObjectStore store, String key, long expires) {
        return store.put(key, ObjectKeys.parse(key), new StoredObject.State(CoreKinds.LAYER, 1, null, Lifetime.SESSION,
                Scope.GLOBAL, null, null, expires, new byte[] {(byte) 0x90}, null, 1, null));
    }
}
