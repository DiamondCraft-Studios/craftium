package dev.craftium.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Versioned, bounded transport for Bukkit's small plugin-message frames. */
public final class BridgeCodec {
    public static final String CHANNEL = "craftium:bridge";
    public static final int VERSION = 1, FRAME = 30_000, RAW_LIMIT = 6_000_000, COMPRESSED_LIMIT = 2_000_000;
    private static final int MAGIC = 0x43544D31, HEADER = 37, PART = FRAME - HEADER;

    private BridgeCodec() {}

    public record Message(int version, String kind, String data) {
        public Message(String kind, String data) {
            this(VERSION, kind, data);
        }
    }

    public static List<byte[]> encode(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length > RAW_LIMIT) throw new IllegalArgumentException("Craftium message exceeds 6 MB");
        try {
            var out = new ByteArrayOutputStream();
            try (var zip = new GZIPOutputStream(out)) {
                zip.write(raw);
            }
            byte[] compressed = out.toByteArray();
            if (compressed.length > COMPRESSED_LIMIT)
                throw new IllegalArgumentException("Craftium message exceeds compressed limit");
            var crc = new CRC32();
            crc.update(raw);
            UUID id = UUID.randomUUID();
            int count = (compressed.length + PART - 1) / PART;
            var frames = new ArrayList<byte[]>(count);
            for (int i = 0; i < count; i++) {
                int length = Math.min(PART, compressed.length - i * PART);
                var frame = ByteBuffer.allocate(HEADER + length)
                        .putInt(MAGIC)
                        .put((byte) VERSION)
                        .putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits())
                        .putShort((short) i)
                        .putShort((short) count)
                        .putInt(compressed.length)
                        .putInt(raw.length)
                        .putInt((int) crc.getValue());
                frame.put(compressed, i * PART, length);
                frames.add(frame.array());
            }
            return frames;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final class Assembler {
        private UUID id;
        private byte[][] parts;
        private int total, rawLength, crc, received;
        private long started;

        public void clear() {
            id = null;
            parts = null;
            received = 0;
        }

        public Optional<String> accept(byte[] frame) {
            return accept(frame, System.nanoTime());
        }

        public Optional<String> accept(byte[] frame, long now) {
            try {
                return read(frame, now);
            } catch (RuntimeException e) {
                clear();
                throw e;
            }
        }

        private Optional<String> read(byte[] frame, long now) {
            if (frame.length < HEADER || frame.length > FRAME)
                throw new IllegalArgumentException("Invalid Craftium frame size");
            var in = ByteBuffer.wrap(frame);
            if (in.getInt() != MAGIC || Byte.toUnsignedInt(in.get()) != VERSION)
                throw new IllegalArgumentException("Update both Craftium client and server to matching versions");
            UUID next = new UUID(in.getLong(), in.getLong());
            int index = Short.toUnsignedInt(in.getShort()), count = Short.toUnsignedInt(in.getShort());
            int bytes = in.getInt(), raw = in.getInt(), checksum = in.getInt();
            if (bytes < 1
                    || bytes > COMPRESSED_LIMIT
                    || raw < 0
                    || raw > RAW_LIMIT
                    || count != (bytes + PART - 1) / PART
                    || index >= count
                    || in.remaining() != Math.min(PART, bytes - index * PART))
                throw new IllegalArgumentException("Invalid Craftium frame header");
            if (id != null && now - started > 15_000_000_000L) clear();
            if (id == null) {
                id = next;
                total = bytes;
                rawLength = raw;
                crc = checksum;
                parts = new byte[count][];
                started = now;
            }
            if (!id.equals(next) || total != bytes || rawLength != raw || crc != checksum || parts.length != count)
                throw new IllegalArgumentException("Conflicting Craftium frames");
            byte[] data = new byte[in.remaining()];
            in.get(data);
            if (parts[index] != null) {
                if (!Arrays.equals(parts[index], data))
                    throw new IllegalArgumentException("Conflicting duplicate frame");
                return Optional.empty();
            }
            parts[index] = data;
            if (++received < count) return Optional.empty();
            var compressed = new ByteArrayOutputStream(total);
            for (byte[] part : parts) compressed.writeBytes(part);
            try (var zip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
                byte[] result = zip.readNBytes(rawLength + 1);
                if (result.length != rawLength || zip.read() != -1)
                    throw new IllegalArgumentException("Invalid expanded Craftium message");
                var check = new CRC32();
                check.update(result);
                if ((int) check.getValue() != crc) throw new IllegalArgumentException("Craftium checksum mismatch");
                clear();
                return Optional.of(new String(result, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid compressed Craftium message", e);
            }
        }
    }
}
