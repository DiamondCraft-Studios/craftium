package dev.craftium.compat;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Fabric API calls that differ between releases. Excluded from the Paper companion, which has no Fabric API. */
public final class FabricCompat {
    private FabricCompat(){}

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay(){return PayloadTypeRegistry.serverboundPlay();}
    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay(){return PayloadTypeRegistry.clientboundPlay();}
}
