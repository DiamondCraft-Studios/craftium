package dev.craftium.compat;

import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 1.21.10 implementation: Fabric API names the play registries by direction. */
public final class FabricCompat {
    private FabricCompat() {}

    /** A {@code maxSize} of 0 keeps vanilla's limit; a larger one lets Fabric API split the payload. */
    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        if (maxSize > 0) PayloadTypeRegistry.playS2C().registerLarge(type, codec, maxSize);
        else PayloadTypeRegistry.playS2C().register(type, codec);
    }

    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxSize) {
        if (maxSize > 0) PayloadTypeRegistry.playC2S().registerLarge(type, codec, maxSize);
        else PayloadTypeRegistry.playC2S().register(type, codec);
    }

    /** Handles a payload from a player on the server thread. */
    public static <T extends CustomPacketPayload> void receive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        ServerPlayNetworking.registerGlobalReceiver(
                type, (payload, context) -> context.server().execute(() -> handler.accept(context.player(), payload)));
    }

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
