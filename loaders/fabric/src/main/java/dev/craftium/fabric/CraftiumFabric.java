package dev.craftium.fabric;

import dev.craftium.Craftium;
import net.fabricmc.api.ModInitializer;

/** The Fabric entrypoint. Craftium subscribes to what it needs through {@code dev.craftium.platform.Platform}. */
public final class CraftiumFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Craftium.init();
    }
}
