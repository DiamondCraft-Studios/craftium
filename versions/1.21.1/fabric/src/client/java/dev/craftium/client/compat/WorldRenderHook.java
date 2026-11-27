package dev.craftium.client.compat;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Minecraft 1.21.1 on Fabric: runs the overlay at the end of the world pass. Fabric's last world-render event comes
 * before the game takes the camera's turn off the model-view matrix, which is what the overlay counts on.
 */
final class WorldRenderHook {
    private WorldRenderHook() {}

    static void register(Runnable draw) {
        WorldRenderEvents.LAST.register(context -> draw.run());
    }
}
