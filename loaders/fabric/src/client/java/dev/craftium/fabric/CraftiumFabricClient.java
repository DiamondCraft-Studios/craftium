package dev.craftium.fabric;

import dev.craftium.client.CraftiumClient;
import net.fabricmc.api.ClientModInitializer;

/** The Fabric client entrypoint; see {@code dev.craftium.client.platform.ClientPlatform}. */
public final class CraftiumFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CraftiumClient.init();
    }
}
