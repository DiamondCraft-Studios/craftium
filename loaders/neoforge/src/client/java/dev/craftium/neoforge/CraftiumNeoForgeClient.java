package dev.craftium.neoforge;

import dev.craftium.client.CraftiumClient;
import dev.craftium.client.platform.ClientPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** The NeoForge client entrypoint, loaded on the client only, after {@link CraftiumNeoForge}. */
@Mod(value = "craftium", dist = Dist.CLIENT)
public final class CraftiumNeoForgeClient {
    public CraftiumNeoForgeClient(IEventBus modBus) {
        ClientPlatform.install(modBus);
        CraftiumClient.init();
    }
}
