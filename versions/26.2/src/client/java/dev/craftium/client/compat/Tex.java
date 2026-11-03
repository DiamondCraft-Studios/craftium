package dev.craftium.client.compat;

import net.minecraft.resources.Identifier;

/** A GUI texture handle; wraps the version's resource identifier type. 26.2 implementation. */
public record Tex(Identifier id){
    public static Tex of(String namespace,String path){return new Tex(Identifier.fromNamespaceAndPath(namespace,path));}
}
