package dev.craftium.compat;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Minecraft 1.21.10 implementation: Fabric API names the play registries by direction. */
public final class FabricCompat {
    private FabricCompat(){}

    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay(){return PayloadTypeRegistry.playC2S();}
    public static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay(){return PayloadTypeRegistry.playS2C();}
}
