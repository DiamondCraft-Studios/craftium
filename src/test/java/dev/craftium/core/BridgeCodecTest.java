package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class BridgeCodecTest {
    private static String random(int size) {
        var r = new Random(42);
        var out = new StringBuilder(size);
        for (int i = 0; i < size; i++) out.append((char) (32 + r.nextInt(90)));
        return out.toString();
    }

    @Test
    void roundTripsUnicode() {
        String text = "Craftium 🌲 建築 — ".repeat(100);
        var a = new BridgeCodec.Assembler();
        assertEquals(text, a.accept(BridgeCodec.encode(text).getFirst()).orElseThrow());
    }

    @Test
    void roundTripsLargeTransferOutOfOrder() {
        String text = random(500_000);
        var frames = new ArrayList<>(BridgeCodec.encode(text));
        assertTrue(frames.size() > 10);
        assertTrue(frames.stream().allMatch(f -> f.length <= 30_000));
        Collections.reverse(frames);
        var a = new BridgeCodec.Assembler();
        String result = null;
        for (var f : frames) {
            var next = a.accept(f);
            if (next.isPresent()) result = next.get();
        }
        assertEquals(text, result);
    }

    @Test
    void rejectsOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> BridgeCodec.encode("x".repeat(BridgeCodec.RAW_LIMIT + 1)));
        assertThrows(IllegalArgumentException.class, () -> BridgeCodec.encode(random(3_000_000)));
    }

    @Test
    void rejectsBadVersionAndHeader() {
        var frame = BridgeCodec.encode("test").getFirst();
        frame[4] = 99;
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec.Assembler().accept(frame));
        frame[4] = 1;
        ByteBuffer.wrap(frame).putInt(29, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec.Assembler().accept(frame));
    }

    @Test
    void rejectsTruncatedAndOversizedFrames() {
        var a = new BridgeCodec.Assembler();
        assertThrows(IllegalArgumentException.class, () -> a.accept(new byte[36]));
        assertThrows(IllegalArgumentException.class, () -> a.accept(new byte[30_001]));
    }

    @Test
    void rejectsChecksumCorruptionAndRecovers() {
        var frame = BridgeCodec.encode("safe").getFirst();
        frame[33] ^= 1;
        var a = new BridgeCodec.Assembler();
        assertThrows(IllegalArgumentException.class, () -> a.accept(frame));
        assertEquals("next", a.accept(BridgeCodec.encode("next").getFirst()).orElseThrow());
    }

    @Test
    void handlesDuplicatesAndExpiry() {
        var frames = BridgeCodec.encode(random(100_000));
        var a = new BridgeCodec.Assembler();
        assertTrue(a.accept(frames.getFirst(), 0).isEmpty());
        assertTrue(a.accept(frames.getFirst(), 1).isEmpty());
        assertEquals(
                "new",
                a.accept(BridgeCodec.encode("new").getFirst(), 16_000_000_000L).orElseThrow());
    }

    @Test
    void rejectsMixedMessages() {
        var a = new BridgeCodec.Assembler();
        a.accept(BridgeCodec.encode(random(100_000)).getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> a.accept(BridgeCodec.encode("other").getFirst()));
    }

    @Test
    void rejectsExpandedLengthLie() {
        var frame = BridgeCodec.encode("x".repeat(10000)).getFirst();
        ByteBuffer.wrap(frame).putInt(29, 2);
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec.Assembler().accept(frame));
    }
}
