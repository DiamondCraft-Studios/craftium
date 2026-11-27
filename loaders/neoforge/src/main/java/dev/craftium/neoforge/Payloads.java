package dev.craftium.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Craftium's payloads on NeoForge. The shared code names its payloads and handlers during startup, before NeoForge
 * asks for them, so they are kept here and handed to NeoForge in {@link #register}. A payload that goes both ways is
 * registered once, as bidirectional, with a handler that picks the side.
 *
 * <p>Every payload is optional, so a client or server without Craftium can still connect. NeoForge splits a payload
 * too large for one packet and joins it again on arrival, so the sizes the shared code gives are not needed here.
 */
public final class Payloads {
    private Payloads() {}

    private record Entry(StreamCodec<RegistryFriendlyByteBuf, ? extends CustomPacketPayload> codec, boolean toClient, boolean toServer) {}

    private static final Map<CustomPacketPayload.Type<?>, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, BiConsumer<ServerPlayer, CustomPacketPayload>> FROM_CLIENT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, Consumer<CustomPacketPayload>> FROM_SERVER =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean registered;

    public static synchronized <T extends CustomPacketPayload> void declare(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, boolean toClient) {
        if (registered) throw new IllegalStateException("Craftium payload " + type.id() + " declared after registration");
        var old = ENTRIES.get(type);
        ENTRIES.put(
                type,
                new Entry(
                        codec,
                        toClient || old != null && old.toClient(),
                        !toClient || old != null && old.toServer()));
    }

    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void onServer(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        FROM_CLIENT.put(type, (player, payload) -> handler.accept(player, (T) payload));
    }

    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> void onClient(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        FROM_SERVER.put(type, payload -> handler.accept((T) payload));
    }

    /** Hands every declared payload to NeoForge. */
    static synchronized void register(RegisterPayloadHandlersEvent event) {
        registered = true;
        var registrar = event.registrar("1").optional();
        for (var e : ENTRIES.entrySet()) registerOne(registrar, e.getKey(), e.getValue());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerOne(
            net.neoforged.neoforge.network.registration.PayloadRegistrar registrar,
            CustomPacketPayload.Type<?> type,
            Entry entry) {
        var t = (CustomPacketPayload.Type) type;
        var codec = (StreamCodec) entry.codec();
        net.neoforged.neoforge.network.handling.IPayloadHandler handler = (payload, context) ->
                handle((CustomPacketPayload) payload, (IPayloadContext) context);
        if (entry.toClient() && entry.toServer()) registrar.playBidirectional(t, codec, handler);
        else if (entry.toClient()) registrar.playToClient(t, codec, handler);
        else registrar.playToServer(t, codec, handler);
    }

    private static void handle(CustomPacketPayload payload, IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND) {
            var handler = FROM_CLIENT.get(payload.type());
            if (handler != null && context.player() instanceof ServerPlayer player) handler.accept(player, payload);
        } else {
            var handler = FROM_SERVER.get(payload.type());
            if (handler != null) handler.accept(payload);
        }
    }
}
