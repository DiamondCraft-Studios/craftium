package dev.craftium.neoforge;

import dev.craftium.Craftium;
import dev.craftium.platform.Platform;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** The NeoForge entrypoint on both sides; see {@code dev.craftium.platform.Platform}. */
@Mod("craftium")
public final class CraftiumNeoForge {
    public CraftiumNeoForge(IEventBus modBus) {
        Platform.modBus = modBus;
        modBus.addListener(Payloads::register);
        Craftium.init();
    }
}
