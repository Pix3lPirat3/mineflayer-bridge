package io.github.pix3lpirat3.mfbridge.core.persist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Framed record files: each record is {@code u32be length, u32be crc32(payload), payload}. Used for
 * both {@code ops.log} and {@code snapshot.bin}.
 */
final class RecordFile {
    static final int HEADER = 8;

    /** Upper bound on one record so a corrupt length cannot trigger a huge allocation. */
    static final int MAX_RECORD = 64 << 20;

    /**
     * Records read from a file.
     *
     * @param records payloads in file order
     * @param validLength byte length of the intact prefix; anything after it is a torn or corrupt tail
     */
    record Contents(List<byte[]> records, long validLength) {}

    private RecordFile() {}

    /**
     * Reads every intact record. A truncated or corrupt record ends the read; a truncated
     * last record is detected by its length and checksum, and discarded.
     */
    static Contents read(Path file) throws IOException {
        byte[] all;
        try {
            all = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Contents(List.of(), 0);
        }
        ByteBuffer buf = ByteBuffer.wrap(all);
        List<byte[]> out = new ArrayList<>();
        CRC32 crc = new CRC32();
        long valid = 0;
        while (buf.remaining() >= HEADER) {
            int len = buf.getInt();
            int sum = buf.getInt();
            if (len < 0 || len > MAX_RECORD || len > buf.remaining()) {
                break;
            }
            byte[] payload = new byte[len];
            buf.get(payload);
            crc.reset();
            crc.update(payload);
            if ((int) crc.getValue() != sum) {
                break;
            }
            out.add(payload);
            valid = buf.position();
        }
        return new Contents(out, valid);
    }

    /** Frames one record into a buffer ready to write. */
    static ByteBuffer frame(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length);
        b.putInt(payload.length).putInt((int) crc.getValue()).put(payload).flip();
        return b;
    }

    /** Appends one framed record and returns the number of bytes written. */
    static int append(FileChannel channel, byte[] payload) throws IOException {
        ByteBuffer b = frame(payload);
        int n = b.remaining();
        while (b.hasRemaining()) {
            channel.write(b);
        }
        return n;
    }

    /** Opens a file for appending, truncating it to {@code length} first. */
    static FileChannel openAppend(Path file, long length) throws IOException {
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        ch.truncate(length);
        ch.position(length);
        return ch;
    }
}
