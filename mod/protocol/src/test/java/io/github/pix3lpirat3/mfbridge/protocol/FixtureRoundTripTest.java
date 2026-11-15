package io.github.pix3lpirat3.mfbridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodec;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireReader;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Decodes every golden fixture written by the TypeScript encoder and re-encodes it byte for byte. */
final class FixtureRoundTripTest {

    @TestFactory
    Stream<DynamicTest> fixturesRoundTrip() throws IOException {
        Path file = Path.of(System.getProperty("bridge.fixtures"), "wire.tsv");
        List<String[]> rows = Files.readAllLines(file).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split("\t"))
                .toList();
        assertFalse(rows.isEmpty(), "no fixtures found");
        return rows.stream().map(row -> DynamicTest.dynamicTest(row[0] + " " + row[1] + " " + row[2], () -> {
            ByteBuf in = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(row[3]));
            ByteBuf out = Unpooled.buffer();
            WireReader r = new WireReader(in);
            WireWriter w = new WireWriter(out);
            switch (row[0]) {
                case "envelope" -> Envelope.read(r).write(w);
                case "message" -> roundTrip(codecForMessage(row[1]), r, w);
                case "kind" -> roundTrip(codecForKind(row[1]), r, w);
                default -> throw new IllegalArgumentException("unknown fixture space " + row[0]);
            }
            assertEquals(0, r.remaining(), "trailing bytes");
            assertEquals(row[3], ByteBufUtil.hexDump(out));
        }));
    }

    private static <T> void roundTrip(WireCodec<T> codec, WireReader r, WireWriter w) {
        codec.write(w, codec.read(r));
    }

    private static WireCodec<?> codecForMessage(String name) {
        return CoreMessages.ALL.stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow()
                .codec();
    }

    private static WireCodec<?> codecForKind(String name) {
        return CoreKinds.ALL.stream()
                .filter(k -> k.name().equals(name))
                .findFirst()
                .orElseThrow()
                .codec();
    }
}
