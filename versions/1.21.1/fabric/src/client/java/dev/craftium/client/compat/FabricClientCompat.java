package dev.craftium.client.compat;

import dev.craftium.Craftium;
import dev.craftium.compat.FabricCompat;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Minecraft 1.21.1 implementation; large payloads are split and joined as described in {@link FabricCompat}. */
public final class FabricClientCompat {
    private FabricClientCompat() {}

    private static final Map<ResourceLocation, Consumer<? extends CustomPacketPayload>> HANDLERS = new HashMap<>();
    private static final FabricCompat.Joiner FROM_SERVER = new FabricCompat.Joiner();
    private static boolean partsRegistered;

    private static void registerParts() {
        if (partsRegistered) return;
        partsRegistered = true;
        // Parts arrive on the network thread in order; joining stays there and the handler moves to the client thread.
        ClientPlayNetworking.registerGlobalReceiver(FabricCompat.Part.TYPE, (part, context) -> {
            byte[] whole;
            synchronized (FROM_SERVER) {
                whole = FROM_SERVER.accept(part);
            }
            if (whole != null) deliver(part.inner(), whole);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            synchronized (FROM_SERVER) {
                FROM_SERVER.clear();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void deliver(ResourceLocation id, byte[] bytes) {
        var large = (FabricCompat.Large<T>) FabricCompat.large(id);
        var handler = (Consumer<T>) HANDLERS.get(id);
        var connection = Minecraft.getInstance().getConnection();
        if (large == null || handler == null || connection == null) return;
        T payload;
        try {
            payload = FabricCompat.decode(large, bytes, connection.registryAccess());
        } catch (RuntimeException e) {
            Craftium.LOG.warn("Dropped a split {} from the server: {}", id, e.toString());
            return;
        }
        handler.accept(payload);
    }

    /** Handles a payload from the server on the network thread; the handler moves to the client thread itself. */
    public static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        HANDLERS.put(type.id(), handler);
        if (FabricCompat.large(type.id()) != null) registerParts();
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload));
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    public static void send(CustomPacketPayload payload) {
        var large = FabricCompat.large(payload.type().id());
        var connection = Minecraft.getInstance().getConnection();
        if (large == null || connection == null) {
            ClientPlayNetworking.send(payload);
            return;
        }
        byte[] bytes = FabricCompat.encode(large, payload, connection.registryAccess());
        if (bytes.length <= FabricCompat.SERVERBOUND_PART) ClientPlayNetworking.send(payload);
        else
            for (var part : FabricCompat.split(payload.type().id(), bytes, FabricCompat.SERVERBOUND_PART))
                ClientPlayNetworking.send(part);
    }

    public static KeyMapping registerKey(KeyMapping key) {
        return KeyBindingHelper.registerKeyBinding(key);
    }

    /** Draws on the HUD after the game's own elements. */
    public static void addHud(String name, Consumer<Gfx> draw) {
        HudRenderCallback.EVENT.register((graphics, delta) -> draw.accept(Gfx.of(graphics)));
    }
}
