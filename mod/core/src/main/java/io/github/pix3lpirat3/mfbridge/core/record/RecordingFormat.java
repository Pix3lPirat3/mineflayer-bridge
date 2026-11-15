package io.github.pix3lpirat3.mfbridge.core.record;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code .mfbrec} recording format, shared with the Node recorder:
 * the 8 ASCII bytes {@code MFBREC1\n}, then records of a u8 direction ({@link #FROM_MOD} or {@link
 * #FROM_NODE}), a big-endian u64 epoch-millisecond timestamp, a big-endian u32 frame length and
 * the raw frame (envelope header plus payload, exactly as on the wire). The {@code 1} in the magic
 * is the format version. One file holds one connection. Frames that carry credentials ({@code
 * auth.response}, {@code pair.result}) are never written.
 */
@AnyThread
public final class RecordingFormat {
    /** File extension, with the dot. */
    public static final String EXTENSION = ".mfbrec";
    /** Direction of a frame the mod sent. */
    public static final int FROM_MOD = 0;
    /** Direction of a frame Node sent. */
    public static final int FROM_NODE = 1;
    /** Largest frame accepted when reading, matching the default transport limit with headroom. */
    public static final int MAX_FRAME_BYTES = 64 << 20;

    private static final byte[] MAGIC = "MFBREC1\n".getBytes(StandardCharsets.US_ASCII);

    private RecordingFormat() {}

    /** Returns a copy of the 8 magic bytes. */
    public static byte[] magic() {
        return MAGIC.clone();
    }

    /**
     * Writes a recording file. Not thread safe; one thread writes one file.
     */
    public static final class Writer implements Closeable {
        private final DataOutputStream out;

        /** Starts a recording on a stream, writing the magic bytes. */
        public Writer(OutputStream stream) throws IOException {
            out = new DataOutputStream(new BufferedOutputStream(stream, 64 << 10));
            out.write(MAGIC);
        }

        /** Creates or truncates a file and starts a recording in it. */
        public static Writer create(Path file) throws IOException {
            return new Writer(Files.newOutputStream(file));
        }

        /**
         * Appends one frame given as header and payload, which are written back to back.
         *
         * @param direction {@link #FROM_MOD} or {@link #FROM_NODE}
         * @param epochMs capture time, epoch milliseconds
         */
        public void write(int direction, long epochMs, byte[] header, byte[] payload) throws IOException {
            checkDirection(direction);
            out.writeByte(direction);
            out.writeLong(epochMs);
            out.writeInt(header.length + payload.length);
            out.write(header);
            out.write(payload);
        }

        /** Appends one frame. */
        public void write(RecordedFrame frame) throws IOException {
            write(frame.direction(), frame.epochMs(), frame.bytes(), new byte[0]);
        }

        /** Writes buffered records to the file. */
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * Reads a recording file record by record.
     */
    public static final class Reader implements Closeable {
        private final DataInputStream in;

        /**
         * Opens a recording on a stream and checks the magic bytes.
         *
         * @throws IOException when the stream is not a recording
         */
        public Reader(InputStream stream) throws IOException {
            in = new DataInputStream(new BufferedInputStream(stream, 64 << 10));
            byte[] head = new byte[MAGIC.length];
            try {
                in.readFully(head);
            } catch (EOFException e) {
                throw new IOException("not a bridge recording: file too short", e);
            }
            if (!Arrays.equals(head, MAGIC)) {
                throw new IOException("not a bridge recording: bad magic bytes");
            }
        }

        /** Opens a recording file. */
        public static Reader open(Path file) throws IOException {
            InputStream s = Files.newInputStream(file);
            try {
                return new Reader(s);
            } catch (IOException e) {
                s.close();
                throw e;
            }
        }

        /**
         * Returns the next record, or null at the end of the file.
         *
         * @throws IOException on a read error, an unknown direction, an oversized frame or a
         *     truncated final record
         */
        public @Nullable RecordedFrame next() throws IOException {
            int direction = in.read();
            if (direction < 0) {
                return null;
            }
            checkDirection(direction);
            try {
                long epochMs = in.readLong();
                long length = Integer.toUnsignedLong(in.readInt());
                if (length > MAX_FRAME_BYTES) {
                    throw new IOException("frame of " + length + " bytes exceeds " + MAX_FRAME_BYTES);
                }
                byte[] bytes = new byte[(int) length];
                in.readFully(bytes);
                return new RecordedFrame(direction, epochMs, bytes);
            } catch (EOFException e) {
                throw new IOException("truncated final record", e);
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Reads every record of a file. */
    public static List<RecordedFrame> readAll(Path file) throws IOException {
        List<RecordedFrame> out = new ArrayList<>();
        try (Reader r = Reader.open(file)) {
            RecordedFrame f;
            while ((f = r.next()) != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** Writes a whole file from records. */
    public static void writeAll(Path file, List<RecordedFrame> frames) throws IOException {
        try (Writer w = Writer.create(file)) {
            for (RecordedFrame f : frames) {
                w.write(f);
            }
        }
    }

    private static void checkDirection(int direction) throws IOException {
        if (direction != FROM_MOD && direction != FROM_NODE) {
            throw new IOException("unknown frame direction " + direction);
        }
    }
}
