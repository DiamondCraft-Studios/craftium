package dev.craftium.compat;

import dev.craftium.Craftium;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Minecraft 1.21.1 implementation. Fabric API for 1.21.1 has no registerLarge, and vanilla caps a custom payload at
 * 1 MiB to the client and 32 KiB to the server. A payload registered with a larger {@code maxSize} is encoded here and,
 * when it is over the cap, sent as numbered {@link Part}s that the other side joins and decodes with the same codec.
 */
public final class FabricCompat {
    private FabricCompat() {}

    /** What one side may send in a single custom payload, less room for the part's own header. */
    public static final int CLIENTBOUND_PART = 1_000_000, SERVERBOUND_PART = 30_000;

    /** One slice of a payload too large for a single packet. */
    public record Part(int message, int index, int count, ResourceLocation inner, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<Part> TYPE = new Type<>(Compat.id("craftium", "part"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Part> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.message);
                    buf.writeVarInt(p.index);
                    buf.writeVarInt(p.count);
                    buf.writeResourceLocation(p.inner);
                    buf.writeByteArray(p.bytes);
                },
                buf -> new Part(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readResourceLocation(),
                        buf.readByteArray(CLIENTBOUND_PART)));

        @Override
        public Type<Part> type() {
            return TYPE;
        }
    }

    /** A payload type that may be split: its codec and the largest it may encode to. */
    public record Large<T extends CustomPacketPayload>(StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {}

    private static final Map<ResourceLocation, Large<?>> LARGE = new HashMap<>();
    private static final Map<ResourceLocation, BiConsumer<ServerPlayer, ? extends CustomPacketPayload>> HANDLERS =
            new HashMap<>();
    private static final Map<UUID, Joiner> FROM_PLAYERS = new HashMap<>();
    private static boolean partsRegistered;
    private static int nextMessage;

    public static Large<?> large(ResourceLocation id) {
        return LARGE.get(id);
    }

    private static void registerParts() {
        if (partsRegistered) return;
        partsRegistered = true;
        PayloadTypeRegistry.playS2C().register(Part.TYPE, Part.CODEC);
        PayloadTypeRegistry.playC2S().register(Part.TYPE, Part.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Part.TYPE, (part, context) -> context.server()
                .execute(() -> {
                    var player = context.player();
                    var whole = FROM_PLAYERS
                            .computeIfAbsent(player.getUUID(), id -> new Joiner())
                            .accept(part);
                    if (whole != null) deliver(player, part.inner(), whole);
                }));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> server.execute(() -> FROM_PLAYERS.remove(handler.player.getUUID())));
    }

    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        PayloadTypeRegistry.playS2C().register(type, codec);
        if (maxSize > 0) {
            registerParts();
            LARGE.put(type.id(), new Large<>(codec, maxSize));
        }
    }

    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        if (maxSize > 0) {
            registerParts();
            LARGE.put(type.id(), new Large<>(codec, maxSize));
        }
    }

    /** Handles a payload from a player on the server thread, whole or joined from parts. */
    public static <T extends CustomPacketPayload> void receive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        HANDLERS.put(type.id(), handler);
        ServerPlayNetworking.registerGlobalReceiver(
                type, (payload, context) -> context.server().execute(() -> handler.accept(context.player(), payload)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void deliver(ServerPlayer player, ResourceLocation id, byte[] bytes) {
        var large = (Large<T>) LARGE.get(id);
        var handler = (BiConsumer<ServerPlayer, T>) HANDLERS.get(id);
        if (large == null || handler == null) return;
        T payload;
        try {
            payload = decode(large, bytes, player.registryAccess());
        } catch (RuntimeException e) {
            Craftium.LOG.warn("Dropped a split {} from {}: {}", id, player.getName().getString(), e.toString());
            return;
        }
        handler.accept(player, payload);
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        var large = LARGE.get(payload.type().id());
        if (large == null) {
            ServerPlayNetworking.send(player, payload);
            return;
        }
        byte[] bytes = encode(large, payload, player.registryAccess());
        if (bytes.length <= CLIENTBOUND_PART) ServerPlayNetworking.send(player, payload);
        else for (var part : split(payload.type().id(), bytes, CLIENTBOUND_PART)) ServerPlayNetworking.send(player, part);
    }

    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> byte[] encode(Large<?> large, CustomPacketPayload payload, RegistryAccess access) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            ((Large<T>) large).codec().encode(buf, (T) payload);
            if (buf.readableBytes() > large.maxSize())
                throw new IllegalArgumentException(
                        payload.type().id() + " is " + buf.readableBytes() + " bytes, over its " + large.maxSize());
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public static <T extends CustomPacketPayload> T decode(Large<T> large, byte[] bytes, RegistryAccess access) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), access);
        T payload = large.codec().decode(buf);
        if (buf.readableBytes() > 0) throw new IllegalArgumentException(buf.readableBytes() + " bytes left over");
        return payload;
    }

    public static synchronized Part[] split(ResourceLocation inner, byte[] bytes, int partSize) {
        int message = nextMessage++;
        int count = (bytes.length + partSize - 1) / partSize;
        var parts = new Part[count];
        for (int i = 0; i < count; i++) {
            int from = i * partSize;
            parts[i] = new Part(
                    message, i, count, inner, java.util.Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + partSize)));
        }
        return parts;
    }

    /**
     * Joins the parts from one sender. Parts of one message arrive in order; a sender keeps at most a few messages
     * open, and a message may not grow past the size its payload type was registered with.
     */
    public static final class Joiner {
        private static final int OPEN = 4;
        private final Map<Integer, java.io.ByteArrayOutputStream> open = new java.util.LinkedHashMap<>();
        private final Map<Integer, Integer> next = new HashMap<>();

        /** The whole message once its last part is in, otherwise null. Bad parts drop their message. */
        public byte[] accept(Part part) {
            var large = LARGE.get(part.inner());
            if (large == null || part.count() <= 0 || part.index() < 0 || part.index() >= part.count()) return null;
            if (part.index() == 0) {
                if (open.size() >= OPEN) {
                    var oldest = open.keySet().iterator().next();
                    open.remove(oldest);
                    next.remove(oldest);
                }
                open.put(part.message(), new java.io.ByteArrayOutputStream());
                next.put(part.message(), 0);
            }
            var into = open.get(part.message());
            if (into == null || next.get(part.message()) != part.index()
                    || into.size() + part.bytes().length > large.maxSize()) {
                open.remove(part.message());
                next.remove(part.message());
                return null;
            }
            into.writeBytes(part.bytes());
            if (part.index() + 1 < part.count()) {
                next.put(part.message(), part.index() + 1);
                return null;
            }
            open.remove(part.message());
            next.remove(part.message());
            return into.toByteArray();
        }

        public void clear() {
            open.clear();
            next.clear();
        }
    }
}
