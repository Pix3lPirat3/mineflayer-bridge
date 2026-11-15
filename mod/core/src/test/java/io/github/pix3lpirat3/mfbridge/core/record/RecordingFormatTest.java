package io.github.pix3lpirat3.mfbridge.core.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingFormatTest {
    @TempDir
    Path dir;

    @Test
    void roundTripsFramesWithTheDocumentedLayout() throws IOException {
        Random random = new Random(7);
        byte[] big = new byte[70_000];
        random.nextBytes(big);
        List<RecordedFrame> frames = List.of(
                new RecordedFrame(RecordingFormat.FROM_NODE, 1_700_000_000_123L, new byte[] {0x00, 0x01, 0x00, (byte) 0x90}),
                new RecordedFrame(RecordingFormat.FROM_MOD, 1_700_000_000_456L, new byte[0]),
                new RecordedFrame(RecordingFormat.FROM_NODE, Long.MAX_VALUE, big));
        Path file = dir.resolve("a.mfbrec");
        RecordingFormat.writeAll(file, frames);

        byte[] raw = Files.readAllBytes(file);
        assertEquals("MFBREC1\n", new String(raw, 0, 8, StandardCharsets.US_ASCII));
        // First record: direction 1, big-endian u64 time, big-endian u32 length 4, then the frame.
        assertArrayEquals(new byte[] {1, 0, 0, 0x01, (byte) 0x8B, (byte) 0xCF, (byte) 0xE5, 0x68, 0x7B, 0, 0, 0, 4,
                0x00, 0x01, 0x00, (byte) 0x90}, Arrays.copyOfRange(raw, 8, 8 + 17));
        assertEquals(8 + (13 + 4) + 13 + (13 + big.length), raw.length);

        List<RecordedFrame> back = RecordingFormat.readAll(file);
        assertEquals(frames.size(), back.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(frames.get(i).direction(), back.get(i).direction());
            assertEquals(frames.get(i).epochMs(), back.get(i).epochMs());
            assertArrayEquals(frames.get(i).bytes(), back.get(i).bytes());
        }
    }

    @Test
    void aTruncatedFinalRecordIsRejected() throws IOException {
        Path file = dir.resolve("cut.mfbrec");
        RecordingFormat.writeAll(file, List.of(new RecordedFrame(RecordingFormat.FROM_MOD, 5, new byte[] {1, 2, 3}),
                new RecordedFrame(RecordingFormat.FROM_MOD, 6, new byte[] {4, 5, 6})));
        byte[] raw = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(raw, raw.length - 2));
        assertThrows(IOException.class, () -> RecordingFormat.readAll(file));
    }

    @Test
    void rejectsOtherFiles() throws IOException {
        Path file = dir.resolve("x.mfbrec");
        Files.writeString(file, "not a recording at all");
        assertThrows(IOException.class, () -> RecordingFormat.readAll(file));
        Files.write(file, RecordingFormat.magic());
        assertEquals(0, RecordingFormat.readAll(file).size());
        Files.write(file, concat(RecordingFormat.magic(), new byte[] {7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertThrows(IOException.class, () -> RecordingFormat.readAll(file));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
