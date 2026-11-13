package dev.craftium.compat;

import com.mojang.math.Transformation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import java.util.Set;
import java.util.function.Function;

/** Minecraft 1.21.10 implementation of the version boundary; see the 26.2 file in src/main for the contract. */
public final class Compat {
    private Compat(){}

    public static ResourceLocation id(String namespace,String path){return ResourceLocation.fromNamespaceAndPath(namespace,path);}
    public static ResourceLocation parse(String id){return ResourceLocation.parse(id);}
    public static ResourceLocation vanilla(String path){return ResourceLocation.withDefaultNamespace(path);}
    public static ResourceLocation tryParse(String id){return ResourceLocation.tryParse(id);}
    public static String keyName(ResourceKey<?> key){return key.location().toString();}
    /** Namespaced name of a tag, e.g. {@code minecraft:infiniburn_overworld}. */
    public static String tagName(net.minecraft.tags.TagKey<?> tag){return tag.location().toString();}

    public static boolean isGamemaster(ServerPlayer player){return player.hasPermissions(2);}
    public static CommandSourceStack gamemasterSource(ServerPlayer player){return player.createCommandSourceStack().withPermission(2);}
    public static boolean isGamemaster(CommandSourceStack source){return source.hasPermission(2);}

    public static Entity loadEntity(CompoundTag tag,Level level){
        return EntityType.loadEntityRecursive(tag,level,EntitySpawnReason.COMMAND,Function.identity());
    }
    public static Set<String> entityTags(Entity entity){return entity.getTags();}
    public static long chunkKey(int x,int z){return net.minecraft.world.level.ChunkPos.asLong(x,z);}
    public static Transformation identityTransform(){return Transformation.identity();}
}
