package dev.craftium.client.compat;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-side Fabric API calls that differ between Minecraft versions. */
public final class FabricClientCompat {
    private FabricClientCompat() {}

    /** Handles a payload from the server on the network thread; the handler moves to the client thread itself. */
    public static <T extends CustomPacketPayload> void receive(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload));
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    public static void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    public static KeyMapping registerKey(KeyMapping key) {
        return KeyBindingHelper.registerKeyBinding(key);
    }

    /** Draws on the HUD after the game's own elements. */
    public static void addHud(String name, Consumer<Gfx> draw) {
        HudElementRegistry.addLast(
                dev.craftium.compat.Compat.id("craftium", name), (raw, delta) -> draw.accept(Gfx.of(raw)));
    }
}
