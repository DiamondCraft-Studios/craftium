package dev.craftium.client.compat;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Minecraft 1.21.1 on NeoForge: runs the overlay after the weather, the last stage of the world pass that comes before
 * the game takes the camera's turn off the model-view matrix, which is what the overlay counts on.
 */
final class WorldRenderHook {
    private WorldRenderHook() {}

    static void register(Runnable draw) {
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) draw.run();
        });
    }
}
