package io.github.pix3lpirat3.mfbridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.pix3lpirat3.mfbridge.protocol.wire.Delivery;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Direction;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodecs;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The same byte vectors are checked by the plugin's plain-messages test. */
class PlainMessagesTest {
    static final MessageType<@Nullable Object> NOTE = PlainMessages.type("acme:note", Direction.BOTH, Delivery.RELIABLE);

    static String encode(@Nullable Object value) {
        ByteBuf buf = Unpooled.buffer();
        NOTE.codec().write(new WireWriter(buf), value);
        return ByteBufUtil.hexDump(buf);
    }

    static @Nullable Object decode(String hex) {
        return NOTE.codec().read(new WireReader(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex))));
    }

    @Test
    void buildsAPlainType() {
        assertEquals("acme:note", NOTE.name());
        assertEquals(Direction.BOTH, NOTE.from());
        assertEquals(Delivery.RELIABLE, NOTE.delivery());
        assertSame(WireCodecs.ANY, NOTE.codec());
        assertNull(NOTE.response());
        assertFalse(NOTE.isRequest());
        assertThrows(IllegalArgumentException.class, () -> PlainMessages.type("acme:x", Direction.MOD, Delivery.CONTROL));
    }

    @Test
    void mapVector() {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        map.put("id", 7);
        map.put("pos", List.of(1.5, -2.0, 0L));
        map.put("ok", true);
        map.put("tag", null);
        map.put("name", "hé");
        String hex = "85a2696407a3706f7393cb3ff8000000000000fe00a26f6bc3a3746167c0a46e616d65a368c3a9";
        assertEquals(hex, encode(map));
        Map<String, @Nullable Object> back = new LinkedHashMap<>();
        back.put("id", 7L);
        back.put("pos", List.of(1.5, -2L, 0L));
        back.put("ok", true);
        back.put("tag", null);
        back.put("name", "hé");
        assertEquals(back, decode(hex));
    }

    @Test
    void integerVector() {
        List<Object> ints = List.of(0, -1, 127, 128, -33, (short) 300, 70000, -70000L, 1L << 40, 9007199254740991L);
        String hex = "9a00ff7fcc80d0dfcd012cce00011170d2fffeee90cf0000010000000000cf001fffffffffffff";
        assertEquals(hex, encode(ints));
        List<Object> longs = new ArrayList<>();
        for (Object o : ints) {
            longs.add(((Number) o).longValue());
        }
        assertEquals(longs, decode(hex));
    }

    @Test
    void floatVector() {
        String hex = "9402cb3fb999999999999acbbfe0000000000000cb7e37e43c8800759c";
        assertEquals(hex, encode(List.of(2.0, 0.1, -0.5f, 1e300)));
        assertEquals(Arrays.asList(2L, 0.1, -0.5, 1e300), decode(hex));
    }

    @Test
    void emptyVector() {
        String hex = "939080a0";
        assertEquals(hex, encode(List.of(List.of(), Map.of(), "")));
        assertEquals(List.of(List.of(), Map.of(), ""), decode(hex));
    }
}
