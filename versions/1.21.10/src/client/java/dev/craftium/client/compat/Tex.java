package dev.craftium.client.compat;

import net.minecraft.resources.ResourceLocation;

/** Minecraft 1.21.10 implementation: textures are identified by {@link ResourceLocation}. */
public record Tex(ResourceLocation id){
    public static Tex of(String namespace,String path){return new Tex(ResourceLocation.fromNamespaceAndPath(namespace,path));}
}
